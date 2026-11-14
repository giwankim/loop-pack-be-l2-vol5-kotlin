package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponCampaignFinder
import com.loopers.application.coupon.required.CouponCampaignRepository
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ApplicationService

/** The implementation of [CouponCampaignFinder]. */
@ApplicationService(readOnly = true)
class CouponCampaignQueryService(
    private val couponCampaignRepository: CouponCampaignRepository,
) : CouponCampaignFinder {
    /** The class's `readOnly` does not apply when this joins the caller's write transaction. */
    override fun findForUpdate(campaignId: Long): CouponCampaign {
        return couponCampaignRepository.findForUpdateById(campaignId) ?: throw CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
    }
}
