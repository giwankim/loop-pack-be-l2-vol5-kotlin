package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponIssueResult
import com.loopers.application.coupon.provided.CouponIssuer
import com.loopers.application.coupon.required.CouponIssueSlots
import com.loopers.application.coupon.required.CouponRepository
import com.loopers.support.stereotype.ApplicationService
import org.springframework.transaction.annotation.Propagation

/**
 * The implementation of [CouponIssuer]. Issuing is four steps and is deliberately not one transaction, so `NEVER` refuses a caller's
 * transaction, as `OrderPaymentSaga` does (ADR 0028 rule 2).
 *
 * 1. Outside any transaction, read the requester's coupon. A holder is answered here and locks nothing and takes no slot, so a repeat
 *    request never waits in the campaign's queue, is never refused for capacity, and still gets its coupon from an exhausted or
 *    suspended campaign.
 * 2. Acquire a processing slot ([CouponIssueSlots]). With none free, the issue is refused with [CouponIssueCapacityExceededException]
 *    and no issuing transaction starts. Nothing waits or retries here.
 * 3. [CouponIssueSteps] runs the issuing transaction.
 * 4. Release the slot, whatever step 3 ended in: an issue, a business rejection, a lock timeout or any other exception. Under `NEVER`
 *    the transaction has committed or rolled back by the time step 3 returns or throws, so a free slot always means the database work
 *    is over. Releasing changes neither the issued count nor a coupon.
 *
 * The slots' Redis failures are neither caught nor translated here. A failed acquire starts no issuing transaction, because the
 * acquire comes first.
 *
 * A surrounding transaction would also be wrong, not only slow: the read in step 1 would fix the REPEATABLE READ snapshot before the
 * lock is granted, and the re-check under the lock would miss a coupon that the previous lock holder just committed.
 */
@ApplicationService(propagation = Propagation.NEVER)
class CouponIssueService(
    private val couponRepository: CouponRepository,
    private val couponIssueSteps: CouponIssueSteps,
    private val couponIssueSlots: CouponIssueSlots,
) : CouponIssuer {
    override fun issue(userId: Long, campaignId: Long): CouponIssueResult {
        val existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId)
        if (existing != null) {
            return CouponIssueResult(existing, newlyIssued = false)
        }

        val token = couponIssueSlots.acquire() ?: throw CouponIssueCapacityExceededException()
        try {
            return couponIssueSteps.issueUnderLock(userId, campaignId)
        } finally {
            couponIssueSlots.release(token)
        }
    }
}
