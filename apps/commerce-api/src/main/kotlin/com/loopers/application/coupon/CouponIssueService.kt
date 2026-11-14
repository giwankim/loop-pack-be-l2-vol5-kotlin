package com.loopers.application.coupon

import com.loopers.application.coupon.provided.CouponIssueResult
import com.loopers.application.coupon.provided.CouponIssuer
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
 * 2. Pass the coupon issue admission ([CouponIssueAdmission]), which acquires a processing slot. A closed admission refuses with
 *    [CouponIssueAdmissionClosedException], and no free slot with [CouponIssueCapacityExceededException]; either way no issuing
 *    transaction starts. Nothing waits or retries here.
 * 3. [CouponIssueSteps] runs the issuing transaction.
 * 4. The admission releases the slot, whatever step 3 ended in: an issue, a business rejection, a lock timeout or any other exception.
 *    Under `NEVER` the transaction has committed or rolled back by the time step 3 returns or throws, so a free slot always means the
 *    database work is over. Releasing changes neither the issued count nor a coupon, and a release that fails closes admission without
 *    changing this request's answer.
 *
 * This service never calls the slots port itself: whether to trust the slots is the admission's rule, and a failure of the slots
 * reaches the caller only as a closed admission, before step 3.
 *
 * A surrounding transaction would also be wrong, not only slow: the read in step 1 would fix the REPEATABLE READ snapshot before the
 * lock is granted, and the re-check under the lock would miss a coupon that the previous lock holder just committed.
 */
@ApplicationService(propagation = Propagation.NEVER)
class CouponIssueService(
    private val couponRepository: CouponRepository,
    private val couponIssueSteps: CouponIssueSteps,
    private val couponIssueAdmission: CouponIssueAdmission,
) : CouponIssuer {
    override fun issue(userId: Long, campaignId: Long): CouponIssueResult {
        val existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId)
        if (existing != null) {
            return CouponIssueResult(existing, newlyIssued = false)
        }

        return couponIssueAdmission.admit { couponIssueSteps.issueUnderLock(userId, campaignId) }
    }
}
