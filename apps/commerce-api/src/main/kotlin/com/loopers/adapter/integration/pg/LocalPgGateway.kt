package com.loopers.adapter.integration.pg

import com.loopers.application.payment.required.PaymentGateway
import com.loopers.application.payment.required.PaymentRequestResult
import com.loopers.domain.payment.Card
import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.InvalidMoneyException
import com.loopers.domain.shared.Money
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import org.springframework.web.client.RestClientException
import kotlin.time.measureTimedValue

private val log = KotlinLogging.logger {}

/**
 * 로컬 테스트 PG에 PG 거래를 요청한다(ADR 0021). HTTP를 PG의 결과 값으로 옮길 뿐 커머스의 기록과 견주지 않는다.
 *
 * PG의 결과를 던지지 않는다(ADR 0022 규칙 4). 응답은 `retrieve()`가 아니라 `exchange`로 받아 상태 코드를 값으로 다룬다.
 * 전송 예외(`ResourceAccessException`)와 응답의 해석 오류는 모두 [RestClientException]이라 catch 하나가 결과 미확인으로 옮긴다.
 * 그 밖의 예외(요청을 직렬화하지 못한 것 등)는 프로그래밍 오류라 그대로 던진다.
 *
 * 호출마다 로그 한 줄을 쓴다. 메서드, 경로, 결과와 HTTP 상태나 실패의 종류, 걸린 시간, PG 주문 번호, 거래 키를 담고 본문과 카드 번호는 담지 않는다.
 * 결과 미확인은 WARN이다.
 */
class LocalPgGateway(
    private val restClient: RestClient,
    private val callbackUrl: String,
) : PaymentGateway {
    companion object {
        private const val PAYMENTS_PATH = "/api/v1/payments"
    }

    override fun requestPayment(pgOrderId: String, amount: Money, card: Card): PaymentRequestResult {
        val (result, elapsed) = measureTimedValue { create(pgOrderId, amount, card) }

        val transactionKey = (result as? PaymentRequestResult.Accepted)?.transaction?.transactionKey
        val message = {
            "PG POST $PAYMENTS_PATH ${result.summary()} in ${elapsed.inWholeMilliseconds} ms, " +
                "pgOrderId=$pgOrderId, transactionKey=$transactionKey"
        }
        if (result is PaymentRequestResult.Unknown) {
            log.warn(message)
        } else {
            log.info(message)
        }

        return result
    }

    private fun create(pgOrderId: String, amount: Money, card: Card): PaymentRequestResult {
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
            PaymentRequestResult.Unknown(e.failureKind())
        }
    }

    /**
     * 202와 잘 짜인 PG 거래만 받아들여진 생성이다. PG 상태가 무엇이든 그렇다. 422는 본문이 정확한 생성 전 거절일 때만 거절이다.
     * 그 밖의 상태 코드는 모두 결과 미확인이다.
     */
    private fun readCreateResponse(response: ConvertibleClientHttpResponse): PaymentRequestResult {
        val status = response.statusCode.value()
        if (status == HttpStatus.ACCEPTED.value()) {
            return readAccepted(response)
        }

        if (status == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
            return readRejection(response)
        }

        return PaymentRequestResult.Unknown("HTTP $status")
    }

    private fun readAccepted(response: ConvertibleClientHttpResponse): PaymentRequestResult {
        val transaction = response.bodyTo(PgTransactionResponse::class.java)
            ?: throw UnreadablePgResponseException("202에 본문이 없다.")

        return PaymentRequestResult.Accepted(transaction.toPgTransaction())
    }

    /**
     * 정확한 생성 전 거절만 PG 거래가 없다는 증거다(ADR 0022 규칙 2). 본문 없는 422와 정확하지 않은 본문은 결과 미확인이다.
     * 되돌려 받은 PG 주문 번호와 금액이 보낸 것과 같은지는 보지 않는다. 그것은 결제가 본다.
     */
    private fun readRejection(response: ConvertibleClientHttpResponse): PaymentRequestResult {
        val rejection = response.bodyTo(PgRejectionResponse::class.java)
        if (rejection == null || !rejection.isExact()) {
            return PaymentRequestResult.Unknown("HTTP ${HttpStatus.UNPROCESSABLE_CONTENT.value()}")
        }

        return PaymentRequestResult.Rejected(rejection.toPgRejection())
    }
}

