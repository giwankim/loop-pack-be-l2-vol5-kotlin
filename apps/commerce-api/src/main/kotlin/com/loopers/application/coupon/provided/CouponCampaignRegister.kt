package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.CouponCampaign
import jakarta.validation.Valid

/** The admin's writes to a coupon campaign. */
interface CouponCampaignRegister {
    /** Stores a campaign that has issued nothing and is issuable. */
    fun register(@Valid request: CouponCampaignAdminRegisterRequest): CouponCampaign

    /**
     * Stops the campaign issuing for good and answers with it. Suspending a suspended campaign changes nothing. Coupons already issued are
     * kept. A missing campaign is refused with `COUPON_CAMPAIGN_NOT_FOUND`.
     */
    fun suspend(campaignId: Long): CouponCampaign
}
