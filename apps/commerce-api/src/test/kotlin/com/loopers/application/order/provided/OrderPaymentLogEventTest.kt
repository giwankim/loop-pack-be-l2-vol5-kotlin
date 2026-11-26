package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.order.OrderPaymentSagaSteps
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.createPgTransaction
import com.loopers.domain.payment.createPgTransactionOf
import com.loopers.support.EnableTestPg
import com.loopers.support.LogEvent
import com.loopers.support.TestPg
import com.loopers.support.failRejectionTxBOnceAfterItsWrites
import com.loopers.support.failTxBOnceAfterItsWrites
import com.loopers.support.logEvents
import com.loopers.support.logEventsOf
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.wiremock.spring.InjectWireMock

/**
 * The log events [com.loopers.application.order.OrderPaymentSaga] leaves for every PG answer (spec 266, "로그 이벤트"). Each scene
 * reads the captured events' key-value pairs, which the deployed profiles' logstash JSON carries as top-level fields. The `test`
 * console prints none of them, so no scene reads the formatted message. Each scene has one order and reads that order's event
 * sequence through `com.loopers.support`'s [logEventsOf], the reader the incident test shares (spec 274, story 22). The two scenes
 * that look at every event (no personal data, no event on a replay) read [logEvents].
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

    /** Where a scene makes TX B throw after its writes ([failTxBOnceAfterItsWrites]). Unstubbed, it is the real TX B. */
    @MockkSpyBean
    private lateinit var orderPaymentSagaSteps: OrderPaymentSagaSteps

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
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
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
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(identifiers("pg.create.rejected", "confirm", payment))
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
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
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
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
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
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
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
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
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
     * create and the found lookup, `cause` from the unavailable lookup, `result`, `from` and `to` from the committed TX B, and the
     * failed TX B of the callback. The other events carry a subset of these fields.
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
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)

        assertThrows<DataAccessResourceFailureException> { orderPaymentReconciler.reconcile(order.id) }

        val events = orderApplicationLogs.logEvents().map { it.fields }
        assertThat(events.map { it["event"] }).containsExactly(
            "pg.create.accepted",
            "payment.apply.committed",
            "pg.lookup.unavailable",
            "pg.lookup.found",
            "payment.apply.failed",
        )
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
        assertThat(orderApplicationLogs.logEvents()).isEmpty()
    }

    /**
     * A create answered with a finished or pending transaction changes the payment from 결과 미확인, and TX B's commit leaves
     * `payment.apply.committed` at INFO after the create's event, with the key of the transaction it applied.
     */
    @ParameterizedTest
    @ValueSource(strings = ["SUCCESS", "FAILED", "PENDING"])
    fun `a TX B that changes the payment leaves payment apply committed at INFO with the statuses it moved between`(
        status: String,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = status,
        )

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        val payment = committedPayment()
        assertThat(eventNames()).containsExactly("pg.create.accepted", "payment.apply.committed")
        assertThat(event("payment.apply.committed")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
                identifiers("payment.apply.committed", "confirm", payment) +
                    mapOf("transactionKey" to TRANSACTION_KEY, "result" to "changed", "from" to "UNKNOWN", "to" to status),
            )
        })
    }

    /** A 생성 전 거절 has no transaction, so its TX B's event has no transaction key. */
    @Test
    fun `a create rejected before creating leaves payment apply committed after pg create rejected, changed to failed`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.rejectsBeforeCreate(nextPgOrderId(), amount = 2_000, card = request.toCreditCard())

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        val payment = committedPayment()
        assertThat(eventNames()).containsExactly("pg.create.rejected", "payment.apply.committed")
        assertThat(event("payment.apply.committed")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
                identifiers("payment.apply.committed", "confirm", payment) +
                    mapOf("result" to "changed", "from" to "UNKNOWN", "to" to "FAILED"),
            )
        })
    }

    /** A lookup that finds the pending transaction again changes nothing: the event says so, with no statuses. */
    @Test
    fun `a TX B that leaves the payment as it was leaves payment apply committed at INFO as unchanged`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.PENDING))
        orderApplicationLogs.list.clear()

        orderConfirmer.reconcile(user.id, order.id)

        assertThat(eventNames()).containsExactly("pg.lookup.found", "payment.apply.committed")
        assertThat(event("payment.apply.committed")).satisfies({
            assertThat(it.level).isEqualTo(Level.INFO)
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
                identifiers("payment.apply.committed", "reconcile", payment) +
                    mapOf("transactionKey" to payment.transactionKey!!, "result" to "unchanged"),
            )
        })
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** A transaction under another key than the payment's conflicts (ADR 0021 rule 1): TX B commits nothing and warns. */
    @Test
    fun `a TX B whose transaction conflicts with the payment leaves payment apply committed at WARN as conflicted`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        val otherKey = "20261126:TR:0ther1"
        testPg.hasTransactions(
            payment.pgOrderId,
            createPgTransaction(payment, transactionKey = otherKey, status = PgStatus.SUCCESS),
        )
        orderApplicationLogs.list.clear()

        orderPaymentReconciler.reconcile(order.id)

        assertThat(eventNames()).containsExactly("pg.lookup.found", "payment.apply.committed")
        assertThat(event("payment.apply.committed")).satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
                identifiers("payment.apply.committed", "callback", payment) +
                    mapOf("transactionKey" to otherKey, "result" to "conflicted"),
            )
        })
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * A TX B that rolls back after the PG accepted the create leaves `payment.apply.failed` at WARN with the order's identifiers and
     * the exception, and the confirm still throws that exception. The payment is still 결과 미확인 with no transaction key. This is
     * the failed-apply sequence of hypothesis 3: the PG's answer arrived, and its apply did not commit.
     */
    @Test
    fun `a TX B that fails after an accepted create leaves payment apply failed at WARN and rethrows the same exception`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.acceptsCreate(
            nextPgOrderId(),
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "SUCCESS",
        )
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)

        val exception = assertThrows<DataAccessResourceFailureException> {
            orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)
        }

        val payment = committedPayment()
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
        assertThat(eventNames()).containsExactly("pg.create.accepted", "payment.apply.failed")
        assertThat(event("payment.apply.failed")).satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
                identifiers("payment.apply.failed", "confirm", payment) + mapOf("transactionKey" to TRANSACTION_KEY),
            )
            assertThat(it.throwable).isSameAs(exception)
        })
    }

    /** The 생성 전 거절's TX B entry leaves the same event when it rolls back, without a transaction key: there is no transaction. */
    @Test
    fun `a TX B that fails after a create rejected before creating leaves payment apply failed after pg create rejected`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.rejectsBeforeCreate(nextPgOrderId(), amount = 2_000, card = request.toCreditCard())
        orderPaymentSagaSteps.failRejectionTxBOnceAfterItsWrites(entityManager)

        assertThrows<DataAccessResourceFailureException> { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) }

        val payment = committedPayment()
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(eventNames()).containsExactly("pg.create.rejected", "payment.apply.failed")
        assertThat(event("payment.apply.failed").fields).containsExactlyInAnyOrderEntriesOf(
            identifiers("payment.apply.failed", "confirm", payment),
        )
    }

    /** The same sequence from a reconcile: the lookup found the transaction, and the TX B that applied it rolled back. */
    @Test
    fun `a TX B that fails after a found lookup leaves payment apply failed after pg lookup found`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val pending = confirmPending(testPg)
        testPg.hasTransactions(pending.pgOrderId, createPgTransactionOf(pending, PgStatus.SUCCESS))
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)
        orderApplicationLogs.list.clear()

        assertThrows<DataAccessResourceFailureException> { orderConfirmer.reconcile(user.id, order.id) }

        assertThat(committedPayment().status).isEqualTo(PaymentStatus.PENDING)
        assertThat(eventNames()).containsExactly("pg.lookup.found", "payment.apply.failed")
        assertThat(event("payment.apply.failed").fields).containsExactlyInAnyOrderEntriesOf(
            identifiers("payment.apply.failed", "reconcile", pending) + mapOf("transactionKey" to pending.transactionKey!!),
        )
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The lost-response sequence of hypothesis 2: a create whose answer was lost goes to no TX B, so no `payment.apply.*` event
     * follows `pg.create.outcome-unknown`. In the database it looks like the failed apply, a 결과 미확인 payment, so only these
     * events tell the two apart.
     */
    @Test
    fun `a create whose response is lost leaves no payment apply event after pg create outcome unknown`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        testPg.losesCreateResponse(nextPgOrderId(), amount = 2_000, card = request.toCreditCard())

        orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(committedPayment().status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(eventNames()).containsExactly("pg.create.outcome-unknown")
    }

    /** The one `pg.lookup.unavailable` event carries `cause` = `HTTP 503`, and every lookup matched a stub. */
    private fun assertUnavailableEvent(trigger: String, payment: Payment) {
        assertThat(event("pg.lookup.unavailable")).satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.fields).containsExactlyInAnyOrderEntriesOf(
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

    /** The one event of the scene's order whose name is [name]. */
    private fun event(name: String): LogEvent {
        return orderEvents().single { it.name == name }
    }

    /** The names of the scene's order's events, in the order they were logged. */
    private fun eventNames(): List<String> {
        return orderEvents().map { it.name }
    }

    /**
     * The scene's order's event sequence. Every scene has one order, so it must be every captured event: an event of another
     * order or one without an `orderId` fails the scene, as it did when the scenes read all events.
     */
    private fun orderEvents(): List<LogEvent> {
        val events = orderApplicationLogs.logEventsOf(order.id)
        val all = orderApplicationLogs.logEvents()
        check(events == all) { "An event outside order ${order.id}: $all" }

        return events
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
