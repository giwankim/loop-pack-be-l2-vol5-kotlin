package com.loopers.domain.coupon

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import org.hibernate.annotations.SQLRestriction

/**
 * Issues one fixed coupon up to its total quota (쿠폰 캠페인 in `CONTEXT.md`). Nothing deletes a campaign in this step, but as a root it supports
 * soft deletion, and a deleted campaign is a missing one (ADR 0016).
 *
 * The constructor checks its inputs itself, because a port or a test can build one without the request's Bean Validation.
 */
@Entity
@SQLRestriction("deleted_at is null")
class CouponCampaign(
    name: String,
    discountAmount: Money,
    totalQuota: Int,
) : BaseEntity() {
    /** The name as sent. It is not blank and has at most [NAME_MAX_LENGTH] characters. */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    val name: String = name

    /** The fixed amount each coupon promises. At least [MIN_DISCOUNT_AMOUNT]. */
    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "discount_amount", nullable = false, precision = 65, scale = 0))
    val discountAmount: Money = discountAmount

    /** How many coupons the campaign can issue. At least [MIN_TOTAL_QUOTA]. */
    @Column(nullable = false)
    val totalQuota: Int = totalQuota

    /** How many coupons the campaign has issued so far. */
    @Column(nullable = false)
    var issuedCount: Int = 0
        protected set

    /** False once issuing is suspended. */
    @Column(nullable = false)
    var issuable: Boolean = true
        protected set

    init {
        if (name.isBlank()) {
            throw InvalidNameException("쿠폰 캠페인 이름은 공백일 수 없습니다.")
        }

        if (name.length > NAME_MAX_LENGTH) {
            throw InvalidNameException("쿠폰 캠페인 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
        }

        if (discountAmount < MIN_DISCOUNT) {
            throw InvalidDiscountAmountException("할인액은 ${MIN_DISCOUNT_AMOUNT}원 이상이어야 합니다.")
        }

        if (totalQuota < MIN_TOTAL_QUOTA) {
            throw InvalidTotalQuotaException("발급 한도는 $MIN_TOTAL_QUOTA 이상이어야 합니다.")
        }
    }

    /**
     * Raises [issuedCount] by one. A suspended campaign refuses first, then an exhausted one, and either keeps its count. The caller holds
     * the campaign's row lock.
     */
    fun issue() {
        if (!issuable) {
            throw CouponCampaignSuspendedException()
        }

        if (issuedCount >= totalQuota) {
            throw CouponCampaignExhaustedException()
        }

        issuedCount += 1
    }

    /** Stops issuing for good. Suspending a suspended campaign changes nothing, and no method resumes one. Issued coupons are kept. */
    fun suspend() {
        issuable = false
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val MIN_DISCOUNT_AMOUNT = 1L
        const val MIN_TOTAL_QUOTA = 1L

        private val MIN_DISCOUNT = Money(MIN_DISCOUNT_AMOUNT)
    }
}
