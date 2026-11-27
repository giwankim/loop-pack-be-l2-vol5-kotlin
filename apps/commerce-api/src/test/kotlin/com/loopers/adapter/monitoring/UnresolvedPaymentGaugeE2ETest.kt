package com.loopers.adapter.monitoring

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.order.OrderPaymentSagaSteps
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.failTxAOnceAfterItsWrites
import com.loopers.support.failTxBOnceAfterItsWrites
import com.loopers.support.test.BaseRealPortWebApiAdapterTest
import com.ninjasquad.springmockk.MockkSpyBean
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
import java.util.UUID

/**
 * The `commerce.payment.unresolved` gauge as a scraper reads it: the Prometheus text fetched over real HTTP from the injected
 * management port (spec 281). No scene reads the gauge from a bean or counts calls to its port. Orders are confirmed and
 * reconciled through [mvc] against the test PG.
 *
 * The gauge is a number in the process's memory, so it outlives the rows [DatabaseCleanUp] empties between scenes. Every scene
 * reads both series first and asserts how far they moved from that reading ([changeSince]). The number moves before the response
 * returns, so no scene waits. Every scene but one resolves the payments it leaves unresolved and asserts that it leaves the
 * gauge where it found it. The one is the payment this process never saw start, so in this class's context that scene begins at 0
 * and goes below it, and a scene that left a residue would fail on its own last assertion first.
 *
 * Confirmations commit per request, so the class leaves the test transaction (`OrderConfirmationApiTest`'s pattern).
 */
