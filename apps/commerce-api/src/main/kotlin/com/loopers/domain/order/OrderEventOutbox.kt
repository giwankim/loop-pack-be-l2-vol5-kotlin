package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
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
 * The state columns exist from the start and hold only their first values here: step 2's relay adds what changes them (rule 8).
 * [nextAttemptAt] comes from the `Clock` bean and is the basis for the next attempt. `createdAt` follows the system clock, which
 * tests can't control, so it isn't used for that.
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

    private fun checkNotBlank(value: String, message: String) {
        if (value.isBlank()) {
            throw InvalidOrderEventOutboxException(message)
        }
    }
}
