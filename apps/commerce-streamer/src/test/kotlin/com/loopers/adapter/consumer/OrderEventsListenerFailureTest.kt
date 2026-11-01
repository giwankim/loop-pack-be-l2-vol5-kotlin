package com.loopers.adapter.consumer

import ch.qos.logback.classic.Level
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest.Item
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
import com.loopers.support.orderConfirmedValue
import com.loopers.support.test.BaseConsumerTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.producer.RecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.context.TestPropertySource
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * A transient database failure after a partial write stops the order events listener's container, and a restart records the
 * record once the failure is gone (ADR 0033 rule 5, purchase activity design 6, `CONTEXT.md` 멈추다). The failure is real: a
 * second JDBC connection, drawn from the same pool as the listener's, holds an uncommitted `purchase_activity` row for the
 * event's second item. The listener's transaction inserts the processed order confirmation and the first item, then waits for
 * that row's unique key, and the session's `innodb_lock_wait_timeout` of 3 s (ADR 0018 rule 7) ends the wait with MySQL 1205,
 * `Lock wait timeout exceeded`. The failure line quotes the statement that failed, an activity insert, which is how the scene
 * sees that the write was partial: the recorder saves the confirmation before the activities, so the confirmation was in. The
 * message names no key, so that the first item was in too follows from the insert order and the held key, not from anything
 * the scene reads. Hibernate reads 1205 as its `LockTimeoutException`, a `LockAcquisitionException`, and Spring translates
 * that into [CannotAcquireLockException]. It
 * is not a `DataIntegrityViolationException`, so the listener's `recordSettlingARace` does not call the recorder again; the
 * exception leaves the listener, Spring rolls the whole transaction back, the listener logs its failure line, and the
 * factory's error handler stops the container.
 *
 * A normal event is recorded on the same partition first, so the failing record's offset n is the group's committed offset
 * before the stop and during it, and n+1 after the restart. This is the step's one restart that gets past the record: the stop
 * scenes restart on an unprocessable event and stop again. The wait is asserted only from below: the container cannot stop
 * before the lock wait has run out, and the base's stop wait bounds it from above.
 *
 * The blocking connection is rolled back and closed on every path, in the scene and again in `@AfterEach`, so no lock outlives
 * it: the base's `TRUNCATE` would otherwise wait on it.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-listener-failure"])
