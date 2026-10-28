package com.loopers.domain.purchaseactivity

import com.loopers.domain.BaseEntity
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The fact that one confirmed order bought one product in some quantity at its confirmation time (`CONTEXT.md` 구매 활동).
 * It is made only from an order confirmed event, never from commerce-api's tables, and week 9's ranking reads it.
 *
 * It is an aggregate root of its own, not a child of the [ProcessedOrderConfirmation] it was recorded with (ADR 0033 rule 2).
 * The confirmation time is kept here too, so week 9's time window reads it without going through that record.
 *
 * A unique key allows one per order and product, so an event that comes again still leaves one. A CHECK keeps the quantity
 * positive, and the constructor refuses a non-positive one before the database sees it, with the `IllegalArgumentException`
 * purchase activity design 6 lists; the recording request's validation refuses it first. The order and product IDs are
 * values in this context, never resolved to an order or a product, so there are no foreign keys to commerce-api's tables
 * (ADR 0033 rule 1).
 *
 * The confirmation time is cut to the microseconds the column keeps, so the time held here is the one read back. Nothing
 * deletes an activity, but as an aggregate root it is soft-deleted, and queries treat a deleted one as missing (ADR 0016).
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "UK_PURCHASE_ACTIVITY_ON_ORDER_ID_AND_PRODUCT_ID", columnNames = ["order_id", "product_id"]),
    ],
    check = [CheckConstraint(name = "CK_PURCHASE_ACTIVITY_ON_QUANTITY_POSITIVE", constraint = "quantity > 0")],
)
@SQLRestriction("deleted_at is null")
class PurchaseActivity(
    @Column(nullable = false, updatable = false)
    val orderId: Long,

    @Column(nullable = false, updatable = false)
    val productId: Long,

    @Column(nullable = false, updatable = false)
    val quantity: Int,

    confirmedAt: Instant,
) : BaseEntity() {
    @Column(nullable = false, updatable = false)
    val confirmedAt: Instant = confirmedAt.truncatedTo(ChronoUnit.MICROS)

    init {
        require(quantity > 0) { "A purchase activity's quantity must be positive, but was $quantity." }
    }
}
