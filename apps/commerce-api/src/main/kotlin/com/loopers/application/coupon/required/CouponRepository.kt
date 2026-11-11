package com.loopers.application.coupon.required

import com.loopers.domain.coupon.Coupon
import org.springframework.data.repository.Repository

/** The coupon store. Spring Data builds the implementation. */
interface CouponRepository : Repository<Coupon, Long> {
    fun save(coupon: Coupon): Coupon

    fun findByCampaignIdAndUserId(campaignId: Long, userId: Long): Coupon?
}
