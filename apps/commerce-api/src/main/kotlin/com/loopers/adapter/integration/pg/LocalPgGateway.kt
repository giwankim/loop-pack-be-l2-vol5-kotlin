package com.loopers.adapter.integration.pg

import com.loopers.application.payment.required.PaymentGateway
import com.loopers.application.payment.required.PgOutcomeUnknownException
import com.loopers.application.payment.required.PgRejectedException
import com.loopers.application.payment.required.PgUnavailableException
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.InvalidMoneyException
import com.loopers.domain.shared.Money
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import org.springframework.web.client.RestClientException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Creates and looks up PG transactions on the local test PG (ADR 0021). It translates HTTP into PG outcomes and never compares
 * them with the commerce side's records.
 *
 * Returns only the PG transactions the PG reports and throws the port's exceptions for every other outcome (ADR 0023).
 * Responses are read with `exchange`, not `retrieve()`. `retrieve()` treats any 2xx as success and any 4xx or 5xx as one kind
 * of error, but here only a 202 is an accepted create, a 404 on a key lookup finds none, and a 422 body must be read by the
 * strict converter (payments design 4.2). Transport errors and unreadable responses are both
 * [RestClientException]s, so one catch per call turns them into [PgOutcomeUnknownException] for a create and
 * [PgUnavailableException] for a lookup. Jackson's own failures, such as a request it cannot write, are
 * `HttpMessageConversionException`s, not [RestClientException]s: programming errors that propagate as is. A misconfigured
 * converter setup is caught, though. With no converter to write the request or read the response, Spring throws a
 * [RestClientException], so the call surfaces as outcome unknown or unavailable although the fault is ours. Port exceptions
 * thrown inside `exchange` also show up as errors on that call's observation.
 *
 * The `RestClient` is built from Boot's preconfigured `RestClient.Builder`, which already records PG calls as observations
 * when an observation registry is present. On top of it go the base URL, the `X-USER-ID` header, the request factory from
 * [LocalPgGatewayConfig] and a strict PG-only mapper.
 */
