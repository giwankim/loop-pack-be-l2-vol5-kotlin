package com.loopers.adapter.consumer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest.Item
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.PurchaseActivity
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
import com.loopers.support.test.BaseConsumerTest
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The order events listener records what reaches the broker (ADR 0033 rules 3, 6 and 7, purchase activity design 5). A scene
 * publishes with the shared [KafkaTemplate], as commerce-api's relay does, and the listener container records the record on
 * its own thread.
 *
 * The listener acknowledges a record last, after the recording has committed, and `MANUAL_IMMEDIATE` commits the offset right
 * there. So a scene waits until the streamer's group has committed the offset past its records, and then reads the two tables:
 * nothing the record does comes after that. The group is the literal `commerce-streamer`, so a listener that joined another
 * group commits nowhere the scene looks.
 *
 * A record's value is JSON written by hand ([orderEventValue]), so a scene sends the exact wire form it names rather than the
 * form the streamer's own types would write.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-listener"])
class OrderEventsListenerTest(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseConsumerTest() {
    companion object {
        private const val GROUP = "commerce-streamer"

        /** How long a scene waits for the broker to take a record, and for the group to commit past it. */
        private val RECORD_WAIT: Duration = Duration.ofSeconds(10)
    }

    /** Logs from the consumer adapter, where the listener logs each record it records or skips. */
    private val listenerLogs = ListAppender<ILoggingEvent>()

    private val listenerLogger = LoggerFactory.getLogger("com.loopers.adapter.consumer") as Logger

    @BeforeEach
    fun captureListenerLogs() {
        listenerLogs.start()
        listenerLogger.addAppender(listenerLogs)
    }

    @AfterEach
    fun releaseListenerLogs() {
        listenerLogger.detachAppender(listenerLogs)
        listenerLogs.stop()
    }

    /**
     * commerce-api writes `confirmedAt` in `ISO_INSTANT`, which drops each all-zero group of three digits (order-events design
     * 2). The event has two items, so each becomes an activity of its own.
     */
    @ParameterizedTest
    @ValueSource(strings = ["2026-10-27T01:02:03.123456Z", "2026-10-27T01:02:03.100Z", "2026-10-27T01:02:03Z"])
    fun `an order confirmed record becomes one processed confirmation and one activity per item at its confirmation time`(
        confirmedAt: String,
    ) {
        val event = createPurchaseActivityRecordRequest(
            items = listOf(Item(productId = 1, quantity = 2), Item(productId = 2, quantity = 1)),
        )

        val record = publish(event.orderId, orderConfirmedValue(event, confirmedAt))

        awaitCommittedPast(record)
        assertThat(storedConfirmations()).singleElement().satisfies({ confirmation ->
            assertThat(confirmation.eventId).isEqualTo(event.eventId)
            assertThat(confirmation.orderId).isEqualTo(event.orderId)
            assertThat(confirmation.confirmedAt).isEqualTo(Instant.parse(confirmedAt))
        })
        assertThat(storedActivities().map { listOf(it.orderId, it.productId, it.quantity, it.confirmedAt) })
            .containsExactlyInAnyOrderElementsOf(
                event.items.map { listOf(event.orderId, it.productId, it.quantity, Instant.parse(confirmedAt)) },
            )
    }

    /** The same key twice lands on one partition, so the second record is read after the first is recorded and acknowledged. */
    @Test
    fun `the same record published twice leaves one set of rows and commits both offsets`() {
        val event = createPurchaseActivityRecordRequest()

        val first = publish(event.orderId, orderConfirmedValue(event))
        val second = publish(event.orderId, orderConfirmedValue(event))

        awaitCommittedPast(second)
        assertThat(second.offset()).isEqualTo(first.offset() + 1)
        assertThat(storedConfirmations().map { it.eventId }).containsExactly(event.eventId)
        assertThat(storedActivities().map { it.productId to it.quantity })
            .containsExactlyInAnyOrderElementsOf(event.items.map { it.productId to it.quantity })
        assertThat(infoLines()).containsExactly(
            "Recorded the order confirmed event. eventId=${event.eventId}, orderId=${event.orderId}, " +
                "partition=${first.partition()}, offset=${first.offset()}, result=RECORDED",
            "Recorded the order confirmed event. eventId=${event.eventId}, orderId=${event.orderId}, " +
                "partition=${second.partition()}, offset=${second.offset()}, result=ALREADY_RECORDED",
        )
    }

    /** The scene names the partitions, so the two records are read by two of the container's consumers, one per partition. */
    @Test
    fun `two records of different orders on different partitions both become rows`() {
        val firstEvent = createPurchaseActivityRecordRequest()
        val secondEvent = createPurchaseActivityRecordRequest()

        val first = publish(firstEvent.orderId, orderConfirmedValue(firstEvent), partition = 0)
        val second = publish(secondEvent.orderId, orderConfirmedValue(secondEvent), partition = 1)

        awaitCommittedPast(first)
        awaitCommittedPast(second)
        assertThat(storedConfirmations().map { it.eventId }).containsExactlyInAnyOrder(firstEvent.eventId, secondEvent.eventId)
        assertThat(storedActivities().map { Triple(it.orderId, it.productId, it.quantity) }).containsExactlyInAnyOrderElementsOf(
            listOf(firstEvent, secondEvent).flatMap { event ->
                event.items.map { Triple(event.orderId, it.productId, it.quantity) }
            },
        )
    }

    /**
     * `order-events` can carry other types of event, so one the streamer does not record is skipped, not refused (`CONTEXT.md`
     * 건너뛰다). Its payload is not an order confirmed event's, so a listener that read it as one would stop instead.
     */
    @Test
    fun `a record of an unknown event type is skipped, acknowledged with no row and one INFO line`() {
        val value = orderEventValue(eventId = "order-cancelled:v1:1", payload = """{"orderId":1}""", eventType = "OrderCancelled")

        val record = publish(orderId = 1, value = value)

        awaitCommittedPast(record)
        assertThat(storedConfirmations()).isEmpty()
        assertThat(storedActivities()).isEmpty()
        assertThat(infoLines()).containsExactly(
            "Skipped an order event of a type the streamer does not record. eventId=order-cancelled:v1:1, " +
                "eventType=OrderCancelled, partition=${record.partition()}, offset=${record.offset()}",
        )
    }

    /**
     * The value of an order events record: the envelope of order-events design 2 around [payload]. It is a JSON tree, not
     * bytes, because the shared template's `JacksonJsonSerializer` writes a byte array as a base64 string. A tree it writes as
     * the JSON the tree holds, compact and in the order written, as it writes commerce-api's message, and a string stays the
     * string written.
     */
    private fun orderEventValue(
        eventId: String,
        payload: String,
        eventType: String = OrderConfirmedPayload.EVENT_TYPE,
    ): JsonNode {
        return JsonMapper.shared().readTree(
            """{"eventId":"$eventId","eventType":"$eventType","schemaVersion":1,"payload":$payload}""",
        )
    }

    /**
     * [event] as an order confirmed record, with [confirmedAt] on the wire as written. By default it is the event's own time
     * as `Instant.toString` writes it, which is `ISO_INSTANT`, as commerce-api's mapper writes it.
     */
    private fun orderConfirmedValue(
        event: PurchaseActivityRecordRequest,
        confirmedAt: String = event.confirmedAt.toString(),
    ): JsonNode {
        val items = event.items.joinToString(",", "[", "]") { """{"productId":${it.productId},"quantity":${it.quantity}}""" }
        val payload = """{"orderId":${event.orderId},"confirmedAt":"$confirmedAt","items":$items}"""

        return orderEventValue(event.eventId, payload)
    }

    /**
     * Publishes [value] keyed by [orderId], as commerce-api's relay does, and returns where the broker put it. Without a
     * [partition] the key chooses it.
     */
    private fun publish(orderId: Long, value: JsonNode, partition: Int? = null): RecordMetadata {
        val record = ProducerRecord<Any, Any>(orderEventsProperties.topic, partition, orderId.toString(), value)

        return kafkaTemplate.send(record)
            .get(RECORD_WAIT.toSeconds(), TimeUnit.SECONDS)
            .recordMetadata
    }

    /** Waits until the streamer's group has committed [record]'s partition one past it: the record is done with. */
    private fun awaitCommittedPast(record: RecordMetadata) {
        await().atMost(RECORD_WAIT).untilAsserted {
            assertThat(committedOffset(record.partition())).isEqualTo(record.offset() + 1)
        }
    }

    private fun committedOffset(partition: Int): Long? {
        val brokers = kafkaProperties.bootstrapServers.joinToString(",")

        return KafkaTestUtils.getCurrentOffset(brokers, GROUP, orderEventsProperties.topic, partition)?.offset()
    }

    /**
     * The listener's INFO lines in this test. It logs before it acknowledges, so once the offset is committed every line of the
     * record is there. A line is added on the listener's thread, under the appender's own lock, so the list is read under it.
     */
    private fun infoLines(): List<String> {
        return synchronized(listenerLogs) {
            listenerLogs.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
        }
    }

    /** Every confirmation in the database. The listener's transaction has committed, so the read needs none of its own. */
    private fun storedConfirmations(): List<ProcessedOrderConfirmation> {
        return entityManager
            .createQuery("select c from ProcessedOrderConfirmation c order by c.id", ProcessedOrderConfirmation::class.java)
            .resultList
    }

    private fun storedActivities(): List<PurchaseActivity> {
        return entityManager
            .createQuery("select a from PurchaseActivity a order by a.id", PurchaseActivity::class.java)
            .resultList
    }
}
