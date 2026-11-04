package com.loopers.application.order.required

import com.loopers.domain.order.createOrderEventOutbox
import com.loopers.support.SqlCaptureConfig
import com.loopers.support.captureSql
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import java.time.Instant

/**
 * What a stored outbox row comes to on the real MySQL: the rows the database refuses (ADR 0034 rules 6 and 7), the next attempt
 * time a later read returns, and a deleted row being a missing row (ADR 0016). The identifier is `IDENTITY`, so a save is the
 * `INSERT`, and a refused row is refused at the save. Each refusal names its constraint, so the other one can't pass for it.
 * [OrderEventOutboxImmutabilityTest] has the event columns, which need a commit.
 */
@Import(SqlCaptureConfig::class)
class OrderEventOutboxRepositoryTest(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
) : BaseRepositoryTest() {
    @Test
    fun `saving a second row with the same event ID is refused by the unique constraint`() {
        val row = prepareOrderEventOutbox()

        val exception = assertThrows<DataIntegrityViolationException> {
            orderEventOutboxRepository.save(createOrderEventOutbox(orderId = row.orderId, eventId = row.eventId))
        }

        assertThat(exception).rootCause().hasMessageContaining("UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID")
    }

    @Test
    fun `saving a payload that is not valid JSON is refused by the check constraint`() {
        val order = prepareOrder()

        val exception = assertThrows<DataIntegrityViolationException> {
            orderEventOutboxRepository.save(createOrderEventOutbox(orderId = order.id, payload = "not json"))
        }

        assertThat(exception).rootCause().hasMessageContaining("CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID")
    }

    /** MySQL would round the instant to `…123457Z`. The row cuts it first, so what it holds is what a later read returns. */
    @Test
    fun `the next attempt time is cut to microseconds before it is stored`() {
        val saved = prepareOrderEventOutbox(nextAttemptAt = Instant.parse("2026-10-27T01:02:03.123456789Z"))
        entityManager.flushAndClear()

        assertThat(saved.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        assertThat(orderEventOutboxRepository.findByEventId(saved.eventId)!!.nextAttemptAt)
            .isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
    }

    @Test
    fun `findByEventId and count don't see a deleted row`() {
        val saved = prepareOrderEventOutbox()
        deleteOrderEventOutbox()
        entityManager.flushAndClear()

        assertThat(orderEventOutboxRepository.findByEventId(saved.eventId)).isNull()
        assertThat(orderEventOutboxRepository.count()).isZero()
    }

    /**
     * The published and blocked rows' next attempt times have passed, so only their statuses keep them out. The leases are 60 s, so the row
     * started 59 s ago is still protected, and the one started 60 s ago has just come due again.
     */
    @Test
    fun `a row under a live lease, a published row and a blocked row are not due, and a row whose lease has ended is`() {
        val now = Instant.parse("2026-10-27T01:02:03Z")
        val pastDueAt = now.minusSeconds(120)
        prepareOrderEventOutbox(orderId = 1, nextAttemptAt = pastDueAt)
        prepareOrderEventOutbox(orderId = 2, nextAttemptAt = pastDueAt)
        startPublishAttempt(now.minusSeconds(59))
        prepareOrderEventOutbox(orderId = 3, nextAttemptAt = pastDueAt)
        markPublished(pastDueAt)
        prepareOrderEventOutbox(orderId = 4, nextAttemptAt = pastDueAt)
        startPublishAttempt(now.minusSeconds(60))
        prepareOrderEventOutbox(orderId = 5, nextAttemptAt = pastDueAt)
        failUntilBlocked(pastDueAt)
        assertThat(orderEventOutbox.nextAttemptAt).isBefore(now)
        entityManager.flushAndClear()

        val dueOutboxes = orderEventOutboxRepository.findDueForUpdate(now, PageRequest.of(0, 20))

        assertThat(dueOutboxes.map { it.orderId }).containsExactly(1L, 4L)
    }

    /**
     * The IDs are generated in saving order, so the two rows due at the same instant show the tie break against the saving order of the
     * rest. It refuses a reversed tie break, but not a missing one: without `o.id` in the query, MySQL still returned tied rows in ID order.
     */
    @Test
    fun `the oldest next attempt time comes first, and the ID breaks a tie`() {
        val firstDueAt = Instant.parse("2026-10-27T01:02:03Z")
        prepareOrderEventOutbox(orderId = 1, nextAttemptAt = firstDueAt.plusSeconds(2))
        prepareOrderEventOutbox(orderId = 2, nextAttemptAt = firstDueAt.plusSeconds(1))
        prepareOrderEventOutbox(orderId = 3, nextAttemptAt = firstDueAt.plusSeconds(1))
        prepareOrderEventOutbox(orderId = 4, nextAttemptAt = firstDueAt)
        entityManager.flushAndClear()

        val dueOutboxes = orderEventOutboxRepository.findDueForUpdate(firstDueAt.plusSeconds(2), PageRequest.of(0, 20))

        assertThat(dueOutboxes.map { it.orderId }).containsExactly(4L, 2L, 3L, 1L)
    }

    /** The columns and their order are what lets a second claim get the next rows (ADR 0035 rule 10). `OrderEventOutboxClaimTest` has its behaviour. */
    @Test
    fun `the index the claim depends on covers the status, the next attempt time and the ID in that order`() {
        val indexedColumns = entityManager.createNativeQuery(
            "select column_name from information_schema.statistics where table_schema = database() " +
                "and table_name = 'order_event_outbox' " +
                "and index_name = 'IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID' order by seq_in_index",
        ).resultList

        assertThat(indexedColumns).containsExactly("status", "next_attempt_at", "id")
    }

    /**
     * The hint is a number in a string, and nothing refuses a value Hibernate ignores, so only the statement it sent can show the
     * claim skips locked rows. ADR 0035's measurements ran on bare SQL and left this unverified. Hibernate names the locked alias
     * (`for update of`), which on this one table locks what a bare `for update` locks.
     */
    @Test
    fun `the due rows query goes out ending in for update skip locked`() {
        val capturedSql = captureSql {
            orderEventOutboxRepository.findDueForUpdate(Instant.parse("2026-10-27T01:02:03Z"), PageRequest.of(0, 20))
        }

        assertThat(capturedSql.statements).hasSize(1)
        assertThat(capturedSql.statements.single()).matches(".* for update of \\w+ skip locked")
    }
}