@EnableTestPg
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UnresolvedPaymentGaugeE2ETest(
    private val databaseCleanUp: DatabaseCleanUp,
) : BaseRealPortWebApiAdapterTest() {
    companion object {
        /** The card every confirmation sends. The number passes the Luhn check. The create stubs match this card. */
        private val CARD = CreditCard(type = "SAMSUNG", number = "4111-1111-1111-1111")

        private const val TRANSACTION_KEY = "20261127:TR:gauge1"

        /** The gauge's `status` label values, the two unresolved payment statuses. */
        private val STATUSES = listOf("UNKNOWN", "PENDING")

        private val UNCHANGED = STATUSES.associateWith { 0.0 }
    }

    /** Field injection, not the constructor. The reason is on the same field in `OrderPaymentIntegrationTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** Where a scene makes TX A or TX B throw after its writes. Unstubbed, they are the real ones. */
    @MockkSpyBean
    private lateinit var orderPaymentSagaSteps: OrderPaymentSagaSteps

    private lateinit var owner: User

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        owner = prepareUser()
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `the Prometheus text holds the gauge for both unresolved statuses while health is UP`() {
        val health = managementGet("/actuator/health")
        val prometheus = managementGet("/actuator/prometheus")

        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")
        assertThat(prometheus.statusCode()).isEqualTo(200)
        assertThat(prometheus.body())
            .contains("commerce_payment_unresolved{application=\"commerce-api\",status=\"UNKNOWN\"}")
            .contains("commerce_payment_unresolved{application=\"commerce-api\",status=\"PENDING\"}")
        assertThat(prometheus.body().lineSequence().single { it.startsWith("# HELP commerce_payment_unresolved ") })
            .contains("unresolved payments", "since this process started", "can be negative", "aggregate with sum")
    }

    /**
     * The incident in one order: the create's response is lost, so the payment is 결과 미확인 and the gauge says one more while
     * health says UP. The customer's reconcile finds the successful PG transaction and takes it back. The create is never sent again.
     */
    @Test
    fun `a lost create response adds one unknown payment while health is UP, and the reconcile that finds it takes it back`() {
        val before = scrape()
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(pgOrderId, amount = 2_000, card = CARD)

        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)

        assertThat(changeSince(before)).isEqualTo(mapOf("UNKNOWN" to 1.0, "PENDING" to 0.0))
        val health = managementGet("/actuator/health")
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")

        testPg.hasTransactions(pgOrderId, transaction(pgOrderId, PgStatus.SUCCESS))
        assertThat(requestReconcile(orderId)).hasStatusOk()

        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The payment moves from 결과 미확인 to 처리 중 within one confirm, so it moves between the series and the total grows by one. A
     * reconcile that finds the PG still processing it is an unchanged TX B and moves nothing.
     */
    @Test
    fun `an accepted create still pending at the PG leaves unknown where it was and adds one pending payment`() {
        val before = scrape()
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(pgOrderId, amount = 2_000, card = CARD, transactionKey = TRANSACTION_KEY, pgStatus = "PENDING")

        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.ACCEPTED)

        assertThat(changeSince(before)).isEqualTo(mapOf("UNKNOWN" to 0.0, "PENDING" to 1.0))

        testPg.hasTransactions(pgOrderId, transaction(pgOrderId, PgStatus.PENDING))
        assertThat(requestReconcile(orderId)).hasStatus(HttpStatus.ACCEPTED)
        assertThat(changeSince(before)).isEqualTo(mapOf("UNKNOWN" to 0.0, "PENDING" to 1.0))

        testPg.hasTransactions(pgOrderId, transaction(pgOrderId, PgStatus.SUCCESS))
        assertThat(requestReconcile(orderId)).hasStatusOk()
        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** TX A's one more 결과 미확인 payment and the rejection's TX B taking it to 실패 cancel out: a rejection is no stuck work. */
    @Test
    fun `a create rejected before creation leaves both series where they started`() {
        val before = scrape()
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        testPg.rejectsBeforeCreate(nextPgOrderId(), amount = 2_000, card = CARD)

        assertThat(requestConfirm(orderId)).hasStatusOk()

        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
    }

    /** A replay answers the stored state without TX A starting a payment (ADR 0024 rule 3), so a retrying client adds nothing. */
    @Test
    fun `a replayed confirm with the same idempotency key adds nothing`() {
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.losesCreateResponse(pgOrderId, amount = 2_000, card = CARD)
        val idempotencyKey = UUID.randomUUID().toString()
        assertThat(requestConfirm(orderId, idempotencyKey)).hasStatus(HttpStatus.ACCEPTED)
        val before = scrape()

        assertThat(requestConfirm(orderId, idempotencyKey)).hasStatus(HttpStatus.ACCEPTED)

        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
        assertThat(testPg.createCount()).isOne()

        testPg.hasTransactions(pgOrderId, transaction(pgOrderId, PgStatus.SUCCESS))
        assertThat(requestReconcile(orderId)).hasStatusOk()
        assertThat(changeSince(before)).isEqualTo(mapOf("UNKNOWN" to -1.0, "PENDING" to 0.0))
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * A TX A that rolls back after writing the payment leaves no payment, so it leaves no number either: the number follows
     * committed state only. The payment takes its PG order ID before the failure, so the scene names one, and the retry after the
     * failure starts the payment for real and goes on to the create.
     */
    @Test
    fun `a TX A that rolls back after starting the payment leaves both series where they started`() {
        val before = scrape()
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        nextPgOrderId()
        orderPaymentSagaSteps.failTxAOnceAfterItsWrites(entityManager)

        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)

        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
        assertThat(testPg.createCount()).isZero()

        testPg.rejectsBeforeCreate(nextPgOrderId(), amount = 2_000, card = CARD)
        assertThat(requestConfirm(orderId)).hasStatusOk()
        assertThat(testPg.createCount()).isOne()
        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
    }

    /**
     * The PG accepted the create with a successful transaction, and TX B rolled back before applying it, so the payment is still
     * 결과 미확인 and the number is where TX A left it. The customer's reconcile then applies the same transaction and takes it back.
     */
    @Test
    fun `a TX B that rolls back leaves the number as TX A left it`() {
        val before = scrape()
        val orderId = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2).id
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(pgOrderId, amount = 2_000, card = CARD, transactionKey = TRANSACTION_KEY, pgStatus = "SUCCESS")
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)

        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)

        assertThat(changeSince(before)).isEqualTo(mapOf("UNKNOWN" to 1.0, "PENDING" to 0.0))

        testPg.hasTransactions(pgOrderId, transaction(pgOrderId, PgStatus.SUCCESS))
        assertThat(requestReconcile(orderId)).hasStatusOk()
        assertThat(changeSince(before)).isEqualTo(UNCHANGED)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * A payment this process never saw start stands in for one a restarted instance or another instance started: it is prepared
     * through the payment's provided port, not through a confirm, so the number never went up for it. Resolving it takes the
     * number down by one. The scene starts at 0 (the class's KDoc says why), so the text must carry −1, not a number held at 0.
     */
    @Test
    fun `reconciling an unknown payment this process never saw start takes unknown down by one`() {
        val order = prepareOrder(owner, listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 2)
        val pgOrderId = nextPgOrderId()
        startPaymentWithoutConfirm(order)
        val before = scrape()

        testPg.hasTransactions(pgOrderId, transaction(pgOrderId, PgStatus.SUCCESS))
        assertThat(requestReconcile(order.id)).hasStatusOk()

        assertThat(changeSince(before)).isEqualTo(mapOf("UNKNOWN" to -1.0, "PENDING" to 0.0))
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    private fun transaction(pgOrderId: String, status: PgStatus): PgTransaction {
        return PgTransaction(transactionKey = TRANSACTION_KEY, pgOrderId = pgOrderId, amount = Money(2_000), status = status)
    }

    /** How far each series moved from [before], scraped now. */
    private fun changeSince(before: Map<String, Double>): Map<String, Double> {
        val now = scrape()

        return now.mapValues { (status, value) -> value - before.getValue(status) }
    }

    /** Both series of the gauge in the Prometheus text scraped now, by `status`. A missing series fails the scene. */
    private fun scrape(): Map<String, Double> {
        val lines = managementGet("/actuator/prometheus").body().lines()

        return STATUSES.associateWith { status ->
            val series = "commerce_payment_unresolved{application=\"commerce-api\",status=\"$status\"} "
            val line = checkNotNull(lines.firstOrNull { it.startsWith(series) }) { "The $status series is missing." }

            line.removePrefix(series).toDouble()
        }
    }

    private fun requestConfirm(orderId: Long, idempotencyKey: String = UUID.randomUUID().toString()): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, owner.id)
            .header(IdempotencyKeyHeader.NAME, idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"cardType":"${CARD.type}","cardNo":"${CARD.number}"}""")
            .exchange()
    }

    private fun requestReconcile(orderId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/payment/reconcile").header(UserIdHeader.NAME, owner.id).exchange()
    }
}
