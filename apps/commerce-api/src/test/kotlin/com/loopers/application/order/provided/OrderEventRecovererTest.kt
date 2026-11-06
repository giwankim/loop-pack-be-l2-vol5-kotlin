package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.OrderEventOutboxFactory
import com.loopers.application.order.provided.OrderEventRecoveryResult.Outcome
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.Order
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaProducerException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.TestPropertySource
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * 누락 복구 adds the row an order confirmed without one is missing, leaves the same row alone, and refuses an order or a row it can't
 * take as it is (ADR 0036 rule 3). The scenes call the port on the real MySQL with the test PG and read rows in a new transaction, so
 * only what committed answers.
 *
 * An order confirmed without a row is what week 6 left: the same TX B, minus the row. The spy on the repository makes TX B's save
 * write nothing, once, as the §5 run does (ADR 0036 rule 5). Later saves are the real ones. A differing row is TX B's too, built with
 * one column changed by the spy on the factory. No scene writes a row or `status`.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=order-events-recovery"])
class OrderEventRecovererTest(
    private val orderEventRecoverer: OrderEventRecoverer,
    private val orderEventRelayer: OrderEventRelayer,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
    private val jdbc: JdbcTemplate,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** Already in microseconds, so the order and the row keep it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
        private val RECOVERED_AT: Instant = Instant.parse("2026-11-06T09:00:00.654321Z")
    }

    /** Where TX B's row goes missing ([confirmAnOrderLeavingNoRow]). Unstubbed, it is the real repository. */
    @MockkSpyBean
    private lateinit var orderEventOutboxRepository: OrderEventOutboxRepository

    /** Where TX B's row comes out different in one column ([confirmAnOrderLeavingADifferingRow]). Unstubbed, it is the real factory. */
    @MockkSpyBean
    private lateinit var orderEventOutboxFactory: OrderEventOutboxFactory

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
     * row is due when it was added, not when the order was confirmed, so the relay publishes it on its next round.
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

    /** The relay needs nothing of its own for a recovered row: one round publishes it, read back from the broker. */
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
        val missingOrder = confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        val presentOrder = prepareOrder(prepareProduct() to 1)
        confirmOrder(testPg, presentOrder)
        clock.pin(RECOVERED_AT)

        val results = orderEventRecoverer.recover(listOf(missingOrder.id, presentOrder.id))

        assertThat(results).containsExactly(
            OrderEventRecoveryResult(missingOrder.id, "order-confirmed:v1:${missingOrder.id}", Outcome.ADDED),
            OrderEventRecoveryResult(presentOrder.id, "order-confirmed:v1:${presentOrder.id}", Outcome.ALREADY_PRESENT),
        )
        assertThat(orderApplicationLogs.linesAt(Level.INFO).map { it.formattedMessage }).containsExactly(
            "Ran 누락 복구 for the order confirmed event. orderId=${missingOrder.id}, " +
                "eventId=order-confirmed:v1:${missingOrder.id}, result=ADDED",
            "Ran 누락 복구 for the order confirmed event. orderId=${presentOrder.id}, " +
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

    @Test
    fun `recovering an order that doesn't exist is refused, with no row written`() {
        val exception = assertThrows<OrderEventRecoveryRefusedException.OrderNotFound> {
            orderEventRecoverer.recover(listOf(404L))
        }

        assertThat(exception.message).isEqualTo("Refused 누락 복구: order 404 doesn't exist.")
        assertNoRows()
    }

    @Test
    fun `recovering a draft order is refused, since it has no confirmation time, with no row written`() {
        prepareOrder(prepareProduct() to 2)

        val exception = assertThrows<OrderEventRecoveryRefusedException.OrderNotConfirmed> {
            orderEventRecoverer.recover(listOf(order.id))
        }

        assertThat(exception.message).isEqualTo("Refused 누락 복구: order ${order.id} has no confirmation time.")
        assertNoRows()
    }

    @Test
    fun `recovering an order whose PG payment failed is refused, since it has no confirmation time, with no row written`() {
        prepareOrder(prepareProduct() to 2)
        confirmOrder(testPg, pgStatus = "FAILED")

        val exception = assertThrows<OrderEventRecoveryRefusedException.OrderNotConfirmed> {
            orderEventRecoverer.recover(listOf(order.id))
        }

        assertThat(exception.message).isEqualTo("Refused 누락 복구: order ${order.id} has no confirmation time.")
        assertNoRows()
    }

    /** A point-paid order is one an earlier week confirmed, which no code path makes now, so the scene writes it as those tests do. */
    @Test
    fun `recovering an order confirmed with points is refused, with no row written`() {
        prepareOrder(prepareProduct() to 2)
        jdbc.update(
            "update orders set status = 'CONFIRMED', payment_method = 'POINT', confirmed_at = now(6) where id = ?",
            order.id,
        )

        val exception = assertThrows<OrderEventRecoveryRefusedException.OrderNotPaidThroughPg> {
            orderEventRecoverer.recover(listOf(order.id))
        }

        assertThat(exception.message).isEqualTo("Refused 누락 복구: order ${order.id} wasn't paid through the PG.")
        assertNoRows()
    }

    /**
     * The row is TX B's own, built with one column changed, so the scene writes no row itself. The event ID differs only in case: the
     * column's collation is case-insensitive, so the lookup still finds the row, as the unique key would. The payload is the same JSON
     * with a space after each comma, so it is compared as the exact string, not as JSON.
     */
    @ParameterizedTest
    @ValueSource(strings = ["event_id", "order_id", "event_type", "schema_version", "message_key", "payload"])
    fun `recovering an order whose row differs in a column is refused, naming the event and the column but not the payload`(
        column: String,
    ) {
        confirmAnOrderLeavingADifferingRow(prepareProduct() to 2, column = column)
        val storedOutbox = committedOrderEventOutbox()
        clock.pin(RECOVERED_AT)

        val exception = assertThrows<OrderEventRecoveryRefusedException.OutboxRowDiffers> {
            orderEventRecoverer.recover(listOf(order.id))
        }

        val eventId = "order-confirmed:v1:${order.id}"
        assertThat(exception.message).isEqualTo("Refused 누락 복구: the outbox row of event $eventId differs in $column.")
        assertThat(exception.message).doesNotContain(storedOutbox.payload, """"items"""")
        val warnLines = orderApplicationLogs.linesAt(Level.WARN).map { it.formattedMessage }
        assertThat(warnLines).containsExactly("${exception.message} orderId=${order.id}")
        assertThat(warnLines.single()).doesNotContain(storedOutbox.payload, """"items"""")
        assertUnchanged(storedOutbox, OrderEventOutboxStatus.PENDING)
    }

    /**
     * The middle order is a draft, refused as unconfirmed. Once it is confirmed, the same list runs again: the first order's row is
     * found as it was, and only the last two are added.
     */
    @Test
    fun `a refusal stops the list, the orders before it keep their rows, and a rerun of the whole list adds only the rest`() {
        val firstOrder = confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        val draftOrder = prepareOrder(prepareProduct() to 1)
        val lastOrder = confirmAnOrderLeavingNoRow(prepareProduct() to 3)
        val orderIds = listOf(firstOrder.id, draftOrder.id, lastOrder.id)
        clock.pin(RECOVERED_AT)

        assertThrows<OrderEventRecoveryRefusedException.OrderNotConfirmed> {
            orderEventRecoverer.recover(orderIds)
        }

        val firstOutbox = committedOrderEventOutbox(firstOrder.id)
        assertThat(firstOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(committedOrderEventOutboxOrNull(draftOrder.id)).isNull()
        assertThat(committedOrderEventOutboxOrNull(lastOrder.id)).isNull()
        assertThat(orderApplicationLogs.linesAt(Level.INFO).map { it.formattedMessage }).containsExactly(
            "Ran 누락 복구 for the order confirmed event. orderId=${firstOrder.id}, " +
                "eventId=order-confirmed:v1:${firstOrder.id}, result=ADDED",
        )

        confirmLeavingNoRow(draftOrder)
        clock.pin(RECOVERED_AT)
        val results = orderEventRecoverer.recover(orderIds)

        assertThat(results).containsExactly(
            OrderEventRecoveryResult(firstOrder.id, "order-confirmed:v1:${firstOrder.id}", Outcome.ALREADY_PRESENT),
            OrderEventRecoveryResult(draftOrder.id, "order-confirmed:v1:${draftOrder.id}", Outcome.ADDED),
            OrderEventRecoveryResult(lastOrder.id, "order-confirmed:v1:${lastOrder.id}", Outcome.ADDED),
        )
        assertThat(committedOrderEventOutbox(firstOrder.id).id).isEqualTo(firstOutbox.id)
        assertThat(inNewTransaction { orderEventOutboxRepository.count() }).isEqualTo(3)
    }

    /**
     * Nothing is locked (ADR 0036 rule 4). Either both miss the row, and the later insert collides on the unique event ID, or the
     * later one reads the row the first committed. Which one happens is up to the race, so both are allowed. Either way one row.
     */
    @Test
    fun `two recoveries of one order started together leave one row, added by one while the other collides or finds it`() {
        confirmAnOrderLeavingNoRow(prepareProduct() to 2)
        clock.pin(RECOVERED_AT)

        val results = runConcurrently(List(2) { { orderEventRecoverer.recover(listOf(order.id)).single() } })

        val outcomes = tally(results, DataIntegrityViolationException::class)
        assertThat(outcomes.technicalErrors).isZero()
        assertThat(outcomes.total).isEqualTo(2)
        val successfulOutcomes = results.mapNotNull { it.getOrNull()?.outcome }
        assertThat(successfulOutcomes).containsOnlyOnce(Outcome.ADDED)
        assertThat(successfulOutcomes - Outcome.ADDED).isSubsetOf(Outcome.ALREADY_PRESENT)
        assertThat(inNewTransaction { orderEventOutboxRepository.count() }).isOne()
    }

    private fun assertNoRows() {
        assertThat(inNewTransaction { orderEventOutboxRepository.count() }).isZero()
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
     * Confirms an order of [items] at [CONFIRMED_AT] through the real TX A and TX B, with TX B's row built differently in [column]
     * once. TX B saves what the factory gave it, and recovery's own build is the real one.
     */
    private fun confirmAnOrderLeavingADifferingRow(vararg items: Pair<Product, Int?>, column: String): Order {
        val preparedOrder = prepareOrder(*items)
        every { orderEventOutboxFactory.create(any()) } answers { differingIn(column, callOriginal()) } andThenAnswer
            { callOriginal() }
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg, preparedOrder)

        return preparedOrder
    }

    private fun differingIn(column: String, outbox: OrderEventOutbox): OrderEventOutbox {
        return OrderEventOutbox(
            eventId = if (column == "event_id") outbox.eventId.uppercase() else outbox.eventId,
            orderId = if (column == "order_id") outbox.orderId + 1_000 else outbox.orderId,
            eventType = if (column == "event_type") "OrderCancelled" else outbox.eventType,
            schemaVersion = if (column == "schema_version") outbox.schemaVersion + 1 else outbox.schemaVersion,
            messageKey = if (column == "message_key") "${outbox.messageKey}-other" else outbox.messageKey,
            payload = if (column == "payload") outbox.payload.replace(",", ", ") else outbox.payload,
            nextAttemptAt = outbox.nextAttemptAt,
        )
    }

    /**
     * Confirms an order of [items] at [CONFIRMED_AT] through the real TX A and TX B, with TX B's row saved nowhere, and checks in a new
     * transaction that no row committed.
     */
    private fun confirmAnOrderLeavingNoRow(vararg items: Pair<Product, Int?>): Order {
        val preparedOrder = prepareOrder(*items)
        confirmLeavingNoRow(preparedOrder)

        return preparedOrder
    }

    /** Confirms [draftOrder] as [confirmAnOrderLeavingNoRow] does, for a scene that holds the order unconfirmed first. */
    private fun confirmLeavingNoRow(draftOrder: Order) {
        every { orderEventOutboxRepository.save(any()) } answers { firstArg() } andThenAnswer { callOriginal() }
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg, draftOrder)

        assertThat(committedOrderEventOutboxOrNull(draftOrder.id)).isNull()
    }

    private fun committedOrderEventOutboxOrNull(orderId: Long): OrderEventOutbox? {
        return inNewTransaction { orderEventOutboxRepository.findByEventId("order-confirmed:v1:$orderId") }
    }
}
