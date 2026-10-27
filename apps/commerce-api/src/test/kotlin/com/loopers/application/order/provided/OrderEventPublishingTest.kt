package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.payment.PgTransactionService
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.createPgTransaction
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.util.AopTestUtils
import org.wiremock.spring.InjectWireMock
import java.time.Instant

/**
 * The order slice publishes the first confirmation of an order paid through the PG as one [OrderConfirmedEvent], whichever
 * path confirms it: confirm's own create answer, the customer's reconcile or the PG's callback (ADR 0032 rules 1 and 7).
 * Answers that leave the order unconfirmed, and answers applied again to a finished payment, publish nothing.
 *
 * The scenes run on the real MySQL and the test PG like [OrderPaymentIntegrationTest] and read the events the committing base
 * records. An event is recorded when the step hands it to the port inside TX B, before that transaction ends, so these scenes
 * show where and how often the order slice publishes. They cannot show that a rolled-back TX B's event is dropped: that is
 * the promise of what delivers the event after the commit. Every confirming scene pins the clock, so the event's
 * `confirmedAt` is a literal.
 *
 * The spies are the ones [OrderPaymentIntegrationTest] uses for the same scenes. A callback that beats confirm's TX B runs
 * from the PG-call spy right after the create is answered. Of two racing callbacks, the payment-modifying spy pauses the first
 * while it holds the order lock. The base takes no test transaction, so a direct call to the port runs outside one.
 */
