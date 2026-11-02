package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderConfirmedMessage
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.payment.PgTransactionService
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.createPgTransaction
import com.loopers.domain.payment.createPgTransactionOf
import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.afterTheCreateIsAnswered
import com.loopers.support.pauseTheFirstTxBHoldingTheOrder
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.wiremock.spring.InjectWireMock
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The order slice leaves the first confirmation of an order paid through the PG in one outbox row, in the transaction that
 * confirms the order (ADR 0034 rules 1 and 8), whichever path confirms it: confirm's own create answer, the customer's
 * reconcile or the PG's callback. Answers that leave the order unconfirmed and answers applied again to a finished payment
 * leave no row (order-outbox design 3). The scenes call the port on the real MySQL with the test PG, and read the rows in a new
 * transaction, so only what committed answers. Nothing here reads the broker, and a row's absence needs no wait: TX B has
 * committed or rolled back by the time the port returns. A scene that asserts a row's body pins the clock, so its instants are
 * literals.
 *
 * The two spies are the ones [OrderPaymentIntegrationTest] uses for the same scenes. A callback that beats confirm's TX B runs
 * from the PG-call spy right after the create is answered. Of two racing callbacks, the payment-modifying spy pauses the first
 * while it holds the order lock.
 */
@EnableTestPg
class OrderEventOutboxTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderPaymentReconciler: OrderPaymentReconciler,
    private val orderFinder: OrderFinder,
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val jsonMapper: JsonMapper,
    private val clock: TestClock,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261027:TR:5a1c0e"

        /** Where a scene pins the clock when the form of the instant is not its subject. Already in microseconds. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
    }

    /** Where a callback runs after confirm's create is answered ([afterTheCreateIsAnswered]). Unstubbed, it is the real PG call. */
    @MockkSpyBean
    private lateinit var pgTransactionService: PgTransactionService

    /** Where the first of two racing callbacks stops while it holds the order ([pauseTheFirstTxBHoldingTheOrder]). */
    @MockkSpyBean
    private lateinit var paymentModifyService: PaymentModifyService

    private val pause = Pause()

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /**
     * The event columns are the facts of order-outbox design 2. The payload is compared as a whole string, because the field
     * order is part of what the row keeps: the body of the wire contract, its items in ascending product ID order whichever
     * order the customer listed them in, and no envelope.
     */
    @Test
    fun `a confirmation whose create answers success leaves one row holding the event's columns and body`() {
        val cheaper = prepareProduct(price = 1_000)
        val pricier = prepareProduct(price = 2_000)

        val row = confirmLeavingARow(CONFIRMED_AT, pricier to 1, cheaper to 2)

        assertThat(row.eventId).isEqualTo("order-confirmed:v1:${order.id}")
        assertThat(row.orderId).isEqualTo(order.id)
        assertThat(row.eventType).isEqualTo("OrderConfirmed")
        assertThat(row.schemaVersion).isEqualTo(1)
        assertThat(row.messageKey).isEqualTo(order.id.toString())
        assertThat(row.payload).isEqualTo(
            """{"orderId":${order.id},"confirmedAt":"2026-10-27T01:02:03.123456Z",""" +
                """"items":[{"productId":${cheaper.id},"quantity":2},{"productId":${pricier.id},"quantity":1}]}""",
        )
    }

    /** The first state of every row (ADR 0034 rule 8): nothing has tried to publish it, and it is due at the step's clock. */
    @Test
    fun `the row starts pending with no attempts, due at the instant the clock is pinned to`() {
        val row = confirmLeavingARow(CONFIRMED_AT, prepareProduct(price = 1_000) to 2)

        assertThat(row.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(row.attemptCount).isZero()
        assertThat(row.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        assertThat(row.publishedAt).isNull()
        assertThat(row.lastError).isNull()
    }

    /**
     * ISO-8601 drops trailing zeros by threes, so a confirmation time has these three forms on the wire. The stored string must
     * read back as the same value in each, since step 2 rebuilds the message from it (ADR 0034 rule 9).
     */
    @ParameterizedTest
    @ValueSource(strings = ["2026-10-27T01:02:03.123456Z", "2026-10-27T01:02:03.100Z", "2026-10-27T01:02:03Z"])
    fun `the stored payload reads back as the body it was written from`(confirmedAt: String) {
        val bought = prepareProduct(price = 1_000)

        val row = confirmLeavingARow(Instant.parse(confirmedAt), bought to 2)

        assertThat(row.payload).isEqualTo(
            """{"orderId":${order.id},"confirmedAt":"$confirmedAt","items":[{"productId":${bought.id},"quantity":2}]}""",
        )
        assertThat(jsonMapper.readValue(row.payload, OrderConfirmedMessage.Payload::class.java)).isEqualTo(
            OrderConfirmedMessage.Payload(
                orderId = order.id,
                confirmedAt = Instant.parse(confirmedAt),
                items = listOf(OrderConfirmedMessage.Payload.Item(productId = bought.id, quantity = 2)),
            ),
        )
    }

    /**
     * A create accepted as pending leaves no row. The customer's reconcile that finds the transaction succeeded confirms the
     * order, and that is the one row. Reconciling again finds the payment finished and answers the stored state without a lookup
     * or a TX B, so no second row can follow.
     */
    @Test
    fun `a customer reconcile that first confirms the order leaves one row and reconciling again leaves no other`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending(testPg)
        assertNoRow()
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.SUCCESS))
        clock.pin(CONFIRMED_AT)

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertOnlyTheConfirmation(quantity = 2)

        orderConfirmer.reconcile(user.id, order.id)

        assertOnlyTheConfirmation(quantity = 2)
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The PG's callback finds the pending payment's transaction succeeded and confirms the order, which leaves one row. A
     * duplicate callback finds the payment finished and returns without a lookup or a TX B, so no second row can follow.
     */
    @Test
    fun `the PG's callback that first confirms the order leaves one row and a duplicate callback leaves no other`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.SUCCESS))
        clock.pin(CONFIRMED_AT)

        orderPaymentReconciler.reconcile(order.id)

        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
        }
        assertOnlyTheConfirmation(quantity = 2)

        orderPaymentReconciler.reconcile(order.id)

        assertOnlyTheConfirmation(quantity = 2)
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * A duplicate callback can also arrive while the first one's TX B still holds the order. It waits on the order lock, then
     * reads the finished payment, so its TX B gets `Unchanged` and leaves nothing. Unlike a duplicate after the payment finished,
     * which returns before any TX B, this one reaches the step that writes the row (ADR 0024 rule 5).
     */
    @Test
    fun `a duplicate callback racing the first one leaves one row`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.SUCCESS))
        paymentModifyService.pauseTheFirstTxBHoldingTheOrder(pause)
        clock.pin(CONFIRMED_AT)

        val first = inAnotherThread { orderPaymentReconciler.reconcile(order.id) }
        pause.awaitHeld()
        val duplicate = inAnotherThread { orderPaymentReconciler.reconcile(order.id) }

        assertThat(duplicate.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        first.await()
        duplicate.await()
        assertOnlyTheConfirmation(quantity = 2)
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The callback arrives after the PG accepted confirm's create but before confirm's TX B applies the answer, so the callback
     * confirms the order and leaves the row. The create's answer says success too, but it reaches a finished payment, is
     * `Unchanged` and leaves nothing more.
     */
    @Test
    fun `a create answer that arrives after a callback confirmed the order leaves one row`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        val pgOrderId = nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = pgOrderId,
            amount = 2_000,
            card = request.toCreditCard(),
            transactionKey = TRANSACTION_KEY,
            pgStatus = "SUCCESS",
        )
        testPg.hasTransactions(pgOrderId, succeededTransaction(pgOrderId, amount = 2_000))
        var rowsAfterCallback = 0L
        pgTransactionService.afterTheCreateIsAnswered {
            orderPaymentReconciler.reconcile(order.id)
            rowsAfterCallback = inNewTransaction { orderEventOutboxRepository.count() }
        }
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(rowsAfterCallback).isOne()
        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertOnlyTheConfirmation(quantity = 2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * Confirm's create answer leaves the order unconfirmed, so no row exists: a create accepted as pending, a lost create
     * response, an accepted create whose PG transaction failed, a rejection before creating, and a rejection that echoes another
     * amount and so conflicts with the payment.
     */
    @ParameterizedTest
    @CsvSource(
        "pending, PAYMENT_PENDING, PENDING",
        "lost, PAYMENT_PENDING, UNKNOWN",
        "failed, PAYMENT_FAILED, FAILED",
        "rejected, PAYMENT_FAILED, FAILED",
        "conflicting, PAYMENT_PENDING, UNKNOWN",
    )
    fun `a create answer that leaves the order unconfirmed leaves no row`(
        answer: String,
        orderStatus: OrderStatus,
        paymentStatus: PaymentStatus,
    ) {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val request = createOrderConfirmRequest()
        val card = request.toCreditCard()
        val pgOrderId = nextPgOrderId()
        val otherAmountRejection =
            """{"code":"${TestPg.REJECTED_BEFORE_CREATE}","orderId":"$pgOrderId","amount":1000,"transactionCreated":false}"""
        when (answer) {
            "pending" -> testPg.acceptsCreate(pgOrderId, amount = 2_000, card = card, transactionKey = TRANSACTION_KEY)

            "lost" -> testPg.losesCreateResponse(pgOrderId, amount = 2_000, card = card)

            "failed" -> testPg.acceptsCreate(
                pgOrderId,
                amount = 2_000,
                card = card,
                transactionKey = TRANSACTION_KEY,
                pgStatus = "FAILED",
            )

            "rejected" -> testPg.rejectsBeforeCreate(pgOrderId, amount = 2_000, card = card)

            "conflicting" -> testPg.answersCreate(
                pgOrderId,
                amount = 2_000,
                card = card,
                httpStatus = 422,
                body = otherAmountRejection,
            )
        }

        val answered = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(answered.order.status).isEqualTo(orderStatus)
        assertThat(answered.paymentStatus).isEqualTo(paymentStatus)
        assertNoRow()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The one conflict that says success: a lookup finds a successful PG transaction for another amount. `Conflicted` changes
     * nothing, so the order stays payment pending, and a row must not follow the PG's word alone. The create scenes' conflict is
     * a rejection, which could never have written a row.
     */
    @Test
    fun `a reconcile that finds a successful transaction for another amount conflicts and leaves no row`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000)), quantity = 2)
        val payment = confirmPending(testPg)
        val otherAmount = createPgTransaction(
            payment,
            transactionKey = payment.transactionKey!!,
            amount = Money(1_000),
            status = PgStatus.SUCCESS,
        )
        testPg.hasTransactions(payment.pgOrderId, otherAmount)

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.PENDING)
        assertNoRow()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** Each confirmed order is its own row: under its own event ID and message key. */
    @Test
    fun `two confirmed orders leave two rows with different event IDs and message keys`() {
        val first = prepareOrder()
        val second = prepareOrder()

        confirmOrder(testPg, first)
        confirmOrder(testPg, second)

        inNewTransaction {
            assertThat(orderEventOutboxRepository.count()).isEqualTo(2)
            val firstRow = orderEventOutboxRepository.findByEventId("order-confirmed:v1:${first.id}")!!
            val secondRow = orderEventOutboxRepository.findByEventId("order-confirmed:v1:${second.id}")!!
            assertThat(firstRow.orderId).isEqualTo(first.id)
            assertThat(firstRow.messageKey).isEqualTo(first.id.toString())
            assertThat(secondRow.orderId).isEqualTo(second.id)
            assertThat(secondRow.messageKey).isEqualTo(second.id.toString())
            assertThat(firstRow.eventId).isNotEqualTo(secondRow.eventId)
            assertThat(firstRow.messageKey).isNotEqualTo(secondRow.messageKey)
        }
    }

    /**
     * The table holds one row: the confirmation of the one order a scene prepared, at [CONFIRMED_AT] with [quantity] units of its
     * one product. Comparing the body as well shows the row is that confirmation's, not only that some row exists.
     */
    private fun assertOnlyTheConfirmation(quantity: Int) {
        inNewTransaction {
            assertThat(orderEventOutboxRepository.count()).isOne()
            val row = orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}")!!
            assertThat(row.messageKey).isEqualTo(order.id.toString())
            assertThat(row.payload).isEqualTo(
                """{"orderId":${order.id},"confirmedAt":"2026-10-27T01:02:03.123456Z",""" +
                    """"items":[{"productId":${product.id},"quantity":$quantity}]}""",
            )
        }
    }

    private fun assertNoRow() {
        assertThat(inNewTransaction { orderEventOutboxRepository.count() }).isZero()
    }

    /** The successful PG transaction a lookup by [pgOrderId] finds after the scene's create stub accepted [amount]. */
    private fun succeededTransaction(pgOrderId: String, amount: Long): PgTransaction {
        return PgTransaction(
            transactionKey = TRANSACTION_KEY,
            pgOrderId = pgOrderId,
            amount = Money(amount),
            status = PgStatus.SUCCESS,
        )
    }

    /** Orders [items] and confirms the order at [confirmedAt] with a create the PG answers with success, and reads the only row. */
    private fun confirmLeavingARow(confirmedAt: Instant, vararg items: Pair<Product, Int>): OrderEventOutbox {
        prepareOrder(*items)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = order.totalAmount.amount.longValueExact(), card = request.toCreditCard())
        clock.pin(confirmedAt)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)

        return inNewTransaction {
            assertThat(orderEventOutboxRepository.count()).isOne()

            orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}")!!
        }
    }
}
