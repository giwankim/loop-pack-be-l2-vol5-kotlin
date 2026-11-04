package com.loopers.domain.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/** What the relay's writes do to a row in each status (ADR 0035 rules 3 and 9). The times are literals, so the lease is too. */
class OrderEventOutboxStateTest {
    companion object {
        private val FIRST_DUE_AT: Instant = Instant.parse("2026-10-27T01:00:00Z")
        private val ATTEMPT_STARTED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
        private val PUBLISHED_AT: Instant = Instant.parse("2026-10-27T01:02:04.123456Z")
        private val FAILED_AT: Instant = Instant.parse("2026-10-27T01:02:05.123456Z")
        private const val FAILURE_MESSAGE = "java.lang.IllegalStateException: the send failed"
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

    @Test
    fun `a failure on a pending row records the error and waits 5 seconds from the failure, keeping the row pending`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)

        outbox.markFailed(FAILURE_MESSAGE, FAILED_AT)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        assertThat(outbox.lastError).isEqualTo("java.lang.IllegalStateException: the send failed")
        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:10.123456Z"))
        assertThat(outbox.attemptCount).isEqualTo(1)
        assertThat(outbox.publishedAt).isNull()
    }

    /** Each wait is read off the failure of one more attempt, so the literals are the sequence as design 13.4 states it. */
    @Test
    fun `the wait doubles with each attempt from 5 seconds up to the 5 minute cap`() {
        val outbox = createOrderEventOutbox(orderId = 7)

        val waitsInSeconds = List(8) {
            outbox.startPublishAttempt(ATTEMPT_STARTED_AT)
            outbox.markFailed(FAILURE_MESSAGE, FAILED_AT)

            Duration.between(FAILED_AT, outbox.nextAttemptAt).toSeconds()
        }

        assertThat(waitsInSeconds).containsExactly(5, 10, 20, 40, 80, 160, 300, 300)
    }

    @Test
    fun `the next attempt's time is cut to microseconds`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)

        outbox.markFailed(FAILURE_MESSAGE, Instant.parse("2026-10-27T01:02:05.123456789Z"))

        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:10.123456Z"))
    }

    /** A relay past its lease can report a failure after another relay published the row. Throwing would end the round over nothing. */
    @Test
    fun `a failure on a published row leaves it as it was`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)
        outbox.markPublished(PUBLISHED_AT)

        outbox.markFailed(FAILURE_MESSAGE, FAILED_AT)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(outbox.lastError).isNull()
        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:03:03.123456Z"))
    }

    @Test
    fun `the 19th failure keeps the row pending and the 20th blocks it, with its error and no further wait`() {
        val outbox = createOrderEventOutbox(orderId = 7)

        failAttempts(outbox, times = 19)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PENDING)
        val nextAttemptAtBeforeTheLastFailure = FAILED_AT.plusSeconds(300)
        assertThat(outbox.nextAttemptAt).isEqualTo(nextAttemptAtBeforeTheLastFailure)

        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)
        val nextAttemptAtOfTheLastAttempt = outbox.nextAttemptAt
        outbox.markFailed("the last failure", FAILED_AT)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
        assertThat(outbox.attemptCount).isEqualTo(20)
        assertThat(outbox.lastError).isEqualTo("the last failure")
        assertThat(outbox.nextAttemptAt).isEqualTo(nextAttemptAtOfTheLastAttempt)
    }

    /** A claim always counts the attempt first, so only a bug gets here. It still mustn't throw, since that would end the round. */
    @Test
    fun `a failure on a row with no attempt yet waits like the first attempt`() {
        val outbox = createOrderEventOutbox(orderId = 7)

        outbox.markFailed(FAILURE_MESSAGE, FAILED_AT)

        assertThat(outbox.nextAttemptAt).isEqualTo(Instant.parse("2026-10-27T01:02:10.123456Z"))
    }

    /** It reaches `BLOCKED` the way a real row does, through 20 failures, so the test doesn't write the status itself. */
    @Test
    fun `a failure on a blocked row leaves it as it was`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        failAttempts(outbox, times = 20)
        val nextAttemptAtWhenBlocked = outbox.nextAttemptAt

        outbox.markFailed("a late failure", FAILED_AT.plusSeconds(60))

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
        assertThat(outbox.lastError).isEqualTo(FAILURE_MESSAGE)
        assertThat(outbox.nextAttemptAt).isEqualTo(nextAttemptAtWhenBlocked)
        assertThat(outbox.attemptCount).isEqualTo(20)
    }

    /** The claim's query filters on the status, so reaching this is a bug, as on a published row. */
    @Test
    fun `starting a publish attempt on a blocked row is refused and changes nothing`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        failAttempts(outbox, times = 20)
        val nextAttemptAtWhenBlocked = outbox.nextAttemptAt

        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox.startPublishAttempt(ATTEMPT_STARTED_AT) }

        assertThat(exception.message).isEqualTo("발행 대기 중인 아웃박스만 발행을 시도할 수 있습니다.")
        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.BLOCKED)
        assertThat(outbox.attemptCount).isEqualTo(20)
        assertThat(outbox.nextAttemptAt).isEqualTo(nextAttemptAtWhenBlocked)
    }

    /** A relay past its lease can report the broker's confirmation after the row blocked. A record is on the broker, so that is a fact. */
    @Test
    fun `marking a blocked row published publishes it and keeps its attempts and last error`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        failAttempts(outbox, times = 20)

        outbox.markPublished(PUBLISHED_AT)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(outbox.publishedAt).isEqualTo(Instant.parse("2026-10-27T01:02:04.123456Z"))
        assertThat(outbox.attemptCount).isEqualTo(20)
        assertThat(outbox.lastError).isEqualTo(FAILURE_MESSAGE)
    }

    /** They are the evidence of the retries that came before the publish (ADR 0035 rule 3). */
    @Test
    fun `a row that later publishes keeps its attempts and its last error`() {
        val outbox = createOrderEventOutbox(orderId = 7)
        failAttempts(outbox, times = 1)
        outbox.startPublishAttempt(ATTEMPT_STARTED_AT)

        outbox.markPublished(PUBLISHED_AT)

        assertThat(outbox.status).isEqualTo(OrderEventOutboxStatus.PUBLISHED)
        assertThat(outbox.attemptCount).isEqualTo(2)
        assertThat(outbox.lastError).isEqualTo(FAILURE_MESSAGE)
    }

    private fun failAttempts(outbox: OrderEventOutbox, times: Int) {
        repeat(times) {
            outbox.startPublishAttempt(ATTEMPT_STARTED_AT)
            outbox.markFailed(FAILURE_MESSAGE, FAILED_AT)
        }
    }
}
