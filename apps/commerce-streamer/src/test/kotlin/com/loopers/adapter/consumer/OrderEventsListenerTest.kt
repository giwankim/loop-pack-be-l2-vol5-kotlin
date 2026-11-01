package com.loopers.adapter.consumer

import ch.qos.logback.classic.Level
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest.Item
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
import com.loopers.support.orderConfirmedValue
import com.loopers.support.orderEventValue
import com.loopers.support.test.BaseConsumerTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.context.TestPropertySource
import java.time.Instant

/**
 * The order events listener records what reaches the broker (ADR 0033 rules 3, 6 and 7, purchase activity design 5). A scene
 * publishes with the shared `KafkaTemplate`, as commerce-api's relay does, and the listener container records the record on
 * its own thread.
 *
 * The listener acknowledges a record last, after the recording has committed, and `MANUAL_IMMEDIATE` commits the offset right
 * there. So a scene waits until the streamer's group has committed the offset past its records, and then reads the two tables:
 * nothing the record does comes after that. The group is the literal `commerce-streamer`, so a listener that joined another
 * group commits nowhere the scene looks.
 *
 * A record's value is JSON written by hand ([orderEventValue]), so a scene sends the exact wire form it names rather than the
 * form the streamer's own types would write. The records the listener refuses, which stop its container, are
 * [OrderEventsListenerStopTest]'s.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-listener"])
class OrderEventsListenerTest : BaseConsumerTest() {
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

    /**
     * The same key twice lands on one partition, so the second record is read after the first is recorded and acknowledged.
     * The second is a new record of the same event at the next offset, so the event is republished (`CONTEXT.md` 다시
     * 발행하다), not redelivered. [RedeliveryAfterCommitTest] has one record delivered twice at the same offset.
     */
    @Test
    fun `the same event published twice leaves one set of rows and commits both offsets`() {
        val event = createPurchaseActivityRecordRequest()

        val first = publish(event.orderId, orderConfirmedValue(event))
        val second = publish(event.orderId, orderConfirmedValue(event))

        awaitCommittedPast(second)
        assertThat(second.offset()).isEqualTo(first.offset() + 1)
        assertThat(storedConfirmations().map { it.eventId }).containsExactly(event.eventId)
        assertThat(storedActivities().map { it.productId to it.quantity })
            .containsExactlyInAnyOrderElementsOf(event.items.map { it.productId to it.quantity })
        assertThat(listenerLines(Level.INFO)).containsExactly(
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
        assertThat(listenerLines(Level.INFO)).containsExactly(
            "Skipped an order event of a type the streamer does not record. eventId=order-cancelled:v1:1, " +
                "eventType=OrderCancelled, partition=${record.partition()}, offset=${record.offset()}",
        )
    }
}
