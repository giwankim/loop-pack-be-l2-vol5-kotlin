package com.loopers.adapter.webapi.v1.coupon

import com.loopers.domain.coupon.CouponCampaign

data class CouponCampaignAdminResponse(
    val id: Long,
    val name: String,
    val discountAmount: Long,
    val totalQuota: Int,
    val issuedCount: Int,
    val suspended: Boolean,
) {
    companion object {
        fun from(campaign: CouponCampaign): CouponCampaignAdminResponse {
            return CouponCampaignAdminResponse(
                id = campaign.id,
                name = campaign.name,
                discountAmount = campaign.discountAmount.amount.longValueExact(),
                totalQuota = campaign.totalQuota,
                issuedCount = campaign.issuedCount,
                suspended = !campaign.issuable,
            )
        }
    }
}
