package com.loopers.application.order.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.TestPg
import com.loopers.support.TopicReader
import com.loopers.support.linesAt
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.wiremock.spring.InjectWireMock
import java.time.Duration
import java.time.Instant

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
        prepareOrderEventOutbox(orderId = 101, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutbox(orderId = 102, payload = PAYLOAD_NOT_A_MESSAGE_BODY, nextAttemptAt = CONFIRMED_AT)
        prepareOrderEventOutbox(orderId = 103, nextAttemptAt = CONFIRMED_AT)
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
        prepareOrderEventOutbox(orderId = 102, payload = PAYLOAD_NOT_A_MESSAGE_BODY, nextAttemptAt = CONFIRMED_AT)
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
        prepareOrderEventOutbox(orderId = 102, payload = PAYLOAD_NOT_A_MESSAGE_BODY, nextAttemptAt = CONFIRMED_AT)
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
        prepareOrderEventOutbox(orderId = 102, payload = payload, nextAttemptAt = CONFIRMED_AT)

        orderEventRelayer.publishDueEvents()

        val failedOutbox = committedOrderEventOutbox(orderId = 102)
        assertThat(failedOutbox.lastError).startsWith("tools.jackson.").doesNotContain("secret-value")
        val warnLine = kafkaAdapterLogs.linesAt(Level.WARN).single()
        assertThat(warnLine.formattedMessage).doesNotContain("secret-value")
        assertThat(warnLine.throwableProxy).isNull()
    }

    /** Confirms an order of one product at [CONFIRMED_AT] through the real TX B. A scene's clock stays pinned there until it moves it. */
    private fun confirmAnOrderLeavingARow(quantity: Int? = null) {
        prepareOrder(quantity = quantity)
        clock.pin(CONFIRMED_AT)
        confirmOrder(testPg)
    }
}
