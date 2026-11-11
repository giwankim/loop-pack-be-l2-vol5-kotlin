package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponIssueResult
import com.loopers.application.coupon.provided.CouponIssuer
import com.loopers.application.coupon.required.CouponRepository
import com.loopers.support.stereotype.ApplicationService
import org.springframework.transaction.annotation.Propagation

/**
 * The implementation of [CouponIssuer]. Issuing is two steps and is deliberately not one transaction, so `NEVER` refuses a caller's
 * transaction, as `OrderPaymentSaga` does (ADR 0028 rule 2).
 *
 * 1. Outside any transaction, read the requester's coupon. A holder is answered here and locks nothing, so a repeat request never waits
 *    in the campaign's queue, and still gets its coupon from an exhausted campaign.
 * 2. Otherwise [CouponIssueSteps] runs the issuing transaction.
 *
 * A surrounding transaction would also be wrong, not only slow: this read would fix the REPEATABLE READ snapshot before the lock is
 * granted, and the re-check under the lock would miss a coupon that the previous lock holder just committed.
 */
@ApplicationService(propagation = Propagation.NEVER)
class CouponIssueService(
    private val couponRepository: CouponRepository,
    private val couponIssueSteps: CouponIssueSteps,
) : CouponIssuer {
    override fun issue(userId: Long, campaignId: Long): CouponIssueResult {
        val existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId)
        if (existing != null) {
            return CouponIssueResult(existing, newlyIssued = false)
        }

        return couponIssueSteps.issueUnderLock(userId, campaignId)
    }
}
