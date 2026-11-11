package com.loopers.application.coupon.required

import com.loopers.domain.coupon.CouponCampaign
import org.springframework.data.repository.Repository

/** The coupon campaign store. Spring Data builds the implementation. */
interface CouponCampaignRepository : Repository<CouponCampaign, Long> {
    fun save(couponCampaign: CouponCampaign): CouponCampaign
}
