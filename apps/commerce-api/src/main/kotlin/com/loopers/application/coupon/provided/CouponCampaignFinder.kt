package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.CouponCampaign

/** Reads of a coupon campaign. */
interface CouponCampaignFinder {
    /** Reads the campaign `FOR UPDATE`. A missing or deleted one is refused with `COUPON_CAMPAIGN_NOT_FOUND`. */
    fun findForUpdate(campaignId: Long): CouponCampaign
}
