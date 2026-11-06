package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.provided.OrderEventRecoveryResult.Outcome
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.product.Product
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.linesAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.core.KafkaProducerException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * 누락 복구 adds the row an order confirmed without one is missing, and leaves a row that is there alone (ADR 0036 rule 3). The scenes
 * call the port on the real MySQL with the test PG and read rows in a new transaction, so only what committed answers.
 *
 * An order confirmed without a row is what week 6 left: the same TX B, minus the row. The spy on the repository makes TX B's save
 * write nothing, once, as the §5 run does (ADR 0036 rule 5). Later saves are the real ones. No scene writes `status`.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=order-events-recovery"])
class OrderEventRecovererTest(
    private val orderEventRecoverer: OrderEventRecoverer,
    private val orderEventRelayer: OrderEventRelayer,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** Already in microseconds, so the order and the row keep it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
        private val RECOVERED_AT: Instant = Instant.parse("2026-11-06T09:00:00.654321Z")
    }

    /** Where TX B's row goes missing ([confirmAnOrderLeavingNoRow]). Unstubbed, it is the real repository. */
    @MockkSpyBean
    private lateinit var orderEventOutboxRepository: OrderEventOutboxRepository

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /** Where the blocked scene fails sends ([blockThroughFailedRounds]). Unstubbed, it is the real template. */
    @MockkSpyBean
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    private lateinit var orderEvents: TopicReader

    /** Logs from the application's order package, where recovery logs each order. */
    private val orderApplicationLogs = ListAppender<ILoggingEvent>()

    private val orderApplicationLogger = LoggerFactory.getLogger("com.loopers.application.order") as Logger

    @BeforeEach
    fun readTheTopicFromItsEnd() {
        orderEvents = TopicReader(kafkaProperties.bootstrapServers, orderEventsProperties.topic)
    }

    @BeforeEach
    fun captureLogs() {
        orderApplicationLogs.start()
        orderApplicationLogger.addAppender(orderApplicationLogs)
    }

    @AfterEach
    fun closeTheTopicReader() {
        orderEvents.close()
    }

    @AfterEach
    fun releaseLogs() {
        orderApplicationLogger.detachAppender(orderApplicationLogs)
        orderApplicationLogs.stop()
    }

    /**
     * The event columns are compared with the literals [OrderEventOutboxTest] pins for TX B's row, the payload as a whole string. The
     * row is due when it was added, not when the order was confirmed, so the relay sends it on its next round.
     */
    @Test
    fun `a confirmed PG order without a row gets the row TX B would have written, pending and due at the clock's now`() {
        val cheaper = prepareProduct(price = 1_000)
        val pricier = prepareProduct(price = 2_000)
        confirmAnOrderLeavingNoRow(pricier to 1, cheaper to 2)
        clock.pin(RECOVERED_AT)

        val results = orderEventRecoverer.recover(listOf(order.id))

        assertThat(results).containsExactly(OrderEventRecoveryResult(order.id, "order-confirmed:v1:${order.id}", Outcome.ADDED))
        val row = committedOrderEventOutbox()
        assertThat(row.eventId).isEqualTo("order-confirmed:v1:${order.id}")
        assertThat(row.orderId).isEqualTo(order.id)
        assertThat(row.eventType).isEqualTo("OrderConfirmed")
        assertThat(row.schemaVersion).isEqualTo(1)
        assertThat(row.messageKey).isEqualTo(order.id.toString())
        assertThat(row.payload).isEqualTo(
            """{"orderId":${order.id},"confirmedAt":"2026-10-27T01:02:03.123456Z",""" +
                """"items":[{"productId":${cheaper.id},"quantity":2},{"productId":${pricier.id},"quantity":1}]}""",
        )
        assertThat(row.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(row.attemptCount).isZero()
        assertThat(row.nextAttemptAt).isEqualTo(Instant.parse("2026-11-06T09:00:00.654321Z"))
        assertThat(row.publishedAt).isNull()
        assertThat(row.lastError).isNull()
    }

    /** The relay needs nothing of its own for a recovered row: one round sends it, read back from the broker. */
    @Test
    fun `one relay round after recovery puts one record of the order's event on the topic`() {
        confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        clock.pin(RECOVERED_AT)
        orderEventRecoverer.recover(listOf(order.id))

        orderEventRelayer.publishDueEvents()

        val publishedRecord = orderEvents.publishedRecords(expected = 1).single()
        assertThat(publishedRecord.key()).isEqualTo(order.id.toString())
        assertThat(publishedRecord.value()).contains(""""eventId":"order-confirmed:v1:${order.id}"""")
        assertThat(committedOrderEventOutbox().status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
    }

    @Test
    fun `recovering an order whose recovered row is still pending again adds nothing and says so`() {
        confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        clock.pin(RECOVERED_AT)
        orderEventRecoverer.recover(listOf(order.id))
        val pendingOutbox = committedOrderEventOutbox()

        val results = orderEventRecoverer.recover(listOf(order.id))

        assertThat(results).containsExactly(alreadyPresent())
        assertUnchanged(pendingOutbox, OrderEventOutboxStatus.PENDING)
    }

    /** The row TX B left, published by the relay: recovery is safe on any order, not only on one it recovered. */
    @Test
    fun `recovering an order whose row is published adds nothing and says so`() {
        prepareOrder(prepareProduct() to 2)
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)
        orderEventRelayer.publishDueEvents()
        orderEvents.publishedRecords(expected = 1)
        val publishedOutbox = committedOrderEventOutbox()

        val results = orderEventRecoverer.recover(listOf(order.id))

        assertThat(results).containsExactly(alreadyPresent())
        assertUnchanged(publishedOutbox, OrderEventOutboxStatus.PUBLISHED)
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()
    }

    /** The row reaches `BLOCKED` through the relay: every send of its key fails, as in [OrderEventRelayFailedSendTest]. */
    @Test
    fun `recovering an order whose row is blocked adds nothing and says so`() {
        confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        clock.pin(RECOVERED_AT)
        orderEventRecoverer.recover(listOf(order.id))
        blockThroughFailedRounds(order.id)
        val blockedOutbox = committedOrderEventOutbox()

        val results = orderEventRecoverer.recover(listOf(order.id))

        assertThat(results).containsExactly(alreadyPresent())
        assertUnchanged(blockedOutbox, OrderEventOutboxStatus.BLOCKED)
    }

    /** One result and one line per order, in the order given, after that order's transaction committed. */
    @Test
    fun `each order logs one info line naming the order, the event and the result`() {
        confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        val missingOrder = order
        prepareOrder(prepareProduct() to 1)
        confirmOrder(testPg)
        val presentOrder = order
        clock.pin(RECOVERED_AT)

        val results = orderEventRecoverer.recover(listOf(missingOrder.id, presentOrder.id))

        assertThat(results).containsExactly(
            OrderEventRecoveryResult(missingOrder.id, "order-confirmed:v1:${missingOrder.id}", Outcome.ADDED),
            OrderEventRecoveryResult(presentOrder.id, "order-confirmed:v1:${presentOrder.id}", Outcome.ALREADY_PRESENT),
        )
        assertThat(orderApplicationLogs.linesAt(Level.INFO).map { it.formattedMessage }).containsExactly(
            "Recovered the order confirmed event. orderId=${missingOrder.id}, " +
                "eventId=order-confirmed:v1:${missingOrder.id}, result=ADDED",
            "Recovered the order confirmed event. orderId=${presentOrder.id}, " +
                "eventId=order-confirmed:v1:${presentOrder.id}, result=ALREADY_PRESENT",
        )
    }

    /** Recovery reads the order and writes a row. Any request, create or lookup, would show in the test PG's log. */
    @Test
    fun `recovery makes no PG request`() {
        confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        val requestsBeforeRecovery = testPg.requestCount()
        clock.pin(RECOVERED_AT)

        orderEventRecoverer.recover(listOf(order.id))
        orderEventRecoverer.recover(listOf(order.id))

        assertThat(testPg.requestCount()).isEqualTo(requestsBeforeRecovery)
    }

    private fun alreadyPresent(): OrderEventRecoveryResult {
        return OrderEventRecoveryResult(order.id, "order-confirmed:v1:${order.id}", Outcome.ALREADY_PRESENT)
    }

    /** The order still has exactly the row it had, read in a new transaction: same ID, state, attempts and times. */
    private fun assertUnchanged(before: OrderEventOutbox, status: OrderEventOutboxStatus) {
        val after = committedOrderEventOutbox()
        assertThat(inNewTransaction { orderEventOutboxRepository.count() }).isOne()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.status).isEqualTo(status)
        assertThat(after.attemptCount).isEqualTo(before.attemptCount)
        assertThat(after.nextAttemptAt).isEqualTo(before.nextAttemptAt)
        assertThat(after.publishedAt).isEqualTo(before.publishedAt)
        assertThat(after.lastError).isEqualTo(before.lastError)
    }

    /** Fails every send of the order's key for as many rounds as the limit, moving the clock by the longest backoff after each. */
    private fun blockThroughFailedRounds(orderId: Long) {
        every { kafkaTemplate.send(any<String>(), orderId.toString(), any<Any>()) } returns failedSend()

        repeat(20) {
            orderEventRelayer.publishDueEvents()
            clock.advance(Duration.ofMinutes(5))
        }

        assertThat(committedOrderEventOutbox(orderId).status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
    }

    /** Fails as the template's own callback does: the future holds a [KafkaProducerException] around the producer's exception. */
    private fun failedSend(): CompletableFuture<SendResult<Any, Any>> {
        val producerRecord = ProducerRecord<Any, Any>(orderEventsProperties.topic, "key", "value")

        return CompletableFuture.failedFuture(
            KafkaProducerException(producerRecord, "Failed to send", TimeoutException("Expiration of 1 record(s)")),
        )
    }

    /**
     * Confirms an order of [items] at [CONFIRMED_AT] through the real TX A and TX B, with TX B's row saved nowhere, and checks in a new
     * transaction that no row committed.
     */
    private fun confirmAnOrderLeavingNoRow(vararg items: Pair<Product, Int?>) {
        prepareOrder(*items)
        every { orderEventOutboxRepository.save(any()) } answers { firstArg() } andThenAnswer { callOriginal() }
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)

        assertThat(inNewTransaction { orderEventOutboxRepository.findByEventId("order-confirmed:v1:${order.id}") }).isNull()
    }
}
