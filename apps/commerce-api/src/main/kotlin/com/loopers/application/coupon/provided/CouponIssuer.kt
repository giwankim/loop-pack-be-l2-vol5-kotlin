package com.loopers.application.coupon.provided

/** A customer's request for a coupon. */
interface CouponIssuer {
    /**
     * Issues [userId] a coupon of [campaignId], or answers with the coupon the user already holds.
     * Refuses a missing campaign with `COUPON_CAMPAIGN_NOT_FOUND`, and an exhausted one with `CouponCampaignExhaustedException`.
     */
    fun issue(userId: Long, campaignId: Long): CouponIssueResult
}
