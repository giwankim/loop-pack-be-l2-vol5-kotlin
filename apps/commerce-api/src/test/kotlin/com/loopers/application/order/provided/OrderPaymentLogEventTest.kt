package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.createPgTransactionOf
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import org.wiremock.spring.InjectWireMock

/**
 * The log events [com.loopers.application.order.OrderPaymentSaga] leaves for every PG answer (spec 266, "로그 이벤트"). Each scene
 * reads the captured events' key-value pairs, which the deployed profiles' logstash JSON carries as top-level fields. The `test`
 * console prints none of them, so no scene reads the formatted message.
 */
@EnableTestPg
class OrderPaymentLogEventTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderPaymentReconciler: OrderPaymentReconciler,
    private val paymentFinder: PaymentFinder,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261022:TR:9577c5"
    }

    /** The test PG's server, injected by WireMock's listener as in [OrderPaymentIntegrationTest]. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** Logs from the application's order package, where the saga leaves its events. */
    private val orderApplicationLogs = ListAppender<ILoggingEvent>()

    private val orderApplicationLogger = LoggerFactory.getLogger("com.loopers.application.order") as Logger

    @BeforeEach
    fun captureLogs() {
        orderApplicationLogs.start()
        orderApplicationLogger.addAppender(orderApplicationLogs)
    }

    @AfterEach
    fun releaseLogs() {
        orderApplicationLogger.detachAppender(orderApplicationLogs)
        orderApplicationLogs.stop()
    }

    @Test
    fun `an accepted create leaves pg create accepted at INFO with the transaction it got`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "PENDING",
        )

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        val payment = committedPayment()
        assertThat(event("pg.create.accepted")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(
                identifiers("pg.create.accepted", "confirm", payment) +
                    mapOf("transactionKey" to TRANSACTION_KEY, "pgStatus" to "PENDING"),
            )
        })
    }

    @Test
    fun `a create rejected before creating leaves pg create rejected at INFO without a transaction`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.rejectsBeforeCreate(nextPgOrderId(), amount = 2_000, card = request.toCreditCard())

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        val payment = committedPayment()
        assertThat(event("pg.create.rejected")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(identifiers("pg.create.rejected", "confirm", payment))
        })
    }

    /**
     * The `cause` is the failure kind the PG adapter put in the exception: the root cause's class for a lost response, the HTTP
     * status for an error answer. It tells a lost response from an error response (story 9).
     */
    @ParameterizedTest
    @CsvSource("lost response, NoHttpResponseException", "503, HTTP 503")
    fun `a create whose outcome is unknown leaves pg create outcome unknown at WARN with the failure kind`(
        answer: String,
        cause: String,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        if (answer == "lost response") {
            testPg.losesCreateResponse(pgOrderId, amount = 2_000, card = request.toCreditCard())
        } else {
            testPg.answersCreate(pgOrderId, amount = 2_000, card = request.toCreditCard(), httpStatus = 503)
        }

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        val payment = committedPayment()
        assertThat(event("pg.create.outcome-unknown")).satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(
                identifiers("pg.create.outcome-unknown", "confirm", payment) + mapOf("cause" to cause),
            )
        })
    }

    /** The customer's 대사 is `reconcile`, the PG's 알림 is `callback`. Both look up the same way, so only the trigger differs. */
    @ParameterizedTest
    @CsvSource("reconcile", "callback")
    fun `a lookup that finds the transaction leaves pg lookup found at INFO with the trigger of its path`(trigger: String) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.SUCCESS))

        reconcileThrough(trigger)

        assertThat(event("pg.lookup.found")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(
                identifiers("pg.lookup.found", trigger, payment) +
                    mapOf("transactionKey" to payment.transactionKey!!, "pgStatus" to "SUCCESS"),
            )
        })
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** No transaction is not evidence that none exists (ADR 0022 rule 3), so it is INFO. The payment's known key goes along. */
    @Test
    fun `a lookup that finds no transaction leaves pg lookup none at INFO`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId)

        orderConfirmer.reconcile(user.id, order.id)

        assertThat(event("pg.lookup.none")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(
                identifiers("pg.lookup.none", "reconcile", payment) + mapOf("transactionKey" to payment.transactionKey!!),
            )
        })
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    @Test
    fun `a lookup that finds several transactions leaves pg lookup several at WARN`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(
            payment.pgOrderId,
            createPgTransactionOf(payment, PgStatus.SUCCESS),
            createPgTransactionOf(payment, PgStatus.FAILED),
        )

        orderConfirmer.reconcile(user.id, order.id)

        assertThat(event("pg.lookup.several")).satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(
                identifiers("pg.lookup.several", "reconcile", payment) + mapOf("transactionKey" to payment.transactionKey!!),
            )
        })
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The adapter retries an unavailable lookup once inside the saga's one call (ADR 0030 rule 4), so one reconcile leaves one
     * event. The customer's 대사 still answers the stored state.
     */
    @Test
    fun `an unavailable lookup on the customer's reconcile leaves pg lookup unavailable at WARN with the failure kind`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.answersList(payment.pgOrderId, httpStatus = 503)

        val stored = orderConfirmer.reconcile(user.id, order.id)

        assertThat(stored.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertUnavailableEvent("reconcile", payment)
    }

    /** The PG's 알림 leaves the same event and then throws `PG_UNAVAILABLE` as before (ADR 0031 rule 6). */
    @Test
    fun `an unavailable lookup on the PG's callback leaves pg lookup unavailable at WARN and still throws PG_UNAVAILABLE`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.answersList(payment.pgOrderId, httpStatus = 503)

        val exception = assertThrows<CoreException> { orderPaymentReconciler.reconcile(order.id) }

        assertThat(exception.errorType).isEqualTo(ErrorType.PG_UNAVAILABLE)
        assertUnavailableEvent("callback", payment)
    }

    /**
     * No event holds personal data or secrets (story 16): no key names the user, the 멱등 키, the amount or the card, and no value
     * holds the 멱등 키, the amount or a card value. The user ID is checked by key only: IDs restart at 1 after every scene, so
     * the user's ID can equal the order's. The scene leaves events of every added field: `transactionKey` and `pgStatus` from the
     * create and the found lookup, `cause` from the unavailable lookup. The other four events carry a subset of these fields.
     * The amount is matched as a whole value, so a transaction key that happens to start with its digits is no false alarm.
     */
    @Test
    fun `no event carries the user ID, the idempotency key, the amount or card data`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(pgOrderId, amount = 2_000, card = request.toCreditCard(), transactionKey = TRANSACTION_KEY)
        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        testPg.answersList(pgOrderId, httpStatus = 503)
        orderConfirmer.reconcile(user.id, order.id)
        testPg.hasTransactions(pgOrderId, createPgTransactionOf(committedPayment(), PgStatus.SUCCESS))

        orderPaymentReconciler.reconcile(order.id)

        val events = orderApplicationLogs.list.map { fields(it) }.filter { "event" in it }
        assertThat(events.map { it["event"] }).containsExactly("pg.create.accepted", "pg.lookup.unavailable", "pg.lookup.found")
        assertThat(events.flatMap { it.keys }).noneMatch { key ->
            listOf("user", "idempotency", "amount", "card").any { key.contains(it, ignoreCase = true) }
        }
        assertThat(events.flatMap { it.values }).noneMatch { value ->
            value.contains(IDEMPOTENCY_KEY) ||
                value.contains(request.cardNo) ||
                value == request.cardType ||
                value.matches(Regex("""2000(\.0+)?"""))
        }
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** A replay with the same 멱등 키 answers the stored state without calling the PG (ADR 0024 rule 3), so it leaves no event. */
    @Test
    fun `a replayed confirm with the same idempotency key leaves no event`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(nextPgOrderId(), amount = 2_000, card = request.toCreditCard(), transactionKey = TRANSACTION_KEY)
        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        assertThat(event("pg.create.accepted")).isNotNull()
        orderApplicationLogs.list.clear()

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(testPg.createCount()).isOne()
        assertThat(orderApplicationLogs.list.map { fields(it) }).noneMatch { "event" in it }
    }

    /** The one `pg.lookup.unavailable` event carries `cause` = `HTTP 503`, and every lookup matched a stub. */
    private fun assertUnavailableEvent(trigger: String, payment: Payment) {
        assertThat(event("pg.lookup.unavailable")).satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(fields(it)).containsExactlyInAnyOrderEntriesOf(
                identifiers("pg.lookup.unavailable", trigger, payment) +
                    mapOf("transactionKey" to payment.transactionKey!!, "cause" to "HTTP 503"),
            )
        })
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** The customer's 대사 (`reconcile`) or the PG's 알림 (`callback`), each through its own port. */
    private fun reconcileThrough(trigger: String) {
        when (trigger) {
            "reconcile" -> orderConfirmer.reconcile(user.id, order.id)
            "callback" -> orderPaymentReconciler.reconcile(order.id)
            else -> error("No path for trigger $trigger")
        }
    }

    private fun committedPayment(): Payment {
        return inNewTransaction { paymentFinder.findByOrder(order.id) }
    }

    /** The one captured event whose `event` field is [name]. */
    private fun event(name: String): ILoggingEvent {
        return orderApplicationLogs.list.single { fields(it)["event"] == name }
    }

    /** The event's key-value pairs, values as text. */
    private fun fields(event: ILoggingEvent): Map<String, String> {
        return event.keyValuePairs.orEmpty().associate { it.key to it.value.toString() }
    }

    /** The fields every event carries. */
    private fun identifiers(event: String, trigger: String, payment: Payment): Map<String, String> {
        return mapOf(
            "event" to event,
            "trigger" to trigger,
            "orderId" to payment.orderId.toString(),
            "paymentId" to payment.id.toString(),
            "pgOrderId" to payment.pgOrderId,
        )
    }
}