@Component
class LocalPgGateway(
    restClientBuilder: RestClient.Builder,
    pgRequestFactory: HttpComponentsClientHttpRequestFactory,
    properties: PgProperties,
) : PaymentGateway {
    companion object {
        /** Carries the synthetic account the PG contract requires. Sent on every request. */
        private const val USER_ID_HEADER = "X-USER-ID"

        private const val PAYMENTS_PATH = "/api/v1/payments"

        /** The transaction key is a path variable, so the URI builder encodes it. */
        private const val TRANSACTION_PATH = "$PAYMENTS_PATH/{transactionKey}"

        /** The PG order ID is a query variable, so the URI builder encodes it, `&` and `+` included. */
        private const val LIST_PATH = "$PAYMENTS_PATH?orderId={pgOrderId}"
    }

    /**
     * Boot's converter configuration has already registered the defaults and a JSON converter with Boot's mapper; this runs
     * after it. `disableDefaults()` drops the converters detected on the classpath (YAML, XML, ...), and since there is only one
     * JSON slot, `withJsonConverter` replaces Boot's converter with the PG-only one. Boot's String and form converters remain,
     * but PG bodies are read and written only by this JSON converter.
     */
    private val restClient = restClientBuilder
        .baseUrl(properties.baseUrl)
        .defaultHeader(USER_ID_HEADER, properties.userId)
        .requestFactory(pgRequestFactory)
        .configureMessageConverters {
            it.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(pgJsonMapper()))
        }
        .build()

    private val callbackUrl = properties.callbackUrl

    override fun createTransaction(pgOrderId: String, amount: Money, card: CreditCard): PgTransaction {
        val request = PgPaymentCreateRequest(
            orderId = pgOrderId,
            cardType = card.type,
            cardNo = card.number,
            amount = amount.amount,
            callbackUrl = callbackUrl,
        )

        return try {
            restClient.post()
                .uri(PAYMENTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange { _, response -> readCreateResponse(response) }
        } catch (e: RestClientException) {
            throw PgOutcomeUnknownException(e.failureKind())
        }
    }

    /**
     * Only a 202 with a well-formed PG transaction is an accepted create, whatever its PG status. A 422 is a rejection only when
     * its body is an exact pre-create rejection. Every other status is outcome unknown.
     */
    private fun readCreateResponse(response: ConvertibleClientHttpResponse): PgTransaction {
        val status = response.statusCode.value()
        if (status == HttpStatus.ACCEPTED.value()) {
            return response.readPgTransaction()
        }

        if (status == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
            val rejection = response.readExactPgRejection()
                ?: throw PgOutcomeUnknownException("HTTP $status")

            throw PgRejectedException(rejection)
        }

        throw PgOutcomeUnknownException("HTTP $status")
    }

    override fun retrieveTransaction(transactionKey: String): PgTransaction? {
        return try {
            restClient.get()
                .uri(TRANSACTION_PATH, transactionKey)
                .exchange { _, response -> readRetrieveResponse(response) }
        } catch (e: RestClientException) {
            throw PgUnavailableException(e.failureKind())
        }
    }

    /**
     * A 200 with a well-formed PG transaction finds that transaction and a 404 finds none (ADR 0022 rule 3). Every other status
     * is unavailable. The body of a 404 is not read.
     */
    private fun readRetrieveResponse(response: ConvertibleClientHttpResponse): PgTransaction? {
        val status = response.statusCode.value()
        if (status == HttpStatus.NOT_FOUND.value()) {
            return null
        }

        if (status != HttpStatus.OK.value()) {
            throw PgUnavailableException("HTTP $status")
        }

        return response.readPgTransaction()
    }

    override fun listTransactions(pgOrderId: String): List<PgTransaction> {
        return try {
            restClient.get()
                .uri(LIST_PATH, pgOrderId)
                .exchange { _, response -> readListResponse(response) }
        } catch (e: RestClientException) {
            throw PgUnavailableException(e.failureKind())
        }
    }

    /**
     * Only a 200 with a JSON array of well-formed PG transactions is a result, and an empty array finds none. Every other status,
     * 404 included, is unavailable.
     */
    private fun readListResponse(response: ConvertibleClientHttpResponse): List<PgTransaction> {
        val status = response.statusCode.value()
        if (status != HttpStatus.OK.value()) {
            throw PgUnavailableException("HTTP $status")
        }

        return response.readPgTransactions()
    }
}

/** A well-formed PG transaction. A missing body is unreadable. */
private fun ConvertibleClientHttpResponse.readPgTransaction(): PgTransaction {
    val transaction = bodyTo(PgTransactionResponse::class.java)
        ?: throw UnreadablePgResponseException("A ${statusCode.value()} has no body.")

    return transaction.toPgTransaction()
}

/** A JSON array of well-formed PG transactions. A missing body or a null element is unreadable. */
private fun ConvertibleClientHttpResponse.readPgTransactions(): List<PgTransaction> {
    val transactions = bodyTo(object : ParameterizedTypeReference<List<PgTransactionResponse?>>() {})
        ?: throw UnreadablePgResponseException("A ${statusCode.value()} has no body.")
    if (null in transactions) {
        throw UnreadablePgResponseException("The array has a null element.")
    }

    return transactions.filterNotNull().map { it.toPgTransaction() }
}

/**
 * The exact pre-create rejection, or `null` when the body is missing or inexact. Only an exact one proves that no PG transaction
 * exists (ADR 0022 rule 2). Whether the echoed PG order ID and amount match the request is for the payment to check.
 */
private fun ConvertibleClientHttpResponse.readExactPgRejection(): PgRejection? {
    val rejection = bodyTo(PgRejectionResponse::class.java)
    if (rejection == null || !rejection.isExact()) {
        return null
    }

    return rejection.toPgRejection()
}

