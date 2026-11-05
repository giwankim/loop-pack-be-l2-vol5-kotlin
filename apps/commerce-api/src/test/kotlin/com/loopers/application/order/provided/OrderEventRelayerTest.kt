package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.application.order.OrderEventOutboxModifyService
import com.loopers.application.order.OrderEventRelay
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.domain.order.OrderStatus
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.linesAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.TestPropertySource
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * One round of the relay takes a row left by a real confirmation to the broker and then to `PUBLISHED` (ADR 0035 rule 1). The scenes
 * call the port on the real MySQL, with the test PG and the Kafka test container. Rows are read in a new transaction, so only what
 * committed answers, and records are read from the broker rather than counted from `send` calls.
 *
 * The class names a topic of its own, since the broker is one per JVM, and each scene reads it from where it ended before the scene
 * ([TopicReader]). Nothing schedules the relay here, so a round runs only when a scene calls the port. The clock is pinned, so the
 * times are literals. They lie before the real date, so a query that compared with the database's `now()` would find a row due early.
 */
@EnableTestPg
@TestPropertySource(properties = ["order-events.topic=order-events-relay"])
class OrderEventRelayerTest(
    private val orderEventRelayer: OrderEventRelayer,
    private val orderEventPublisher: OrderEventPublisher,
    private val orderFinder: OrderFinder,
    private val clock: TestClock,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** Already in microseconds, so the order and the row keep it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
        private val ROUND_STARTED_AT: Instant = Instant.parse("2026-10-27T01:02:08.123456Z")

        /** Valid JSON, so the check constraint lets it in, but not a message body, so it can't be rebuilt. */
        private const val PAYLOAD_NOT_A_MESSAGE_BODY = """{"unexpected":"not a message body"}"""
        private const val REBUILD_FAILURE_MESSAGE =
            "tools.jackson.databind.exc.MismatchedInputException: " +
                "at com.loopers.adapter.integration.kafka.OrderConfirmedMessage\$Payload[\"orderId\"]"
    }

    /** Where the stop scene stops the relay ([stopRelayAfterBrokerAckBeforeMarkingPublished]). Unstubbed, it is the real service. */
    @MockkSpyBean
    private lateinit var orderEventOutboxModifyService: OrderEventOutboxModifyService

    /** The test PG's server. Like [OrderPaymentIntegrationTest], WireMock's listener injects it into a field. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    private lateinit var orderEvents: TopicReader

    /** Logs from the Kafka adapter. The adapter logs a publish after awaiting its future, on the thread that called the port. */
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
        kafkaAdapterLogs.stop()
    }

    @Test
    fun `one round publishes the row a confirmation left and saves it as published`() {
        confirmAnOrderLeavingARow()
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        val publishedOutbox = committedOrderEventOutbox()
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:08.123456Z"))
        assertThat(publishedOutbox.attemptCount).isOne()
        assertThat(publishedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:03:08.123456Z"))
        assertThat(publishedOutbox.lastError).isNull()
        assertThat(orderEvents.publishedRecords(expected = 1)).hasSize(1)
    }

    /**
     * The whole record, as week 6's contract has it (order-events design 2). The value is compared as a string, so it fails if the keys
     * change order, which a JSON comparison wouldn't see. This closes the gap order-outbox design 7 left open: the row alone can't show
     * the envelope.
     */
    @Test
    fun `the record carries the order's key and the whole envelope, in the contract's order and with no headers`() {
        confirmAnOrderLeavingARow(quantity = 2)

        orderEventRelayer.publishDueEvents()

        val publishedRecord = orderEvents.publishedRecords(expected = 1).single()
        assertThat(publishedRecord.topic()).isEqualTo("order-events-relay")
        assertThat(publishedRecord.key()).isEqualTo(order.id.toString())
        assertThat(publishedRecord.value()).isEqualTo(
            """{"eventId":"order-confirmed:v1:${order.id}","eventType":"OrderConfirmed","schemaVersion":1,""" +
                """"payload":{"orderId":${order.id},"confirmedAt":"2026-10-27T01:02:03.123456Z",""" +
                """"items":[{"productId":${product.id},"quantity":2}]}}""",
        )
        assertThat(publishedRecord.headers().toArray()).isEmpty()
    }

    @Test
    fun `a publish logs one info line naming the event and where its record landed`() {
        confirmAnOrderLeavingARow()

        orderEventRelayer.publishDueEvents()

        val publishedRecord = orderEvents.publishedRecords(expected = 1).single()
        assertThat(kafkaAdapterLogs.linesAt(Level.INFO).map { it.formattedMessage }).containsExactly(
            "Published the order confirmed event. eventId=order-confirmed:v1:${order.id}, topic=order-events-relay, " +
                "partition=${publishedRecord.partition()}, offset=${publishedRecord.offset()}",
        )
    }

    /**
     * The row is due at [CONFIRMED_AT], which is long past by the database's `now()`. Only the `Clock` bean says otherwise, so a round
     * before that time leaves it, and moving the clock onto that very instant makes it due.
     */
    @Test
    fun `the clock bean decides when a row is due`() {
        confirmAnOrderLeavingARow()
        clock.pin(CONFIRMED_AT.minusSeconds(1))

        orderEventRelayer.publishDueEvents()

        val waitingOutbox = committedOrderEventOutbox()
        assertThat(waitingOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(waitingOutbox.attemptCount).isZero()
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()

        clock.advance(Duration.ofSeconds(1))
        orderEventRelayer.publishDueEvents()

        assertThat(committedOrderEventOutbox().status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(orderEvents.publishedRecords(expected = 1)).hasSize(1)
    }

    /** The claim has to commit before the send, and no transaction may wrap a network wait, so the call itself is refused (ADR 0035 rule 11). */
    @Test
    fun `a round called inside a transaction is refused before it claims or sends anything`() {
        confirmAnOrderLeavingARow()

        assertThrows<IllegalTransactionStateException> {
            inNewTransaction { orderEventRelayer.publishDueEvents() }
        }

        val untouchedOutbox = committedOrderEventOutbox()
        assertThat(untouchedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(untouchedOutbox.attemptCount).isZero()
        assertThat(orderEvents.publishedRecords(expected = 0)).isEmpty()
    }

    /**
     * The shared broker can't be paused without affecting other classes, so the failing row is one whose stored payload can't be rebuilt.
     * It sits between the healthy ones in the claim's order, so a failure that ended the batch would strand the last.
     */
    @Test
    fun `a row that can't be rebuilt fails alone, and the healthy rows of its batch are published`() {
        prepareOrderEventOutboxWithoutOrder(orderId = 101, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutboxWithoutOrder(orderId = 102, payload = PAYLOAD_NOT_A_MESSAGE_BODY, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutboxWithoutOrder(orderId = 103, nextAttemptAt = CONFIRMED_AT)
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        val failedOutbox = committedOrderEventOutbox(orderId = 102)
        assertThat(failedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(failedOutbox.attemptCount).isOne()
        assertThat(failedOutbox.lastError).isEqualTo(REBUILD_FAILURE_MESSAGE)
        assertThat(failedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:13.123456Z"))
        assertThat(failedOutbox.publishedAt).isNull()
        assertThat(committedOrderEventOutbox(orderId = 101).status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(committedOrderEventOutbox(orderId = 103).status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(orderEvents.publishedRecords(expected = 2).map { it.key() }).containsExactlyInAnyOrder("101", "103")
    }

    @Test
    fun `a failure logs one warn line naming the event and the cause`() {
        prepareOrderEventOutboxWithoutOrder(orderId = 102, payload = PAYLOAD_NOT_A_MESSAGE_BODY, nextAttemptAt = CONFIRMED_AT)
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        assertThat(kafkaAdapterLogs.linesAt(Level.WARN).map { it.formattedMessage }).containsExactly(
            "Failed to publish the order confirmed event. eventId=order-confirmed:v1:102, " +
                "cause=$REBUILD_FAILURE_MESSAGE",
        )
    }

    /** One microsecond before the backoff ends nothing is claimed. On its last instant the second attempt fails and waits twice as long. */
    @Test
    fun `a failed row isn't claimed until its backoff has passed, and moving the clock makes it due again`() {
        prepareOrderEventOutboxWithoutOrder(orderId = 102, payload = PAYLOAD_NOT_A_MESSAGE_BODY, nextAttemptAt = CONFIRMED_AT)
        clock.pin(ROUND_STARTED_AT)
        orderEventRelayer.publishDueEvents()

        clock.pin(Instant.parse("2026-10-27T01:02:13.123455Z"))
        orderEventRelayer.publishDueEvents()

        assertThat(committedOrderEventOutbox(orderId = 102).attemptCount).isOne()

        clock.pin(Instant.parse("2026-10-27T01:02:13.123456Z"))
        orderEventRelayer.publishDueEvents()

        val retriedOutbox = committedOrderEventOutbox(orderId = 102)
        assertThat(retriedOutbox.attemptCount).isEqualTo(2)
        assertThat(retriedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:23.123456Z"))
    }

    /**
     * Jackson's messages quote the value they refused, so a rebuild failure can't name them whole. Each payload here is valid JSON whose
     * refused value is the marker, at a field, at the root and in a date.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"orderId":"secret-value","confirmedAt":"2026-10-27T01:02:03Z","items":[]}""",
            """"secret-value"""",
            """{"orderId":1,"confirmedAt":"secret-value","items":[]}""",
            """{"orderId":1,"confirmedAt":"2026-10-27T01:02:03Z","items":[{"productId":"secret-value","quantity":1}]}""",
        ],
    )
    fun `the failure text and the warn line never carry the payload`(payload: String) {
        prepareOrderEventOutboxWithoutOrder(orderId = 102, payload = payload, nextAttemptAt = CONFIRMED_AT)

        orderEventRelayer.publishDueEvents()

        val failedOutbox = committedOrderEventOutbox(orderId = 102)
        assertThat(failedOutbox.lastError).startsWith("tools.jackson.").doesNotContain("secret-value")
        val warnLine = kafkaAdapterLogs.linesAt(Level.WARN).single()
        assertThat(warnLine.formattedMessage).doesNotContain("secret-value")
        assertThat(warnLine.throwableProxy).isNull()
    }

    /** A controlled stop after the commit, before any relay ran: the JVM stays up, no process is killed, and no round is called. */
    @Test
    fun `a confirmed order that no relay has reached has a pending row and no record`() {
        confirmAnOrderLeavingARow()

        val committedOrder = inNewTransaction { orderFinder.find(user.id, order.id) }
        val waitingOutbox = committedOrderEventOutbox()
        val publishedRecords = orderEvents.publishedRecords(expected = 0)
        assertThat(committedOrder.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(waitingOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(waitingOutbox.attemptCount).isZero()
        assertThat(publishedRecords).isEmpty()

        log.info {
            "Relay stop, after the commit before the relay: order ${order.id} ${committedOrder.status}; " +
                "row ${describe(waitingOutbox)}; records ${publishedRecords.size}"
        }
    }

    /** A controlled stop: the JVM stays up, no process is killed, and the restart is a new [OrderEventRelay] on the same database and topic. */
    @Test
    fun `a new relay finds the row a confirmation stored and publishes it`() {
        confirmAnOrderLeavingARow()
        clock.pin(ROUND_STARTED_AT)

        newRelay().publishDueEvents()

        val publishedOutbox = committedOrderEventOutbox()
        val publishedRecord = orderEvents.publishedRecords(expected = 1).single()
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.attemptCount).isOne()
        assertThat(publishedRecord.key()).isEqualTo(order.id.toString())
        assertThat(publishedRecord.value()).contains(""""eventId":"order-confirmed:v1:${order.id}"""")

        log.info {
            "Relay stop, a new relay: row ${describe(publishedOutbox)}; record ${describe(publishedRecord)}"
        }
    }

    /**
     * A controlled stop: the JVM stays up, no process is killed, and the one-shot stub is what stops the first relay. The restart is a
     * new [OrderEventRelay] on the same database and topic, time moves through [TestClock], and no scene writes `status`. The lease
     * alone holds the row back and then lets it go, so the republish is the at-least-once the design accepts, and the consumer's
     * idempotency is what meets it, which step 3 shows. The two records are read from the broker, not counted from `send` calls.
     */
    @Test
    fun `a relay stopped after the broker's ack leaves the row pending, and a new relay republishes it past the lease`() {
        confirmAnOrderLeavingARow()
        clock.pin(ROUND_STARTED_AT)
        stopRelayAfterBrokerAckBeforeMarkingPublished()

        assertThrows<DataAccessResourceFailureException> { orderEventRelayer.publishDueEvents() }

        val firstRecord = orderEvents.publishedRecords(expected = 1).single()
        val stoppedOutbox = committedOrderEventOutbox()
        assertThat(stoppedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(stoppedOutbox.attemptCount).isOne()
        assertThat(stoppedOutbox.publishedAt).isNull()
        assertThat(stoppedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:03:08.123456Z"))

        val restartedRelay = newRelay()
        clock.pin(Instant.parse("2026-10-27T01:03:08.123455Z"))
        restartedRelay.publishDueEvents()

        val recordsInsideTheLease = orderEvents.publishedRecords(expected = 0)
        val leasedOutbox = committedOrderEventOutbox()
        assertThat(recordsInsideTheLease).isEmpty()
        assertThat(leasedOutbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(leasedOutbox.attemptCount).isOne()

        clock.pin(Instant.parse("2026-10-27T01:03:09.123456Z"))
        restartedRelay.publishDueEvents()

        val secondRecord = orderEvents.publishedRecords(expected = 1).single()
        val publishedOutbox = committedOrderEventOutbox()
        assertThat(firstRecord.value()).contains(""""eventId":"order-confirmed:v1:${order.id}"""")
        assertThat(secondRecord.value()).isEqualTo(firstRecord.value())
        assertThat(secondRecord.key()).isEqualTo(firstRecord.key())
        assertThat(secondRecord.partition()).isEqualTo(firstRecord.partition())
        assertThat(secondRecord.offset()).isGreaterThan(firstRecord.offset())
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.attemptCount).isEqualTo(2)
        assertThat(publishedOutbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:03:09.123456Z"))

        log.info {
            "Relay stop, after the ack before PUBLISHED: after the stop: row ${describe(stoppedOutbox)}, " +
                "record ${describe(firstRecord)}; inside the lease: row ${describe(leasedOutbox)}, " +
                "new records ${recordsInsideTheLease.size}; past the lease: row ${describe(publishedOutbox)}, " +
                "record ${describe(secondRecord)}"
        }
    }

    /**
     * Stops a round between the broker's ack and saving `PUBLISHED`: the first `markPublished` throws and later calls are the real ones.
     * The relay doesn't catch the failure, so it leaves the round the way a database failure in that transaction would (order-outbox
     * design 13.5). The field is a CGLIB proxy, so the stub goes on the spy it wraps.
     */
    private fun stopRelayAfterBrokerAckBeforeMarkingPublished() {
        val target = AopTestUtils.getUltimateTargetObject<OrderEventOutboxModifyService>(orderEventOutboxModifyService)
        val failure = DataAccessResourceFailureException("The relay stopped after the broker's ack, before saving PUBLISHED.")
        every { target.markPublished(any()) } throws failure andThenAnswer { callOriginal() }
    }

    /** What a restart makes: a new instance on the context's beans. Called directly, it has no proxy, which a scene's round doesn't need. */
    private fun newRelay(): OrderEventRelayer {
        return OrderEventRelay(orderEventOutboxModifyService, orderEventPublisher)
    }

    private fun describe(outbox: OrderEventOutbox): String {
        return "${outbox.eventId} ${outbox.status} attempts ${outbox.attemptCount} " +
            "published_at ${outbox.publishedAt} next_attempt_at ${outbox.nextAttemptAt}"
    }

    private fun describe(record: ConsumerRecord<String, String>): String {
        return "key ${record.key()} partition ${record.partition()} offset ${record.offset()}"
    }

    /** Two full batches and a short one, so only a round that claims again gets past the first 20 (ADR 0035 rule 1). */
    @Test
    fun `one round publishes a backlog larger than one batch`() {
        val backlogOrderIds = prepareOutboxBacklogWithoutOrders(rowCount = 45)
        clock.pin(ROUND_STARTED_AT)

        orderEventRelayer.publishDueEvents()

        assertEachRowPublishedOnce(backlogOrderIds)
    }

    /**
     * Two relays are not a supported setup (ADR 0035 rule 13). One relay is, and turning on a second by mistake is a setting rather than
     * something the code stops. The scene shows only what the database's coordination does then: each attempt is counted once and each
     * row has one record. It says nothing about who owns a row past its lease, and it doesn't assert how the rows were divided. The backlog
     * is above the size at which MySQL was measured using the index (the relay probe note's plan by table size), and in the recorded run
     * the relays published 240 and 260.
     */
    @Test
    fun `two relays over one backlog publish each row once`() {
        val backlogOrderIds = prepareOutboxBacklogWithoutOrders(rowCount = 500)
        clock.pin(ROUND_STARTED_AT)

        val roundResults = runConcurrently(List(2) { { orderEventRelayer.publishDueEvents() } })

        val outcomes = tally(roundResults)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 2, rejections = 0, technicalErrors = 0))
        assertThat(outcomes.total).isEqualTo(2)
        assertEachRowPublishedOnce(backlogOrderIds)
    }

    /**
     * A relay past its lease (ADR 0035 rule 9). The first relay's claim stands for one still waiting on the broker when its lease ends, and
     * the second relay's round publishes the row. The late result comes through the service, since holding a real send past the lease
     * would take a stubbed publisher. Everything the second relay wrote has to survive it.
     */
    @Test
    fun `a failure reported after another relay published leaves the row published`() {
        prepareOrderEventOutboxWithoutOrder(orderId = 101, nextAttemptAt = CONFIRMED_AT)
        clock.pin(ROUND_STARTED_AT)
        val lateOutbox = orderEventOutboxModifyService.claimDueOutboxes(20).single()
        clock.advance(Duration.ofSeconds(60))
        orderEventRelayer.publishDueEvents()
        clock.advance(Duration.ofSeconds(1))

        orderEventOutboxModifyService.markFailed(lateOutbox.id, "late failure")

        val publishedOutbox = committedOrderEventOutbox(orderId = 101)
        assertThat(publishedOutbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(publishedOutbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:03:08.123456Z"))
        assertThat(publishedOutbox.attemptCount).isEqualTo(2)
        assertThat(publishedOutbox.lastError).isNull()
        assertThat(publishedOutbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:04:08.123456Z"))
        assertThat(orderEvents.publishedRecords(expected = 1)).hasSize(1)
    }

    /**
     * Rows saved directly, with order IDs that name no order, because the row refers to its order by ID only. Confirming this many orders
     * through the test PG would add nothing to what a round does with them.
     */
    private fun prepareOutboxBacklogWithoutOrders(rowCount: Int): List<Long> {
        val backlogOrderIds = (1L..rowCount).toList()
        backlogOrderIds.forEach { orderId ->
            prepareOrderEventOutboxWithoutOrder(orderId = orderId, nextAttemptAt = CONFIRMED_AT)
        }

        return backlogOrderIds
    }

    /** One record per row by the record's key, which is the order's ID, and one attempt per row as committed. */
    private fun assertEachRowPublishedOnce(orderIds: List<Long>) {
        val publishedRecords = orderEvents.publishedRecords(expected = orderIds.size)
        assertThat(publishedRecords.map { it.key() }).containsExactlyInAnyOrderElementsOf(orderIds.map { it.toString() })

        orderIds.forEach { orderId ->
            val outbox = committedOrderEventOutbox(orderId)
            assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
            assertThat(outbox.attemptCount).isOne()
        }
    }

    /** Confirms an order of one product at [CONFIRMED_AT] through the real TX B. A scene's clock stays pinned there until it moves it. */
    private fun confirmAnOrderLeavingARow(quantity: Int? = null) {
        prepareOrder(quantity = quantity)
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)
    }
}
