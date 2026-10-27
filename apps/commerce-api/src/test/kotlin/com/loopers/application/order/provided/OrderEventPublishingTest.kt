package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.OrderPaymentSagaSteps
import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.payment.PgTransactionService
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.payment.createPgTransactionOf
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.afterTheCreateIsAnswered
import com.loopers.support.awaitLinesAt
import com.loopers.support.failTxBOnceAfterItsWrites
import com.loopers.support.pauseTheFirstTxBHoldingTheOrder
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ThrowingConsumer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.json.JsonContent
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The order slice publishes the first confirmation of an order paid through the PG as one record on the order events topic,
 * whichever path confirms it: confirm's own create answer, the customer's reconcile or the PG's callback (ADR 0032 rules 1
 * and 7). Answers that leave the order unconfirmed, answers applied again to a finished payment and a TX B that rolls back
 * publish nothing.
 *
 * The scenes run on the real MySQL, the test PG and the Kafka test container, and read what reached the broker. The relay
 * publishes after the commit on a thread of its own, so a scene waits a bounded time for the records it expects and then
 * reads on for a quiet period to see that nothing else follows ([TopicReader.publishedRecords]). The class names a topic of
 * its own, since the broker is one per JVM, and each scene reads it from where it ended before the scene ([TopicReader]).
 * Every scene that asserts an envelope pins the clock, so its `confirmedAt` is a literal.
 *
 * The first two spies are the ones [OrderPaymentIntegrationTest] uses for the same scenes. A callback that beats confirm's
 * TX B runs from the PG-call spy right after the create is answered. Of two racing callbacks, the payment-modifying spy
 * pauses the first while it holds the order lock. The spy on the TX B bean fails one TX B after its writes, as that class's
 * scene 7 does, and the template's spy is where a scene sees which thread publishes. The base takes no test transaction, so
 * a direct call to the port runs outside one.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=order-events-publishing"])
class OrderEventPublishingTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderPaymentReconciler: OrderPaymentReconciler,
    private val orderFinder: OrderFinder,
    private val orderEventPublisher: OrderEventPublisher,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val IDEMPOTENCY_KEY = "confirm-key-1"
        private const val TRANSACTION_KEY = "20261027:TR:5a1c0e"

        /** Where every confirming scene pins the clock. Already in microseconds, so the order keeps it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")

        /** How long a scene waits for what the relay does on its own thread: the INFO line, or the thread that publishes. */
        private val RELAY_WAIT: Duration = Duration.ofSeconds(10)
    }

    /** Where a callback runs after confirm's create is answered ([afterTheCreateIsAnswered]). Unstubbed, it is the real PG call. */
    @MockkSpyBean
    private lateinit var pgTransactionService: PgTransactionService

    /** Where the first of two racing callbacks stops while it holds the order ([pauseTheFirstTxBHoldingTheOrder]). */
    @MockkSpyBean
    private lateinit var paymentModifyService: PaymentModifyService

    /** Where a TX B fails after its writes ([failTxBOnceAfterItsWrites]). Unstubbed, it is the real TX B. */
    @MockkSpyBean
    private lateinit var orderPaymentSagaSteps: OrderPaymentSagaSteps

    /** Where a scene sees the thread that publishes ([theThreadThatPublishes]). Unstubbed, it is the real template. */
    @MockkSpyBean
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    private val pause = Pause()

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** Reads the class's topic from where it ended before the scene, so the scene sees only what it published. */
    private lateinit var orderEvents: TopicReader

    /**
     * Logs from the Kafka adapter, where the relay logs each publish. The INFO line is logged on the producer's thread when
     * the broker acknowledges the record, and that acknowledgement can reach the producer after the record reached the
     * consumer. So the line may trail the record, and a scene waits for it ([awaitLinesAt]).
     */
    private val kafkaAdapterLogs = ListAppender<ILoggingEvent>()

    private val kafkaAdapterLogger = LoggerFactory.getLogger("com.loopers.adapter.integration.kafka") as Logger

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @BeforeEach
    fun captureKafkaAdapterLogs() {
        kafkaAdapterLogs.start()
        kafkaAdapterLogger.addAppender(kafkaAdapterLogs)
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @AfterEach
    fun releaseKafkaAdapterLogs() {
        kafkaAdapterLogger.detachAppender(kafkaAdapterLogs)
    }

    /**
     * The record carries the envelope of design section 2: every line item in ascending product ID order, whichever order the
     * customer listed them in, and the instant the order shows as its confirmation time as an ISO-8601 UTC string. The
     * confirmation answers as it did before the relay existed, and the record follows it.
     */
    @Test
    fun `a confirmation whose create answers success publishes one record holding the envelope under the order's key`() {
        val cheaper = prepareProduct(price = 1_000)
        val pricier = prepareProduct(price = 2_000)
        prepareOrder(pricier to 1, cheaper to 2)
        val request = createOrderConfirmRequest()
        acceptNextCreate(testPg, amount = 4_000, card = request.toCreditCard())
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(confirmed.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        val records = orderEvents.publishedRecords(expected = 1)
        assertThat(records).hasSize(1)
        assertThat(records.single().key()).isEqualTo(order.id.toString())
        assertThat(records.single().headers().toArray()).isEmpty()
        assertThat(JsonContent(records.single().value())).isStrictlyEqualTo(
            """
            {
              "eventId": "order-confirmed:v1:${order.id}",
              "eventType": "OrderConfirmed",
              "schemaVersion": 1,
              "payload": {
                "orderId": ${order.id},
                "confirmedAt": "2026-10-27T01:02:03.123456Z",
                "items": [
                  { "productId": ${cheaper.id}, "quantity": 2 },
                  { "productId": ${pricier.id}, "quantity": 1 }
                ]
              }
            }
            """,
        )
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).confirmedAt).isEqualTo(CONFIRMED_AT)
        }
    }

    /**
     * A create accepted as pending publishes nothing. The customer's reconcile that finds the transaction succeeded confirms
     * the order, and that is the one record. Reconciling again finds the payment finished and answers the stored state without
     * a lookup or a TX B, so no second record follows.
     */
    @Test
    fun `a customer reconcile that first confirms the order publishes one record and reconciling again publishes no other`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.SUCCESS))
        clock.pin(CONFIRMED_AT)

        val reconciled = orderConfirmer.reconcile(user.id, order.id)
        orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertOnlyTheConfirmation(orderEvents.publishedRecords(expected = 1), quantity = 2)
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The PG's callback finds the pending payment's transaction succeeded and confirms the order, which is published once. A
     * duplicate callback finds the payment finished and returns without a lookup or a TX B, so no second record follows.
     */
    @Test
    fun `the PG's callback that first confirms the order publishes one record and a duplicate callback publishes no other`() {
        prepareOrder(products = listOf(prepareProduct()), quantity = 2)
        val payment = confirmPending(testPg)
        testPg.hasTransactions(payment.pgOrderId, createPgTransactionOf(payment, PgStatus.SUCCESS))
        clock.pin(CONFIRMED_AT)

        orderPaymentReconciler.reconcile(order.id)
        orderPaymentReconciler.reconcile(order.id)

        assertOnlyTheConfirmation(orderEvents.publishedRecords(expected = 1), quantity = 2)
        inNewTransaction {
            assertThat(orderFinder.find(user.id, order.id).status).isEqualTo(OrderStatus.CONFIRMED)
        }
        assertThat(testPg.lookupCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * A duplicate callback can also arrive while the first one's TX B still holds the order. It waits on the order lock, then
     * reads the finished payment, so its TX B gets `Unchanged` and publishes nothing. Unlike a duplicate after the payment
     * finished, which returns before any TX B, this one reaches the step that publishes (ADR 0024 rule 5).
     */
    @Test
    fun `a duplicate callback racing the first one publishes no second record`() {
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
        assertOnlyTheConfirmation(orderEvents.publishedRecords(expected = 1), quantity = 2)
        assertThat(testPg.lookupCount()).isEqualTo(2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The callback arrives after the PG accepted confirm's create but before confirm's TX B applies the answer, so the callback
     * confirms the order, and that confirmation is published. The create's answer says success too, but it reaches a finished
     * payment, is `Unchanged` and publishes nothing more.
     */
    @Test
    fun `a create answer that arrives after a callback confirmed the order publishes no second record`() {
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
        var recordsAfterCallback: List<ConsumerRecord<String, String>> = emptyList()
        pgTransactionService.afterTheCreateIsAnswered {
            orderPaymentReconciler.reconcile(order.id)
            recordsAfterCallback = orderEvents.publishedRecords(expected = 1)
        }
        clock.pin(CONFIRMED_AT)

        val confirmed = orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request)

        assertOnlyTheConfirmation(recordsAfterCallback, quantity = 2)
        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()
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
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /**
     * The PG said success, but the TX B that applies it rolls back after its writes. The step had already published the event
     * inside that TX B, and the relay never runs for a transaction that rolled back, so no record exists. The customer's
     * reconcile then confirms the order in a TX B that commits, and that confirmation is the one record.
     */
    @Test
    fun `a rolled-back TX B publishes nothing, and the reconcile that then confirms the order publishes one record`() {
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
        orderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager)
        clock.pin(CONFIRMED_AT)
        assertThrows<DataAccessResourceFailureException> { orderConfirmer.confirm(user.id, order.id, IDEMPOTENCY_KEY, request) }
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()
        testPg.hasTransactions(pgOrderId, succeededTransaction(pgOrderId, amount = 2_000))

        val reconciled = orderConfirmer.reconcile(user.id, order.id)

        assertThat(reconciled.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertOnlyTheConfirmation(orderEvents.publishedRecords(expected = 1), quantity = 2)
        assertThat(testPg.unmatchedRequests()).isEmpty()
    }

    /** Each confirmed order is its own record: keyed by its own ID and carrying its own eventId. */
    @Test
    fun `two confirmed orders publish two records under different keys and event IDs`() {
        val first = prepareOrder()
        val second = prepareOrder()

        confirmOrder(testPg, first)
        confirmOrder(testPg, second)

        val requirements = listOf(first, second).map { confirmed -> recordOf(confirmed) }
        assertThat(orderEvents.publishedRecords(expected = 2)).satisfiesExactlyInAnyOrder(*requirements.toTypedArray())
    }

    /**
     * Each publish is logged once at INFO with the eventId and where the record landed, the topic, partition and offset the
     * consumer reads it from.
     */
    @Test
    fun `a published record is logged once at INFO with its event ID, topic, partition and offset`() {
        prepareOrder()
        confirmOrder(testPg)

        val record = orderEvents.publishedRecords(expected = 1).single()

        assertThat(kafkaAdapterLogs.awaitLinesAt(Level.INFO, RELAY_WAIT).map { it.formattedMessage }).containsExactly(
            "Published the order confirmed event. eventId=order-confirmed:v1:${order.id}, topic=${record.topic()}, " +
                "partition=${record.partition()}, offset=${record.offset()}",
        )
    }

    /**
     * The relay is proxied asynchronously: the confirmation runs on the test's thread, and the record is published from a
     * virtual thread. Only the relay's executor makes virtual threads, since `spring.threads.virtual.enabled` is off.
     */
    @Test
    fun `the record is published from a virtual thread, not from the thread that confirmed the order`() {
        prepareOrder()
        val publishingThread = theThreadThatPublishes()

        confirmOrder(testPg)

        val thread = publishingThread.get(RELAY_WAIT.toMillis(), TimeUnit.MILLISECONDS)
        assertThat(thread).isNotSameAs(Thread.currentThread())
        assertThat(thread.isVirtual).isTrue()
        assertThat(orderEvents.publishedRecords(expected = 1)).hasSize(1)
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
    }

    /**
     * [records] are one record: the confirmation of the one order a scene prepared, at [CONFIRMED_AT] with [quantity] units of
     * its one product, keyed by the order ID and holding design section 2's envelope.
     */
    private fun assertOnlyTheConfirmation(records: List<ConsumerRecord<String, String>>, quantity: Int) {
        assertThat(records).hasSize(1)
        assertThat(records.single().key()).isEqualTo(order.id.toString())
        assertThat(JsonContent(records.single().value())).isStrictlyEqualTo(
            """
            {
              "eventId": "order-confirmed:v1:${order.id}",
              "eventType": "OrderConfirmed",
              "schemaVersion": 1,
              "payload": {
                "orderId": ${order.id},
                "confirmedAt": "2026-10-27T01:02:03.123456Z",
                "items": [{ "productId": ${product.id}, "quantity": $quantity }]
              }
            }
            """,
        )
    }

    /** The record of [order]'s confirmation: keyed by its ID and carrying its eventId. */
    private fun recordOf(order: Order): ThrowingConsumer<ConsumerRecord<String, String>> {
        return ThrowingConsumer { record ->
            assertThat(record.key()).isEqualTo(order.id.toString())
            assertThat(JsonContent(record.value())).extractingPath("$.eventId").isEqualTo("order-confirmed:v1:${order.id}")
        }
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

    /**
     * Completes with the thread that first calls the template's `send`, and lets every call through to the real template. The
     * template is no AOP proxy, so the stub goes on the spy itself.
     */
    private fun theThreadThatPublishes(): CompletableFuture<Thread> {
        val thread = CompletableFuture<Thread>()
        every { kafkaTemplate.send(any<String>(), any(), any()) } answers {
            thread.complete(Thread.currentThread())
            callOriginal()
        }

        return thread
    }
}
