package com.loopers.domain.purchaseactivity

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The record that one order confirmed event was recorded as purchase activity (`CONTEXT.md` 처리한 주문 확정). When an
 * event comes again, this is what tells the same fact from a conflict.
 *
 * It is an aggregate root of its own, not the parent of the purchase activities it was recorded with: it answers "was this
 * event recorded", while the activities are the facts week 9 reads, and the two differ in who reads them and how long they are
 * kept (ADR 0033 rule 2). One transaction writes both, and the application service keeps that promise, not a JPA cascade.
 *
 * Unique keys allow one per event ID and one per order. They are the backstop for two consumers recording the same event at
 * once, across a rebalance; the recorder reads first (ADR 0033 rule 3). The order ID is a value in this context, so there is
 * no foreign key to commerce-api's tables (ADR 0033 rule 1).
 *
 * The confirmation time is cut to the microseconds the column keeps, so the time held here is the one read back, and an
 * event that comes again compares against the stored time. Nothing deletes a confirmation, but as an aggregate root it is
 * soft-deleted, and queries treat a deleted one as missing (ADR 0016).
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID", columnNames = ["event_id"]),
        UniqueConstraint(name = "UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID", columnNames = ["order_id"]),
    ],
)
@SQLRestriction("deleted_at is null")
class ProcessedOrderConfirmation(
    @Column(nullable = false, updatable = false)
    val eventId: String,

    @Column(nullable = false, updatable = false)
    val orderId: Long,

    confirmedAt: Instant,
) : BaseEntity() {
    @Column(nullable = false, updatable = false)
    val confirmedAt: Instant = confirmedAt.truncatedTo(ChronoUnit.MICROS)
}
