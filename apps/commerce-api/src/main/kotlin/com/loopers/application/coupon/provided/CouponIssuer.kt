package com.loopers.application.coupon.provided

/** A customer's request for a coupon. */
interface CouponIssuer {
    /**
     * Issues [userId] a coupon of [campaignId], or answers with the coupon the user already holds.
     * Refuses a missing campaign with `COUPON_CAMPAIGN_NOT_FOUND`, a suspended one with `CouponCampaignSuspendedException`, and an
     * exhausted one with `CouponCampaignExhaustedException`. A holder gets the coupon back from a suspended or exhausted campaign too.
     * Refuses an issue with `CouponIssueCapacityExceededException` when every processing slot is taken; a holder is answered even then.
     */
    fun issue(userId: Long, campaignId: Long): CouponIssueResult
}
