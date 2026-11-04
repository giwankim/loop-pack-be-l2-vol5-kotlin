package com.loopers.domain.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** What the relay's writes do to a row in each status (ADR 0035 rules 3 and 9). The times are literals, so the lease is too. */
class OrderEventOutboxStateTest {
    companion object {
        private val FIRST_DUE_AT: Instant = Instant.parse("2026-10-27T01:00:00Z")
        private val ATTEMPT_STARTED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
        private val PUBLISHED_AT: Instant = Instant.parse("2026-10-27T01:02:04.123456Z")
    }

    @Test
    fun `starting a publish attempt counts it and leases the row for 60 seconds`() {
        val outbox = createOrderEventOutbox(orderId = 7)

        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)

        assertThat(outbox.attemptCount).isEqualTo(1)
        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:03:03.123456Z"))
        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(outbox.publishedAt).isNull()
    }

    @Test
    fun `another attempt counts again and leases the row from its own start`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)

        outbox.startPublishAttempt(Instant.parse("2026-10-27T01:04:00Z"))

        assertThat(outbox.attemptCount).isEqualTo(2)
        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:05:00Z"))
    }

    /** MySQL would round the instant up. The row cuts it first, so what it holds is what a later read returns. */
    @Test
    fun `the lease is cut to microseconds`() {
        val outbox = createOrderEventOutbox(orderId = 7)

        outbox.startPublishAttempt(Instant.parse("2026-10-27T01:02:03.123456789Z"))

        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:03:03.123456Z"))
    }

    /** The claim's query filters on the status, so reaching this is a bug in the code rather than a state to handle. */
    @Test
    fun `starting a publish attempt on a published row is refused and changes nothing`() {
        val outbox = createOrderEventOutbox(orderId = 7, nextAttemptAt = FIRST_DUE_AT)
        outbox.markPublished(PUBLISHED_AT)

        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox.startPublishAttempt(ATTEMPT_STARTED_AT) }

        assertThat(exception.message).isEqualTo("발행 대기 중인 아웃박스만 발행을 시도할 수 있습니다.")
        assertThat(outbox.attemptCount).isZero()
        assertThat(outbox.nextAttemptAt).isEqualTo(FIRST_DUE_AT)
    }

    @Test
    fun `marking a pending row published records the time and keeps its attempts`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)

        outbox.markPublished(PUBLISHED_AT)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(outbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:04.123456Z"))
        assertThat(outbox.attemptCount).isEqualTo(1)
        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:03:03.123456Z"))
    }

    @Test
    fun `the published time is cut to microseconds`() {
        val outbox = createOrderEventOutbox(orderId = 7)

        outbox.markPublished(Instant.parse("2026-10-27T01:02:04.123456789Z"))

        assertThat(outbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:04.123456Z"))
    }

    /** A relay that outlived its lease reports a broker's confirmation too. It is true, so it isn't refused, and the first time stays. */
    @Test
    fun `marking a published row published again leaves it as it was`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        outbox.markPublished(PUBLISHED_AT)

        outbox.markPublished(Instant.parse("2026-10-27T01:09:00Z"))

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(outbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:04.123456Z"))
    }
}
