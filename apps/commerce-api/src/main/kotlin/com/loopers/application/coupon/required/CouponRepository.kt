package com.loopers.application.coupon.required

import com.loopers.domain.coupon.Coupon
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.repository.Repository

/** The coupon store. Spring Data builds the implementation. */
interface CouponRepository : Repository<Coupon, Long> {
    fun save(coupon: Coupon): Coupon

    fun findByCampaignIdAndUserId(campaignId: Long, userId: Long): Coupon?

    /**
     * A slice of [userId]'s coupons, newest issue first and the larger ID first among equal issue times. The name states the order,
     * so [pageable] carries only the slice's position and size. The coupon table alone answers it, and its index
     * `IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID` already holds one user's rows in this order, so the plan needs no sort step.
     *
     * A [Slice] reads `size + 1` rows to decide `hasNext` and counts nothing; a `Page` would add a count query without a word.
     */
    fun findAllByUserIdOrderByIssuedAtDescIdDesc(userId: Long, pageable: Pageable): Slice<Coupon>
}
