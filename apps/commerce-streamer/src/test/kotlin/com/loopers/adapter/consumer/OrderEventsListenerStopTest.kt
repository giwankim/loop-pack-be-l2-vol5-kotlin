package com.loopers.adapter.consumer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest.Item
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
import com.loopers.support.TestOrderEventsTopicConfig
import com.loopers.support.orderConfirmedValue
import com.loopers.support.orderEventValue
import com.loopers.support.test.BaseConsumerTest
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.converter.ConversionException
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.TimeUnit

/**
 * An unprocessable event stops the order events listener's container (ADR 0033 rule 5, purchase activity design 6). The
 * listener refuses the record with one ERROR line and rethrows, and the factory's `CommonContainerStoppingErrorHandler`
 * stops the container: all three consumers, since a record listener's error handler is given the parent container. The
 * record is never acknowledged, so the group stays on it. A value that is not JSON stops the container too, but it never
 * reaches the listener: the factory's converter throws first, and only spring-kafka's own ERROR line says why.
 *
 * Each scene waits until the container has stopped and every one of its consumers has closed, so a stop that left a consumer
 * running fails the wait. It then reads that the container did not stop as expected: `isInExpectedState` is false only after
 * an abnormal stop, which is what the reason `ABNORMAL` of each consumer's `ConsumerStoppedEvent` says, and reading it needs
 * no event listener in the context.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-listener-stop"])
class OrderEventsListenerStopTest(
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseConsumerTest() {
    companion object {
        /** The items of the event a conflict scene records first. */
        private val RECORDED_ITEMS = listOf(Item(productId = 1, quantity = 2), Item(productId = 2, quantity = 1))

        /** The same products as [RECORDED_ITEMS], with product 1's quantity changed. */
        private val CONFLICTING_ITEMS = listOf(Item(productId = 1, quantity = 3), Item(productId = 2, quantity = 1))

        /** As long as the base waits for the broker to take a record. */
        private const val PUBLISH_WAIT_SECONDS = 10L
    }

    /** Logs from the listener's consumers, where spring-kafka logs what the error handler threw when it stopped the container. */
    private val containerLogs = ListAppender<ILoggingEvent>()

    private val containerLogger = LoggerFactory.getLogger(KafkaMessageListenerContainer::class.java) as Logger

    @BeforeEach
    fun captureContainerLogs() {
        containerLogs.start()
        containerLogger.addAppender(containerLogs)
    }

    @AfterEach
    fun releaseContainerLogs() {
        containerLogger.detachAppender(containerLogs)
        containerLogs.stop()
    }

    /**
     * The same event ID with another quantity is a conflict, not a redelivery (`CONTEXT.md` 충돌). The recorded fact stays, the
     * group stays on the conflicting record, and the ERROR line carries the recorded and the incoming quantities, the evidence a
     * person decides from (design 6).
     */
    @Test
    fun `a record conflicting with the recorded one stops the container, commits no offset and leaves the recorded rows`() {
        val event = createPurchaseActivityRecordRequest(items = RECORDED_ITEMS)

        val conflicting = publishAConflictWith(event)

        val container = awaitStopped()
        assertThat(container.isInExpectedState).isFalse()
        assertThat(committedOffset(conflicting.partition())).isEqualTo(conflicting.offset())
        assertOnlyTheRowsOf(event)
        assertThat(listenerLines(Level.ERROR)).containsExactly(refusal(conflicting, event, conflictCause(event)))
    }

    /**
     * A restart does not get past an unprocessable event (design 6). The group is still on the conflicting record, so the
     * restarted container reads it again and stops again, and the second ERROR line is the first, partition and offset
     * included. Moving on takes a person: fixing the recorded rows, or moving the group past the record.
     */
    @Test
    fun `restarting a container stopped on a conflict reads the same record again and stops again`() {
        val event = createPurchaseActivityRecordRequest(items = RECORDED_ITEMS)
        val conflicting = publishAConflictWith(event)
        val container = awaitStopped()

        container.start()

        awaitStopped()
        assertThat(container.isInExpectedState).isFalse()
        assertThat(committedOffset(conflicting.partition())).isEqualTo(conflicting.offset())
        assertOnlyTheRowsOf(event)
        val refusal = refusal(conflicting, event, conflictCause(event))
        assertThat(listenerLines(Level.ERROR)).containsExactly(refusal, refusal)
    }

    /**
     * A payload without its `orderId` cannot be read as an order confirmed event's. The ERROR line still names the order: by
     * the record's key, which commerce-api sets to the order ID.
     */
    @Test
    fun `a record whose payload has no orderId stops the container and commits no offset`() {
        val event = createPurchaseActivityRecordRequest()
        val payload = """{"confirmedAt":"${event.confirmedAt}","items":[{"productId":1,"quantity":2}]}"""
        val committedBefore = committedOffsets()

        val record = publish(event.orderId, orderEventValue(event.eventId, payload))

        val container = awaitStopped()
        assertThat(container.isInExpectedState).isFalse()
        assertThat(committedOffsets()).isEqualTo(committedBefore)
        assertNoRows()
        assertThat(listenerLines(Level.ERROR)).containsExactly(
            refusal(
                record,
                event,
                "com.loopers.adapter.consumer.MalformedOrderEventException: The payload of order confirmed event " +
                    "${event.eventId} cannot be read: Missing required creator property 'orderId' (index 0)",
            ),
        )
    }

    /** The payload may mean something else at another schema version, so it is not read as version 1. */
    @Test
    fun `an order confirmed record at schema version 2 stops the container and commits no offset`() {
        val event = createPurchaseActivityRecordRequest()
        val committedBefore = committedOffsets()

        val record = publish(event.orderId, orderConfirmedValue(event, schemaVersion = 2))

        val container = awaitStopped()
        assertThat(container.isInExpectedState).isFalse()
        assertThat(committedOffsets()).isEqualTo(committedBefore)
        assertNoRows()
        assertThat(listenerLines(Level.ERROR)).containsExactly(
            refusal(
                record,
                event,
                "com.loopers.adapter.consumer.UnsupportedOrderEventException: Order confirmed event ${event.eventId} " +
                    "is at schema version 2, and the streamer reads version 1.",
            ),
        )
    }

    /**
     * An event without items reads as a payload, and the recording request's validation refuses it before anything is read or
     * written. It is the one unprocessable event that is not an `UnprocessableOrderEventException`.
     */
    @Test
    fun `an order confirmed record without items stops the container and commits no offset`() {
        val event = createPurchaseActivityRecordRequest(items = emptyList())
        val committedBefore = committedOffsets()

        val record = publish(event.orderId, orderConfirmedValue(event))

        val container = awaitStopped()
        assertThat(container.isInExpectedState).isFalse()
        assertThat(committedOffsets()).isEqualTo(committedBefore)
        assertNoRows()
        assertThat(listenerLines(Level.ERROR)).containsExactly(
            refusal(
                record,
                event,
                "jakarta.validation.ConstraintViolationException: record.request.items: " +
                    "An order confirmed event must have at least one item.",
            ),
        )
    }

    /**
     * A value that is not JSON cannot be read as an [OrderEventMessage]. The factory's converter throws spring-kafka's
     * `ConversionException` before the listener method is called, so the listener never sees the record and logs no refusal
     * (design 5). The error handler stops the container all the same, and spring-kafka's own ERROR line, which it logs on every
     * stop, is the one that names the conversion.
     */
    @Test
    fun `a record whose value is not JSON stops the container before the listener and commits no offset`() {
        val committedBefore = committedOffsets()

        publishBytes(orderId = 1, value = "not json".toByteArray())

        val container = awaitStopped()
        assertThat(container.isInExpectedState).isFalse()
        assertThat(committedOffsets()).isEqualTo(committedBefore)
        assertNoRows()
        assertThat(listenerLines(Level.ERROR)).isEmpty()
        val stop = containerErrors().single()
        assertThat(stop.formattedMessage).isEqualTo("Error handler threw an exception")
        assertThat(causeChainOf(stop.throwableProxy)).startsWith(
            KafkaException::class.java.name,
            ListenerExecutionFailedException::class.java.name,
            ConversionException::class.java.name,
        )
    }

    /**
     * Publishes [event] and waits until it is recorded, then publishes the same event ID with [CONFLICTING_ITEMS], and returns
     * where the broker put that record. The key is the same, so the two land on one partition, one after the other.
     */
    private fun publishAConflictWith(event: PurchaseActivityRecordRequest): RecordMetadata {
        val recorded = publish(event.orderId, orderConfirmedValue(event))
        awaitCommittedPast(recorded)
        val conflict = event.copy(items = CONFLICTING_ITEMS)

        return publish(conflict.orderId, orderConfirmedValue(conflict))
    }

    /** What the listener rethrows on [publishAConflictWith]'s conflicting record. */
    private fun conflictCause(event: PurchaseActivityRecordRequest): String {
        return "com.loopers.application.purchaseactivity.provided.PurchaseActivityConflictException: Order confirmed event " +
            "${event.eventId} of order ${event.orderId} conflicts with the recorded one: " +
            "items (product=quantity) recorded {1=2, 2=1}, incoming {1=3, 2=1}."
    }

    /**
     * Publishes [value] as it is, keyed by [orderId], and returns where the broker put it. The base's shared template would
     * write a byte array as a base64 JSON string, so this producer writes the bytes unchanged.
     */
    private fun publishBytes(orderId: Long, value: ByteArray): RecordMetadata {
        val producer = KafkaProducer(kafkaProperties.buildProducerProperties(), StringSerializer(), ByteArraySerializer())

        return producer.use {
            it.send(ProducerRecord(orderEventsProperties.topic, orderId.toString(), value))
                .get(PUBLISH_WAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * The ERROR lines the listener's consumers logged in this test. A line is added on a consumer's thread, under the appender's
     * own lock, so the list is read under it.
     */
    private fun containerErrors(): List<ILoggingEvent> {
        return synchronized(containerLogs) {
            containerLogs.list.filter { it.level == Level.ERROR }
        }
    }

    /** The class names of [throwable] and its causes, outermost first. */
    private fun causeChainOf(throwable: IThrowableProxy?): List<String> {
        return generateSequence(throwable) { it.cause }.map { it.className }.toList()
    }

    /** The streamer's group's committed offset on each partition of the class's topic, in partition order. */
    private fun committedOffsets(): List<Long?> {
        return (0 until TestOrderEventsTopicConfig.PARTITIONS).map { committedOffset(it) }
    }

    /** The ERROR line the listener logs when it refuses [record] of [event], ending in the [cause] it rethrows. */
    private fun refusal(record: RecordMetadata, event: PurchaseActivityRecordRequest, cause: String): String {
        return "Refused an order confirmed event that cannot be processed. eventId=${event.eventId}, " +
            "orderId=${event.orderId}, partition=${record.partition()}, offset=${record.offset()}, cause=$cause"
    }

    private fun assertNoRows() {
        assertThat(storedConfirmations()).isEmpty()
        assertThat(storedActivities()).isEmpty()
    }
}
