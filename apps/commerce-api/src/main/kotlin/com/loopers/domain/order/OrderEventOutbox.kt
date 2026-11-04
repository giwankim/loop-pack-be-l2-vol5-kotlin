package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One row is one event, and the table as a whole is the outbox. The name is the short one all the same (ADR 0035 rule 14).
 *
 * An order confirmed event left in the transaction that confirmed the order, waiting for the relay (ADR 0034). It is its own
 * root that refers to its order by ID only, and it takes plain values, because `domain` can't see the application's event.
 * The table serves the `order-events` topic alone, so it has no topic column.
 *
 * The event columns are the facts of the confirmation and never change: `updatable = false` keeps them out of Hibernate's
 * `UPDATE` (ADR 0034 rule 7). [payload] is the body of the wire contract and holds nothing the other columns hold (rule 5). The
 * two constraints are the last line of defence (rule 7). Normally the order lock and `ReconcileResult.Changed` keep a row
 * single, and the serializer writes valid JSON. `event_id` derives from `order_id`, so `order_id` needs no constraint of its own.
 *
 * The constructor checks the plain values itself, as its neighbours do. Whether [payload] is valid JSON is the check constraint's
 * to refuse. A rejected value fails TX B the way a failed serialization does (ADR 0034 rule 2).
 *
 * The state columns change only through the relay's writes, and the rules for them live here (ADR 0035 rules 3 and 9).
 * [nextAttemptAt] comes from the `Clock` bean and is the basis for the next attempt. `createdAt` follows the system clock, which
 * tests can't control, so it isn't used for that.
 *
 * `IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID` exists for correct behaviour rather than speed (ADR 0035 rule 10).
 * InnoDB locks the rows it scans, so without it a first `SKIP LOCKED` claim locks every row it passes and a second claim gets none.
 *
 * Nothing deletes rows (rows are kept for inspection), but as a root it supports soft deletion, and a deleted row is a missing
 * row (ADR 0016).
 */
@Entity
@Table(
    name = "order_event_outbox",
    uniqueConstraints = [
        UniqueConstraint(name = "UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID", columnNames = ["event_id"]),
    ],
    indexes = [
        Index(name = "IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID", columnList = "status, next_attempt_at, id"),
    ],
    check = [
        CheckConstraint(name = "CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID", constraint = "json_valid(payload)"),
    ],
)
@SQLRestriction("deleted_at is null")
class OrderEventOutbox(
    @Column(nullable = false, updatable = false)
    val eventId: String,

    @Column(nullable = false, updatable = false)
    val orderId: Long,

    @Column(nullable = false, updatable = false)
    val eventType: String,

    @Column(nullable = false, updatable = false)
    val schemaVersion: Int,

    @Column(nullable = false, updatable = false)
    val messageKey: String,

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    val payload: String,

    nextAttemptAt: Instant,
) : BaseEntity() {
    /**
     * A varchar, as design 2's table says, rather than the MySQL `enum` that Hibernate makes of the other entities' statuses. A
     * value step 2 adds then needs no schema change.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(16)")
    var status: OrderEventOutboxStatus = OrderEventOutboxStatus.PENDING
        protected set

    @Column(nullable = false)
    var attemptCount: Int = 0
        protected set

    /** Cut to the microseconds the column keeps, so that the instant held here is the one a later read returns, as in [Order]. */
    @Column(nullable = false)
    var nextAttemptAt: Instant = nextAttemptAt.truncatedTo(ChronoUnit.MICROS)
        protected set

    var publishedAt: Instant? = null
        protected set

    @Column(columnDefinition = "text")
    var lastError: String? = null
        protected set

    init {
        checkNotBlank(eventId, "아웃박스의 이벤트 ID는 공백일 수 없습니다.")

        if (orderId <= 0) {
            throw InvalidOrderEventOutboxException("아웃박스의 주문 ID는 양수여야 합니다.")
        }

        checkNotBlank(eventType, "아웃박스의 이벤트 유형은 공백일 수 없습니다.")

        if (schemaVersion <= 0) {
            throw InvalidOrderEventOutboxException("아웃박스의 스키마 버전은 양수여야 합니다.")
        }

        checkNotBlank(messageKey, "아웃박스의 메시지 키는 공백일 수 없습니다.")
        checkNotBlank(payload, "아웃박스의 payload는 공백일 수 없습니다.")
    }

    /**
     * Counts the attempt and leases the row: until the lease ends the row isn't due, which is what protects it during the send, after
     * the claim's lock has gone (ADR 0035 rule 2).
     */
    fun startPublishAttempt(attemptStartedAt: Instant) {
        ensurePending()

        attemptCount += 1
        nextAttemptAt = attemptStartedAt.plus(PUBLISH_ATTEMPT_LEASE).truncatedTo(ChronoUnit.MICROS)
    }

    /**
     * The broker confirmed a record of this event. That is a fact whatever the row's state, so it is never refused, and a row already
     * published keeps its first time (ADR 0035 rule 9).
     */
    fun markPublished(publishedAt: Instant) {
        if (status == OrderEventOutboxStatus.PUBLISHED) {
            return
        }

        status = OrderEventOutboxStatus.PUBLISHED
        this.publishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS)
    }

    /**
     * The attempt failed, for whatever reason (ADR 0035 rule 7). The row waits out a backoff, or stops at the last attempt. Only a relay
     * past its lease reaches this on a row that isn't pending, and that late result changes nothing, since throwing would end its round.
     */
    fun markFailed(failureMessage: String, failedAt: Instant) {
        if (status != OrderEventOutboxStatus.PENDING) {
            return
        }

        lastError = failureMessage

        if (attemptCount >= MAX_PUBLISH_ATTEMPTS) {
            status = OrderEventOutboxStatus.BLOCKED

            return
        }

        nextAttemptAt = failedAt.plus(retryBackoffAfter(attemptCount)).truncatedTo(ChronoUnit.MICROS)
    }

    private fun ensurePending() {
        if (status != OrderEventOutboxStatus.PENDING) {
            throw InvalidOrderEventOutboxException("발행 대기 중인 아웃박스만 발행을 시도할 수 있습니다.")
        }
    }

    private fun checkNotBlank(value: String, message: String) {
        if (value.isBlank()) {
            throw InvalidOrderEventOutboxException(message)
        }
    }

    companion object {
        /** How many rows one claim takes. A batch is done in about 40 ms (ADR 0035 rule 1). */
        const val CLAIM_BATCH_SIZE = 20

        /** Longer than the 41 s a batch can hold a row: 20 sends blocked for 1 s each, then a 21 s delivery timeout (design 13.4). */
        private val PUBLISH_ATTEMPT_LEASE: Duration = Duration.ofSeconds(60)

        /** A judgement: a full outage takes a row about 77 minutes to use these up (design 13.4). */
        private const val MAX_PUBLISH_ATTEMPTS = 20

        private val FIRST_RETRY_BACKOFF: Duration = Duration.ofSeconds(5)

        /** A judgement: it bounds a stuck row's WARN lines to about 288 a day (design 13.4). */
        private val MAX_RETRY_BACKOFF: Duration = Duration.ofMinutes(5)

        /**
         * The wait after the given attempt, the first being 1. A claim counts the attempt first, so 0 means a bug, and it waits like the
         * first rather than throwing, because a throw here would end the round.
         */
        private fun retryBackoffAfter(attemptCount: Int): Duration {
            return generateSequence(FIRST_RETRY_BACKOFF) { minOf(it.multipliedBy(2), MAX_RETRY_BACKOFF) }
                .elementAt((attemptCount - 1).coerceAtLeast(0))
        }
    }
}