/**
 * A strict mapper for the PG's JSON only. Boot's mapper (`supports/jackson`) is lenient toward clients of our own API, and
 * that leniency must not leak into reading someone else's contract.
 *
 * - A single value is not read as an array, nor an empty string as null.
 * - Null and missing primitives are rejected. The Kotlin module rejects them for non-null Kotlin types.
 * - Scalars are not coerced: `"7000"` is not a number and `"false"` is not a boolean. Amounts are whole won, so `7000.5` is
 *   not truncated to an integer either.
 * - Unknown properties are ignored, so fields the PG adds later don't break reading the contract's fields.
 */
private fun pgJsonMapper(): JsonMapper {
    return jacksonMapperBuilder()
        .disable(
            DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
            DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
            DeserializationFeature.ACCEPT_FLOAT_AS_INT,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        )
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build()
}

/** The innermost exception type in the chain. Messages are left out because they can carry fragments of the response body. */
private fun RestClientException.failureKind(): String {
    return (rootCause ?: this).javaClass.simpleName
}

/**
 * Jackson parsed the PG's response but it does not match the contract: an empty body, a blank transaction key, a null array
 * element, an unknown PG status or a negative amount. Extends [RestClientException] so that the one catch that turns transport
 * and Jackson errors into outcome unknown or unavailable handles this as well.
 *
 * Only the body readers and the JSON types throw it, and the status mappers throw only the port's exceptions. The readers are
 * the only code that touches the JSON types, and they extend `ConvertibleClientHttpResponse`, which exists only inside
 * `exchange`, so this always reaches the catch of the call that read the body.
 */
private class UnreadablePgResponseException(
    message: String,
    cause: Throwable? = null,
) : RestClientException(message, cause)

/** The create request body. The amount is a JSON number. */
private data class PgPaymentCreateRequest(
    val orderId: String,
    val cardType: String,
    val cardNo: String,
    val amount: Long,
    val callbackUrl: String,
)

/**
 * A PG transaction as JSON. The PG status is read as a string and mapped here, so an unknown value is unreadable. So is a
 * blank transaction key, because the PG transaction could never be looked up by it.
 */
private data class PgTransactionResponse(
    val transactionKey: String,
    val orderId: String,
    val amount: Long,
    val status: String,
) {
    fun toPgTransaction(): PgTransaction {
        if (transactionKey.isBlank()) {
            throw UnreadablePgResponseException("The transaction key is blank.")
        }

        val pgStatus = PgStatus.entries.find { it.name == status }
            ?: throw UnreadablePgResponseException("Unknown PG status.")

        return PgTransaction(transactionKey = transactionKey, pgOrderId = orderId, amount = pgMoney(amount), status = pgStatus)
    }
}

/**
 * A pre-create rejection as JSON. `transactionCreated` must be a JSON boolean, so a string `"false"` or a missing value is
 * unreadable.
 */
private data class PgRejectionResponse(
    val code: String,
    val transactionCreated: Boolean,
    val orderId: String,
    val amount: Long,
) {
    companion object {
        /** The code the test PG sends only with a pre-create rejection. */
        private const val REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE"
    }

    /** The exact `code` with `transactionCreated` set to `false`. Anything less does not prove that no PG transaction exists. */
    fun isExact(): Boolean {
        return code == REJECTED_BEFORE_CREATE && !transactionCreated
    }

    fun toPgRejection(): PgRejection {
        return PgRejection(pgOrderId = orderId, amount = pgMoney(amount))
    }
}

/** An amount sent by the PG. A negative one is unreadable. */
private fun pgMoney(amount: Long): Money {
    return try {
        Money(amount)
    } catch (e: InvalidMoneyException) {
        throw UnreadablePgResponseException("The PG sent a negative amount.", e)
    }
}
