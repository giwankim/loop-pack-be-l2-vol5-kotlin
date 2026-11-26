package com.loopers.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.wiremock.spring.InjectWireMock
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * The incident fixture [IncidentPg] on its own (spec 274, stories 20 and 21): what a registered order does when it is confirmed
 * through [mvc], whether the loss switch is real, and whether a second create for one PG order ID is reported. The incident itself
 * is `PaymentIncidentE2ETest`'s.
 *
 * Confirmations commit per request, so the class leaves the test transaction and empties the tables itself (`PaymentApiTest`'s
 * pattern).
 */
@EnableTestPg
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentPgTest(
    private val databaseCleanUp: DatabaseCleanUp,
) : BaseWebApiAdapterTest() {
    companion object {
        /** The card every confirmation sends. The number passes the Luhn check. The fixture's create stubs match this card. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        /** One product of 1,000 won, ordered twice. */
        private const val AMOUNT = 2_000L
    }

    /** Field injection, not the constructor. The reason is on the same field in `OrderPaymentIntegrationTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val incidentPg by lazy { IncidentPg(server) }

    private lateinit var owner: User

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        owner = prepareUser()
        prepareProduct(price = 1_000, stock = 10)
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    /** The PG holds the transaction as the lookup finds it: `SUCCESS`, under the key the fixture minted. */
    @Test
    fun `a registered normal order confirms as pending and finishes as successful on the PG's callback`() {
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        val registered = incidentPg.registerNormal(pgOrderId, amount = AMOUNT, card = CARD)

        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)
        assertDetail(orderId, status = "PAYMENT_PENDING", paymentStatus = "PENDING")

        assertThat(requestCallback(pgOrderId, registered.transactionKey)).hasStatusOk()
        assertDetail(orderId, status = "CONFIRMED", paymentStatus = "SUCCESS")
        assertThat(incidentPg.transactions()).containsExactly(
            PgTransaction(
                transactionKey = registered.transactionKey,
                pgOrderId = pgOrderId,
                amount = Money(AMOUNT),
                status = PgStatus.SUCCESS,
            ),
        )
        assertThat(incidentPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The create reaches the PG, which makes the `SUCCESS` transaction, and its response is lost. Commerce sends no lookup, so
     * the payment stays 결과 미확인 while the PG holds a successful transaction.
     */
    @Test
    fun `a registered lost order confirms as unknown while response loss is on`() {
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        val registered = incidentPg.registerLost(pgOrderId, amount = AMOUNT, card = CARD)

        incidentPg.startResponseLoss()
        try {
            assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)
        } finally {
            incidentPg.stopResponseLoss()
        }

        assertDetail(orderId, status = "PAYMENT_PENDING", paymentStatus = "UNKNOWN")
        assertThat(registered.status).isEqualTo(PgStatus.SUCCESS)
        assertThat(incidentPg.transactions()).containsExactly(registered)
        assertThat(incidentPg.createCount(pgOrderId)).isOne()
        assertThat(incidentPg.listLookupCount(pgOrderId)).isZero()
        assertThat(incidentPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The switch is real: released, the loss stub is gone and the lost order's create is answered with its `SUCCESS` transaction,
     * so the order is confirmed with one create. A second release changes nothing.
     */
    @Test
    fun `response loss is off until started and on while started, and once released a lost order's create is answered`() {
        val orderId = prepareOrder(owner, listOf(product), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        incidentPg.registerLost(pgOrderId, amount = AMOUNT, card = CARD)
        assertThat(incidentPg.isResponseLossOn()).isFalse()

        try {
            incidentPg.startResponseLoss()
            assertThat(incidentPg.isResponseLossOn()).isTrue()
        } finally {
            incidentPg.stopResponseLoss()
        }
        incidentPg.stopResponseLoss()

        assertThat(incidentPg.isResponseLossOn()).isFalse()
        assertThat(requestConfirm(orderId)).hasStatusOk()
        assertDetail(orderId, status = "CONFIRMED", paymentStatus = "SUCCESS")
        assertThat(incidentPg.createCount(pgOrderId)).isOne()
        assertThat(incidentPg.unmatchedRequests()).isEmpty()
    }

    /**
     * Commerce never sends a second create for one PG order ID, so the scene sends it to the test PG itself, as a client that
     * retried the create would. The fixture reports it whoever sent it. The other order, created once, is not reported.
     */
    @Test
    fun `a second create for one PG order ID is reported, and an order created once is not`() {
        val confirmedId = prepareOrder(owner, listOf(product), quantity = 2).id
        val confirmedPgOrderId = nextPgOrderId()
        incidentPg.registerNormal(confirmedPgOrderId, amount = AMOUNT, card = CARD)
        val retriedId = prepareOrder(owner, listOf(product), quantity = 2).id
        val retriedPgOrderId = nextPgOrderId()
        incidentPg.registerNormal(retriedPgOrderId, amount = AMOUNT, card = CARD)
        assertThat(requestConfirm(confirmedId)).hasStatus(HttpStatus.ACCEPTED)
        assertThat(requestConfirm(retriedId)).hasStatus(HttpStatus.ACCEPTED)
        assertThat(incidentPg.pgOrderIdsCreatedMoreThanOnce()).isEmpty()

        val retried = sendCreateToPg(retriedPgOrderId)

        assertThat(retried.statusCode()).isEqualTo(202)
        assertThat(incidentPg.pgOrderIdsCreatedMoreThanOnce()).containsExactly(retriedPgOrderId)
    }

    /**
     * The incident test reads the created PG order IDs in its `finally`, so a malformed create must not throw there and hide the
     * phase's failure. The scene sends a create with no `orderId` to the test PG itself, since commerce always sends one. A
     * registered order's stub is in place, and the same create with its `orderId` is accepted, so the missing field alone is why the
     * first one matched no stub.
     */
    @Test
    fun `a create without a PG order ID matches no registered stub, and is left out of the created ones`() {
        val pgOrderId = nextPgOrderId()
        incidentPg.registerNormal(pgOrderId, amount = AMOUNT, card = CARD)
        val json = """
            {"cardType":"${CARD.type}","cardNo":"${CARD.number}","amount":$AMOUNT,
             "callbackUrl":"http://127.0.0.1:8080/api/v1/payments/callback"}
        """

        val withoutOrderId = sendCreateJsonToPg(json)
        val withOrderId = sendCreateToPg(pgOrderId)

        assertThat(withoutOrderId.statusCode()).isEqualTo(404)
        assertThat(withOrderId.statusCode()).isEqualTo(202)
        assertThat(incidentPg.createdPgOrderIds()).containsExactly(pgOrderId)
        assertThat(incidentPg.pgOrderIdsCreatedMoreThanOnce()).isEmpty()
        assertThat(incidentPg.createCount()).isEqualTo(2)
        assertThat(incidentPg.unmatchedRequests()).hasSize(1)
    }

    /** The create commerce sent for [pgOrderId], sent again over HTTP to the test PG with the PG contract's headers. */
    private fun sendCreateToPg(pgOrderId: String): HttpResponse<String> {
        val json = """
            {"orderId":"$pgOrderId","cardType":"${CARD.type}","cardNo":"${CARD.number}","amount":$AMOUNT,
             "callbackUrl":"http://127.0.0.1:8080/api/v1/payments/callback"}
        """

        return sendCreateJsonToPg(json)
    }

    /** A create carrying [json] as its body, sent over HTTP to the test PG with the PG contract's headers. */
    private fun sendCreateJsonToPg(json: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("${server.baseUrl()}/api/v1/payments"))
            .header("X-USER-ID", TestPg.PG_USER_ID)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        return HttpClient.newHttpClient().use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }
    }

    private fun assertDetail(orderId: Long, status: String, paymentStatus: String) {
        val detail = mvc.get().uri("/api/v1/orders/$orderId").header(UserIdHeader.NAME, owner.id).exchange()
        val body = assertThat(detail).hasStatusOk().bodyJson()

        body.extractingPath("$.data.status").isEqualTo(status)
        body.extractingPath("$.data.paymentStatus").isEqualTo(paymentStatus)
    }

    private fun requestConfirm(orderId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, owner.id)
            .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""")
            .exchange()
    }

    /** The PG contract's callback body. The status is `SUCCESS`, the transaction's as the PG holds it. */
    private fun requestCallback(pgOrderId: String, transactionKey: String): MvcTestResult {
        return mvc.post().uri("/api/v1/payments/callback")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"transactionKey":"$transactionKey","orderId":"$pgOrderId","status":"SUCCESS"}""")
            .exchange()
    }
}
