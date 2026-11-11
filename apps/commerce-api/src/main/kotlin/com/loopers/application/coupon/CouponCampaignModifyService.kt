package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponCampaignAdminRegisterRequest
import com.loopers.application.coupon.provided.CouponCampaignRegister
import com.loopers.application.coupon.required.CouponCampaignRepository
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.shared.Money
import com.loopers.support.stereotype.ValidatedApplicationService

/** The implementation of [CouponCampaignRegister]. */
@ValidatedApplicationService
class CouponCampaignModifyService(
    private val couponCampaignRepository: CouponCampaignRepository,
) : CouponCampaignRegister {
    override fun register(request: CouponCampaignAdminRegisterRequest): CouponCampaign {
        val campaign = CouponCampaign(
            name = request.name,
            discountAmount = Money(request.discountAmount),
            totalQuota = request.totalQuota,
        )

        return couponCampaignRepository.save(campaign)
    }
}
