package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.CouponCampaign
import jakarta.validation.Valid

/** The admin's writes to a coupon campaign. */
interface CouponCampaignRegister {
    /** Stores a campaign that has issued nothing and is issuable. */
    fun register(@Valid request: CouponCampaignAdminRegisterRequest): CouponCampaign
}
