package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponCampaignFinder
import com.loopers.application.coupon.provided.CouponIssueResult
import com.loopers.application.coupon.required.CouponCampaignRepository
import com.loopers.application.coupon.required.CouponRepository
import com.loopers.domain.coupon.Coupon
import com.loopers.support.stereotype.ApplicationService
import java.time.Clock

/**
 * The issuing transaction. It is a bean of its own so that the call from [CouponIssueService] goes through the proxy that opens it, as
 * `OrderPaymentSagaSteps` is. It implements no provided port, and only that service calls it.
 */
@ApplicationService
class CouponIssueSteps(
    private val couponCampaignFinder: CouponCampaignFinder,
    private val couponCampaignRepository: CouponCampaignRepository,
    private val couponRepository: CouponRepository,
    private val clock: Clock,
) {
    /**
     * Locks the campaign on its first read, so that the issued count is never stale and same-campaign issues pass one at a time. The
     * lock must stay the first statement: the re-check below is a plain read, and it sees what the previous holder committed only
     * because its snapshot starts after the lock is granted.
     *
     * The re-check decides the duplicate, so no path recovers from a unique-key violation. If one happens anyway it is a bug and surfaces
     * as a 500. The campaign and the coupon commit together or not at all. The transaction locks no other row, so the global lock order
     * of ADR 0018 needs no new position.
     */
    fun issueUnderLock(userId: Long, campaignId: Long): CouponIssueResult {
        val campaign = couponCampaignFinder.findForUpdate(campaignId)

        val existingCoupon = couponRepository.findByCampaignIdAndUserId(campaignId, userId)
        if (existingCoupon != null) {
            return CouponIssueResult(existingCoupon, newlyIssued = false)
        }

        campaign.issue()
        val savedCampaign = couponCampaignRepository.save(campaign)

        val coupon = couponRepository.save(
            Coupon(
                campaignId = savedCampaign.id,
                userId = userId,
                campaignName = savedCampaign.name,
                discountAmount = savedCampaign.discountAmount,
                issuedAt = clock.instant(),
            ),
        )

        return CouponIssueResult(coupon, newlyIssued = true)
    }
}
