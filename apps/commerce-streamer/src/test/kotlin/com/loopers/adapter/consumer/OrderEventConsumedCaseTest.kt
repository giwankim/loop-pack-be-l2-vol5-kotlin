package com.loopers.adapter.consumer

import ch.qos.logback.classic.Level
import com.loopers.support.orderEventValue
import com.loopers.support.test.BaseConsumerTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The consumed case handed to week 7's outbox (purchase activity design 8). It starts from the order of step 1's unpublished
 * case, which `OrderEventPublishGapTest` in commerce-api confirms without a record reaching the broker: order 1, confirmed at
 * `2026-10-27T01:02:03.123456Z`, two of product 1 and one of product 2. The scene publishes the record commerce-api's relay
 * would have published for it ([VALUE], items in ascending product ID as order-events design 2 writes them, keyed by the
 * order ID), and the streamer records it.
 *
 * The scene logs what it observed: where the broker put the record, the value, the group's commit, the listener's line and
 * the two tables' rows. Run it with
 * `./gradlew :apps:commerce-streamer:test --tests 'com.loopers.adapter.consumer.OrderEventConsumedCaseTest'`.
 * `docs/research/week6-purchase-activity-writing-material.md` keeps one run's values and the commit they came from. The PG
 * order ID and the transaction key are not this context's to know, so that record takes them from step 1's run: it stitches
 * two runs, not one run of both apps.
 *
 * The class names a topic of its own, so the record is the topic's first, at offset 0 of the partition the key chooses.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-consumed-case"])
class OrderEventConsumedCaseTest : BaseConsumerTest() {
    companion object {
        private const val ORDER_ID = 1L

        private const val EVENT_ID = "order-confirmed:v1:1"

        /** Step 1's pinned clock, and so the order's confirmation time. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")

        /**
         * The value of step 1's order confirmed event, as commerce-api's relay writes it. commerce-api's
         * `OrderEventPublishingTest` publishes the same order at the same instant and pins this form: these fields and no
         * others, the items in ascending product ID, the order ID as the key and no headers.
         */
        private val VALUE = orderEventValue(
            EVENT_ID,
            """{"orderId":1,"confirmedAt":"2026-10-27T01:02:03.123456Z",""" +
                """"items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}""",
        )
    }

    @Test
    fun `step 1's unpublished order confirmed event, published, becomes one processed confirmation and two activities`() {
        val record = publish(ORDER_ID, VALUE)

        awaitCommittedPast(record)
        assertThat(record.offset()).isZero()
        assertThat(storedConfirmations().map { Triple(it.eventId, it.orderId, it.confirmedAt) })
            .containsExactly(Triple(EVENT_ID, ORDER_ID, CONFIRMED_AT))
        assertThat(storedActivities().map { listOf(it.orderId, it.productId, it.quantity, it.confirmedAt) }).containsExactly(
            listOf(ORDER_ID, 1L, 2, CONFIRMED_AT),
            listOf(ORDER_ID, 2L, 1, CONFIRMED_AT),
        )
        assertThat(listenerLines(Level.INFO)).containsExactly(
            "Recorded the order confirmed event. eventId=$EVENT_ID, orderId=$ORDER_ID, " +
                "partition=${record.partition()}, offset=0, result=RECORDED",
        )
        logTheConsumedCase(record.topic(), record.partition(), record.offset())
    }

    /**
     * Logs the consumed case as week 7 starts from it: the record, the commit, the listener's line and the rows, as stored. The
     * class shares the listener's package, so its own line reaches the base's appender too, and the listener's is read first.
     */
    private fun logTheConsumedCase(topic: String, partition: Int, offset: Long) {
        val listenerLine = listenerLines(Level.INFO).single()

        log.info {
            """
            |W7 handoff, consumed case
            |topic=$topic, partition=$partition, offset=$offset, key=$ORDER_ID
            |value=$VALUE
            |committed offset of commerce-streamer on partition $partition=${committedOffset(partition)}
            |listener: $listenerLine
            |${storedConfirmationsLine()}
            |${storedActivitiesLine()}
            """.trimMargin()
        }
    }
}
