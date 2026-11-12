package com.loopers.adapter.webapi.v1.coupon

import com.loopers.domain.coupon.Coupon
import java.time.Instant

data class CouponResponse(
    val couponId: Long,
    val campaignId: Long,
    val campaignName: String,
    val discountAmount: Long,
    val issuedAt: Instant,
) {
    companion object {
        fun from(coupon: Coupon): CouponResponse {
            return CouponResponse(
                couponId = coupon.id,
                campaignId = coupon.campaignId,
                campaignName = coupon.campaignName,
                discountAmount = coupon.discountAmount.amount.longValueExact(),
                issuedAt = coupon.issuedAt,
            )
        }
    }
}