/**
 * 로그에 쓰는 결과와 HTTP 상태나 실패의 종류. 받아들여진 생성과 생성 전 거절은 상태 코드가 하나로 정해져 있다(ADR 0022 규칙 2).
 * 결과 미확인의 [PaymentRequestResult.Unknown.cause]는 받은 상태 코드나 실패의 종류다.
 */
private fun PaymentRequestResult.summary(): String {
    return when (this) {
        is PaymentRequestResult.Accepted -> "Accepted (HTTP ${HttpStatus.ACCEPTED.value()})"
        is PaymentRequestResult.Rejected -> "Rejected (HTTP ${HttpStatus.UNPROCESSABLE_CONTENT.value()})"
        is PaymentRequestResult.Unknown -> "Unknown ($cause)"
    }
}

/** 실패의 종류. 예외 사슬의 맨 안쪽 타입이다. 메시지는 응답 본문의 조각을 담을 수 있어 쓰지 않는다. */
private fun RestClientException.failureKind(): String {
    return (rootCause ?: this).javaClass.simpleName
}

/**
 * PG의 응답을 계약대로 읽지 못했다. Jackson이 읽은 뒤의 해석(빈 본문, 빈 거래 키, 모르는 PG 상태, 음수 금액)이 실패한 것이다.
 * [RestClientException]을 넓혀, 전송 예외와 Jackson의 해석 오류를 결과 미확인으로 옮기는 catch 하나가 이것도 받게 한다.
 */
private class UnreadablePgResponseException(
    message: String,
    cause: Throwable? = null,
) : RestClientException(message, cause)

/** 생성 요청의 본문. 금액은 JSON 숫자다. */
private data class PgPaymentCreateRequest(
    val orderId: String,
    val cardType: String,
    val cardNo: String,
    val amount: Long,
    val callbackUrl: String,
)

/**
 * PG 거래의 JSON. PG 상태는 문자열로 읽어 어댑터가 옮긴다. 모르는 값은 해석 실패다.
 * 빈 거래 키도 해석 실패다. 그 키로는 거래를 다시 조회할 수 없다.
 */
private data class PgTransactionResponse(
    val transactionKey: String,
    val orderId: String,
    val amount: Long,
    val status: String,
) {
    fun toPgTransaction(): PgTransaction {
        if (transactionKey.isBlank()) {
            throw UnreadablePgResponseException("PG 거래의 거래 키가 비었다.")
        }

        val pgStatus = PgStatus.entries.find { it.name == status }
            ?: throw UnreadablePgResponseException("모르는 PG 상태다.")

        return PgTransaction(transactionKey = transactionKey, pgOrderId = orderId, amount = pgMoney(amount), status = pgStatus)
    }
}

/** 생성 전 거절의 JSON. `transactionCreated`는 JSON boolean만 읽는다. 문자열 `"false"`나 빠진 값은 해석 실패다. */
private data class PgRejectionResponse(
    val code: String,
    val transactionCreated: Boolean,
    val orderId: String,
    val amount: Long,
) {
    companion object {
        /** 테스트 PG가 생성 전 거절에만 싣는 code. */
        private const val REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE"
    }

    /** 정확한 `code`이고 `transactionCreated`가 `false`인가. 둘 중 하나라도 아니면 PG 거래가 없다는 증거가 아니다. */
    fun isExact(): Boolean {
        return code == REJECTED_BEFORE_CREATE && !transactionCreated
    }

    fun toPgRejection(): PgRejection {
        return PgRejection(pgOrderId = orderId, amount = pgMoney(amount))
    }
}

/** PG가 보낸 금액. 음수는 해석 실패다. */
private fun pgMoney(amount: Long): Money {
    return try {
        Money(amount)
    } catch (e: InvalidMoneyException) {
        throw UnreadablePgResponseException("PG가 보낸 금액이 음수다.", e)
    }
}
