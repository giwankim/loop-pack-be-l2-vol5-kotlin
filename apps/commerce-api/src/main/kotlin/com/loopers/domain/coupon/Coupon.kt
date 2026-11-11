package com.loopers.domain.coupon

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What one coupon campaign issued to one user (쿠폰 in `CONTEXT.md`). It holds the campaign's name and discount amount as they were at
 * issue, so it reads the same whatever happens to the campaign later, and nothing changes after issue.
 *
 * The unique key `(campaign_id, user_id)` is a backstop. The re-check under the campaign's row lock is what keeps a user to one coupon.
 *
 * The campaign and the user are referred to by ID only, so `scalar-foreign-keys.sql` adds their foreign keys (ADR 0015). The table sorts
 * before both tables it references, so Hibernate's drop by name order needs no line in `scalar-foreign-keys-drop.sql`.
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "UK_COUPON_ON_CAMPAIGN_ID_AND_USER_ID", columnNames = ["campaign_id", "user_id"]),
    ],
    indexes = [
        Index(name = "IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID", columnList = "user_id, issued_at DESC, id DESC"),
    ],
)
@SQLRestriction("deleted_at is null")
class Coupon(
    campaignId: Long,
    userId: Long,
    campaignName: String,
    discountAmount: Money,
    issuedAt: Instant,
) : BaseEntity() {
    /** The campaign that issued this coupon. */
    @Column(nullable = false, updatable = false)
    val campaignId: Long = campaignId

    /** The user who holds this coupon. */
    @Column(nullable = false, updatable = false)
    val userId: Long = userId

    /** The campaign's name at issue. */
    @Column(nullable = false, updatable = false, length = CouponCampaign.NAME_MAX_LENGTH)
    val campaignName: String = campaignName

    /** The campaign's discount amount at issue. */
    @Embedded
    @AttributeOverride(
        name = "amount",
        column = Column(name = "discount_amount", nullable = false, updatable = false, precision = 65, scale = 0),
    )
    val discountAmount: Money = discountAmount

    /** When the coupon was issued, in microseconds like every stored time, so the first answer and a later read agree (`BaseEntity`). */
    @Column(nullable = false, updatable = false)
    val issuedAt: Instant = issuedAt.truncatedTo(ChronoUnit.MICROS)
}