class OrderEventsListenerFailureTest(
    private val dataSource: DataSource,
) : BaseConsumerTest() {
    companion object {
        /** Both records go here, so one partition's committed offset tells the whole story. */
        private const val PARTITION = 0

        /** Two items, so the failure comes after the confirmation and the first item are written. */
        private val ITEMS = listOf(Item(productId = 1, quantity = 2), Item(productId = 2, quantity = 1))

        /** `innodb_lock_wait_timeout`, which every connection of the pool sets as a session variable (ADR 0018 rule 7). */
        private val LOCK_WAIT: Duration = Duration.ofSeconds(3)

        /** What Spring translates Hibernate's `LockTimeoutException` into; the failure line names it. */
        private val FAILURE_TYPE: String = CannotAcquireLockException::class.java.name

        /** MySQL's message for error 1205, `ER_LOCK_WAIT_TIMEOUT`. */
        private const val LOCK_WAIT_TIMEOUT_MESSAGE = "Lock wait timeout exceeded; try restarting transaction"

        /**
         * The statement that failed, as Hibernate's message quotes it. The recorder inserts the confirmation before the items,
         * so an activity insert failing means the confirmation was in. The message names no key, so it cannot say which item.
         */
        private const val FAILED_STATEMENT = "[insert into purchase_activity "
    }

    /** The connection holding the second item's row, from [holdTheRowOf] until [releaseTheHeldRow]. */
    private var blockingConnection: Connection? = null

    @AfterEach
    fun releaseTheHeldRowOnEveryPath() {
        releaseTheHeldRow()
    }

    @Test
    fun `a lock wait timeout after a partial write rolls back, stops the container on the record, and a restart records it`() {
        val recorded = createPurchaseActivityRecordRequest()
        val normal = publish(recorded.orderId, orderConfirmedValue(recorded), PARTITION)
        awaitCommittedPast(normal)
        val event = createPurchaseActivityRecordRequest(items = ITEMS)
        holdTheRowOf(event, event.items[1])
        val committedBefore = committedOffset(PARTITION)
        val published = Instant.now()

        val failing = publish(event.orderId, orderConfirmedValue(event), PARTITION)

        val container = awaitStopped()
        val stoppedAfter = Duration.between(published, Instant.now())
        assertThat(container.isInExpectedState).isFalse()
        assertThat(failing.offset()).isEqualTo(committedBefore)
        val committedDuring = committedOffset(PARTITION)
        assertThat(committedDuring).isEqualTo(failing.offset())
        assertOnlyTheRowsOf(recorded)
        val confirmationsDuring = storedConfirmationsLine()
        val activitiesDuring = storedActivitiesLine()
        val failure = listenerLines(Level.ERROR).single()
        assertThat(failure).startsWith(failureLine(failing, event))
        assertThat(failure).contains(LOCK_WAIT_TIMEOUT_MESSAGE)
        assertThat(failure).contains(FAILED_STATEMENT)
        assertThat(stoppedAfter).isGreaterThanOrEqualTo(LOCK_WAIT)

        releaseTheHeldRow()
        container.start()

        awaitCommittedPast(failing)
        assertThat(committedOffset(PARTITION)).isEqualTo(failing.offset() + 1)
        assertOnlyTheRowsOf(recorded, event)
        assertThat(listenerLines(Level.INFO)).containsExactly(recordedLine(normal, recorded), recordedLine(failing, event))
        assertThat(listenerLines(Level.ERROR)).containsExactly(failure)
        logTheScene(
            normal = normal,
            failing = failing,
            committedBefore = committedBefore,
            committedDuring = committedDuring,
            stoppedAfter = stoppedAfter,
            failure = failure,
            confirmationsDuring = confirmationsDuring,
            activitiesDuring = activitiesDuring,
        )
    }

    /**
     * Opens a transaction on a second connection from the pool and inserts [item]'s purchase activity of [event] without
     * committing. The row's unique key on the order and the product is what the listener's own insert of that item waits for.
     */
    private fun holdTheRowOf(event: PurchaseActivityRecordRequest, item: Item) {
        val connection = dataSource.connection.apply { autoCommit = false }
        blockingConnection = connection
        val now = Instant.now().atOffset(ZoneOffset.UTC).toLocalDateTime()

        connection.prepareStatement(
            "insert into purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at) " +
                "values (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, event.orderId)
            statement.setLong(2, item.productId)
            statement.setInt(3, item.quantity)
            statement.setObject(4, event.confirmedAt.atOffset(ZoneOffset.UTC).toLocalDateTime())
            statement.setObject(5, now)
            statement.setObject(6, now)
            statement.executeUpdate()
        }
    }

    /** Rolls the held row back and returns its connection to the pool. Nothing happens once it is released. */
    private fun releaseTheHeldRow() {
        val connection = blockingConnection ?: return
        blockingConnection = null

        connection.use { it.rollback() }
    }

    /** The ERROR line the listener logs when it cannot record [record] of [event], up to the cause's message. */
    private fun failureLine(record: RecordMetadata, event: PurchaseActivityRecordRequest): String {
        return "Could not record an order confirmed event; the consumer stops until it is restarted. eventId=${event.eventId}, " +
            "orderId=${event.orderId}, partition=${record.partition()}, offset=${record.offset()}, cause=$FAILURE_TYPE: "
    }

    /** The INFO line the listener logs when it records [record] of [event] as new. */
    private fun recordedLine(record: RecordMetadata, event: PurchaseActivityRecordRequest): String {
        return "Recorded the order confirmed event. eventId=${event.eventId}, orderId=${event.orderId}, " +
            "partition=${record.partition()}, offset=${record.offset()}, result=RECORDED"
    }

    /**
     * Logs what the scene observed, for the writing material: the two records, the committed offset before the stop, during it
     * and after the restart, how long after the failing record was published the container had stopped, the failure line and
     * the rows during the stop and after the restart. The class shares the listener's package, so this line reaches the base's
     * appender too, and the listener's lines are asserted first.
     */
    private fun logTheScene(
        normal: RecordMetadata,
        failing: RecordMetadata,
        committedBefore: Long?,
        committedDuring: Long?,
        stoppedAfter: Duration,
        failure: String,
        confirmationsDuring: String,
        activitiesDuring: String,
    ) {
        val committedAfter = committedOffset(PARTITION)

        log.info {
            """
            |Real listener failure, a lock wait timeout after a partial write
            |topic=${failing.topic()}, partition=$PARTITION, normal record offset=${normal.offset()}, failing record offset=${failing.offset()}
            |committed offset of commerce-streamer on partition $PARTITION: before=$committedBefore, during the stop=$committedDuring, after the restart=$committedAfter
            |the container had stopped ${stoppedAfter.toMillis()} ms after the failing record was published (innodb_lock_wait_timeout=${LOCK_WAIT.toSeconds()})
            |failure line: $failure
            |rows during the stop
            |$confirmationsDuring
            |$activitiesDuring
            |rows after the restart
            |${storedConfirmationsLine()}
            |${storedActivitiesLine()}
            """.trimMargin()
        }
    }
}
