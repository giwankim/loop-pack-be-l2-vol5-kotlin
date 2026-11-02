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
 * What the database itself keeps of the outbox on the real MySQL (ADR 0034 rules 6 and 7). The identifier is `IDENTITY`, so a
 * save is the `INSERT`, and a refused row is refused at the save. Each refusal names its constraint, so the other one can't
 * pass for it. [OrderEventOutboxImmutabilityTest] has the event columns, which need a commit.
 */
class OrderEventOutboxRepositoryTest(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
) : BaseRepositoryTest() {
    @Test
    fun `saving a second row with the same event ID is refused by the unique constraint`() {
        orderEventOutboxRepository.save(createOrderEventOutbox(eventId = "order-confirmed:v1:1"))

        val exception = assertThrows<DataIntegrityViolationException> {
            orderEventOutboxRepository.save(createOrderEventOutbox(eventId = "order-confirmed:v1:1"))
        }

        assertThat(exception).rootCause().hasMessageContaining("UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID")
    }

    @Test
    fun `saving a payload that is not valid JSON is refused by the check constraint`() {
        val exception = assertThrows<DataIntegrityViolationException> {
            orderEventOutboxRepository.save(createOrderEventOutbox(payload = "not json"))
        }

        assertThat(exception).rootCause().hasMessageContaining("CK_ORDER_EVENT_OUTBOX_PAYLOAD")
    }

    /** MySQL would round the instant to `…123457Z`. The row cuts it first, so what it holds is what a later read returns. */
    @Test
    fun `the next attempt time is cut to microseconds before it is stored`() {
        val saved = orderEventOutboxRepository.save(
            createOrderEventOutbox(nextAttemptAt = Instant.parse("2026-10-27T01:02:03.123456789Z")),
        )
        entityManager.flushAndClear()

        assertThat(saved.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        assertThat(orderEventOutboxRepository.findByEventId(saved.eventId)!!.nextAttemptAt)
            .isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
    }

    @Test
    fun `findByEventId and count don't see a deleted row`() {
        val saved = orderEventOutboxRepository.save(createOrderEventOutbox())
        orderEventOutboxRepository.save(saved.apply { delete() })
        entityManager.flushAndClear()

        assertThat(orderEventOutboxRepository.findByEventId(saved.eventId)).isNull()
        assertThat(orderEventOutboxRepository.count()).isZero()
    }
}
