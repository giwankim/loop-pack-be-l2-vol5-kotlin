package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponCampaignAdminRegisterRequest
import com.loopers.application.coupon.provided.CouponCampaignFinder
import com.loopers.application.coupon.provided.CouponCampaignRegister
import com.loopers.application.coupon.required.CouponCampaignRepository
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.shared.Money
import com.loopers.support.stereotype.ValidatedApplicationService

/** The implementation of [CouponCampaignRegister]. */
@ValidatedApplicationService
class CouponCampaignModifyService(
    private val couponCampaignFinder: CouponCampaignFinder,
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

    /**
     * Locks the campaign on its first read like every other writer of its row (ADR 0018 rule 1), so a suspension waits for an issue in progress
     * and the next issue sees it. The transaction locks no other row.
     */
    override fun suspend(campaignId: Long): CouponCampaign {
        val campaign = couponCampaignFinder.findForUpdate(campaignId)

        campaign.suspend()

        return couponCampaignRepository.save(campaign)
    }
}