@EnableTestPg
class OrderEventPublishingTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderPaymentReconciler: OrderPaymentReconciler,
    private val orderFinder: OrderFinder,
    private val paymentFinder: PaymentFinder,
    private val orderEventPublisher: OrderEventPublisher,
    private val clock: TestClock,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261027:TR:5a1c0e"

        /** Where every confirming scene pins the clock. Already in microseconds, so the order keeps it as it is. */
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
     * The event carries every line item in ascending product ID order, whichever order the customer listed them in, and the
     * instant the order shows as its confirmation time.
     */
    @Test
    fun `a confirmation whose create answers success publishes one event with the confirmation time and the quantities`() {
        val cheaper = prepareProduct(price = 1_000)
        val pricier = prepareProduct(price = 2_000)
        prepareOrder(pricier to 1, cheaper to 2)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = 4_000, card = request.toCreditCard())
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmedEvents()).containsExactly(
            OrderConfirmedEvent(
                orderId = order.id,
                confirmedAt = CONFIRMED_AT,
                items = listOf(
                    OrderConfirmedEvent.Item(productId = cheaper.id, quantity = 2),
                    OrderConfirmedEvent.Item(productId = pricier.id, quantity = 1),
                ),
            ),
        )
        assertThat(confirmedEvents().single().eventId).isEqualTo("order-confirmed:v1:${order.id}")
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).confirmedAt).isEqualTo(CONFIRMED_AT)
        }
    }

    /**
     * A create accepted as pending publishes nothing. The customer's reconcile that finds the transaction succeeded confirms
     * the order and publishes once. Reconciling again finds the payment finished and answers the stored state without a
     * lookup or a TX B, so it publishes nothing more.
     */
    @Test
    fun `a customer reconcile that first confirms the order publishes once and reconciling again publishes nothing more`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending()
        assertThat(confirmedEvents()).isEmpty()
        testPg.hasTransactions(payment.pgOrderId, transactionOf(payment, PgStatus.SUCCESS))
        clock.pin(CONFIRMED_AT)

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmedEvents()).containsExactly(confirmationOf(quantity = 2))

        orderConfirmer.reconcile(user.id, order.id)

        assertThat(confirmedEvents()).containsExactly(confirmationOf(quantity = 2))
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The PG's callback finds the pending payment's transaction succeeded, confirms the order and publishes once. A duplicate
     * callback finds the payment finished and returns without a lookup or a TX B, so it publishes nothing more.
     */
    @Test
    fun `the PG's callback that first confirms the order publishes once and a duplicate callback publishes nothing more`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending()
        testPg.hasTransactions(payment.pgOrderId, transactionOf(payment, PgStatus.SUCCESS))
        clock.pin(CONFIRMED_AT)

        orderPaymentReconciler.reconcile(order.id)

        assertThat(confirmedEvents()).containsExactly(confirmationOf(quantity = 2))

        orderPaymentReconciler.reconcile(order.id)

        assertThat(confirmedEvents()).containsExactly(confirmationOf(quantity = 2))
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * A duplicate callback can also arrive while the first one's TX B still holds the order. It waits on the order lock, then
     * reads the finished payment, so its TX B gets `Unchanged` and publishes nothing more. Unlike a duplicate after the payment
     * finished, which returns before any TX B, this one reaches the step that publishes (ADR 0024 rule 5).
     */
    @Test
    fun `a duplicate callback racing the first one publishes nothing more`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending()
        testPg.hasTransactions(payment.pgOrderId, transactionOf(payment, PgStatus.SUCCESS))
        pauseTheFirstTxBHoldingTheOrder()
        clock.pin(CONFIRMED_AT)

        val first = inAnotherThread { orderPaymentReconciler.reconcile(order.id) }
        pause.awaitHeld()
        val duplicate = inAnotherThread { orderPaymentReconciler.reconcile(order.id) }

        assertThat(duplicate.finishesWithin(LOCK_WAIT_PROBE)).isFalse()
        pause.release()
        first.await()
        duplicate.await()
        assertThat(confirmedEvents()).containsExactly(confirmationOf(quantity = 2))
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The callback arrives after the PG accepted confirm's create but before confirm's TX B applies the answer, so the callback
     * confirms the order and publishes. The create's answer says success too, but it reaches a finished payment, is
     * `Unchanged` and publishes nothing more.
     */
    @Test
    fun `a create answer that arrives after a callback confirmed the order publishes nothing more`() {
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
        testPg.hasTransactions(
            pgOrderId,
            PgTransaction(
                transactionKey = TRANSACTION_KEY,
                pgOrderId = pgOrderId,
                amount = Money(2_000),
                status = PgStatus.SUCCESS,
            ),
        )
        var eventsAfterCallback: List<OrderConfirmedEvent>? = null
        afterTheCreateIsAnswered {
            orderPaymentReconciler.reconcile(order.id)
            eventsAfterCallback = confirmedEvents()
        }
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(eventsAfterCallback).containsExactly(confirmationOf(quantity = 2))
        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmedEvents()).containsExactly(confirmationOf(quantity = 2))
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * Confirm's create answer leaves the order unconfirmed, so nothing is published: a create accepted as pending, a lost
     * create response, an accepted create whose PG transaction failed, a rejection before creating, and a rejection that
     * echoes another amount and so conflicts with the payment.
     */
    @ParameterizedTest
    @CsvSource(
        "pending, PAYMENT_PENDING, PENDING",
        "lost, PAYMENT_PENDING, UNKNOWN",
        "failed, PAYMENT_FAILED, FAILED",
        "rejected, PAYMENT_FAILED, FAILED",
        "conflicting, PAYMENT_PENDING, UNKNOWN",
    )
    fun `a create answer that leaves the order unconfirmed publishes nothing`(
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
        assertThat(confirmedEvents()).isEmpty()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** With no transaction to deliver after, the port refuses the call before anything is published (ADR 0032 rule 3). */
    @Test
    fun `publishing an order confirmed event outside a transaction is refused`() {
        val event = OrderConfirmedEvent(
            orderId = 1,
            confirmedAt = CONFIRMED_AT,
            items = listOf(OrderConfirmedEvent.Item(productId = 1, quantity = 1)),
        )

        assertThrows<IllegalStateException> { orderEventPublisher.publish(event) }

        assertThat(confirmedEvents()).isEmpty()
    }

    private fun confirmedEvents(): List<OrderConfirmedEvent> {
        return applicationEvents.stream(OrderConfirmedEvent::class.java).toList()
    }

    /** The event of the one order a scene prepared, confirmed at [CONFIRMED_AT] with [quantity] units of its one product. */
    private fun confirmationOf(quantity: Int): OrderConfirmedEvent {
        return OrderConfirmedEvent(
            orderId = order.id,
            confirmedAt = CONFIRMED_AT,
            items = listOf(OrderConfirmedEvent.Item(productId = product.id, quantity = quantity)),
        )
    }

    /** Confirms the prepared order with a create accepted as pending and returns its payment, read in a new transaction. */
    private fun confirmPending(): Payment {
        confirmOrder(testPg, pgStatus = "PENDING")

        return inNewTransaction { paymentFinder.findByOrder(order.id) }
    }

    /** The PG transaction that matches [payment]: its PG order ID, amount and transaction key, with PG status [status]. */
    private fun transactionOf(payment: Payment, status: PgStatus): PgTransaction {
        return createPgTransaction(payment, transactionKey = payment.transactionKey!!, status = status)
    }

    /**
     * Stops the first TX B that applies a PG transaction to the payment just before it does, while it holds the order. Later
     * calls are not stopped, so another TX B applying the same answer waits on the order lock. The Service is a CGLIB proxy,
     * so the stub goes on the spy it wraps.
     */
    private fun pauseTheFirstTxBHoldingTheOrder() {
        val target = AopTestUtils.getUltimateTargetObject<PaymentModifyService>(paymentModifyService)
        every { target.reconcile(any(), any()) } answers {
            pause.hold()
            callOriginal()
        } andThenAnswer { callOriginal() }
    }

    /**
     * Runs [action] right after confirm's create is answered, before the answer goes to TX B. The PG call is outside any
     * transaction, so a `NEVER` port such as the PG's reconcile is allowed. The Service is a CGLIB proxy, so the stub goes on
     * the spy it wraps.
     */
    private fun afterTheCreateIsAnswered(action: () -> Unit) {
        val target = AopTestUtils.getUltimateTargetObject<PgTransactionService>(pgTransactionService)
        every { target.create(any(), any()) } answers {
            val transaction = callOriginal()
            action()
            transaction
        }
    }
}
