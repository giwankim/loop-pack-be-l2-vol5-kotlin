package com.loopers.application.order.required

import com.loopers.domain.order.createOrderEventOutbox
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant

/**
 * What a stored outbox row comes to on the real MySQL: the rows the database refuses (ADR 0034 rules 6 and 7), the next attempt
 * time a later read returns, and a deleted row being a missing row (ADR 0016). The identifier is `IDENTITY`, so a save is the
 * `INSERT`, and a refused row is refused at the save. Each refusal names its constraint, so the other one can't pass for it.
 * [OrderEventOutboxImmutabilityTest] has the event columns, which need a commit.
 */
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
}
