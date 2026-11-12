package com.loopers.application.coupon.provided

import com.loopers.application.coupon.CouponIssueCapacityExceededException
import com.loopers.application.coupon.required.CouponIssueSlots
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponCampaignExhaustedException
import com.loopers.domain.coupon.CouponCampaignSuspendedException
import com.loopers.support.captureSql
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate

/**
 * Issuing through the provided port with the processing slots in front of the issuing transaction, on real Redis and MySQL at the
 * configured capacity. Every scene is sequential: a taken slot is a token the test acquired through [CouponIssueSlots], not an issue
 * in flight. Redis is flushed after each test.
 */
class CouponIssueAdmissionTest(
    private val couponIssuer: CouponIssuer,
    private val couponIssueSlots: CouponIssueSlots,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseCommittingApplicationServiceTest() {
    @Test
    fun `with a free slot the issue runs, and its slot is released afterwards`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()

        val result = couponIssuer.issue(customer.id, campaign.id)

        assertThat(result.newlyIssued).isTrue()
        assertThat(heldTokens()).isEmpty()
    }

    @Test
    fun `with every slot taken a user without a coupon is refused, and nothing is issued`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        takeEverySlot()

        val captured = captureSql {
            assertThrows<CouponIssueCapacityExceededException> { couponIssuer.issue(customer.id, campaign.id) }
        }

        assertThat(captured.statements.filter { it.contains("for update", ignoreCase = true) }).isEmpty()
        assertThat(countCoupons()).isZero()
        assertThat(issuedCount(campaign)).isZero()
    }

    @Test
    fun `with every slot taken a holder still gets the same coupon`() {
        val holder = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = couponIssuer.issue(holder.id, campaign.id)
        takeEverySlot()

        val repeat = couponIssuer.issue(holder.id, campaign.id)

        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(first.coupon.id)
        assertThat(issuedCount(campaign)).isOne()
    }

    @Test
    fun `after a slot is released the refused user's retry is issued`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        val tokens = takeEverySlot()
        assertThrows<CouponIssueCapacityExceededException> { couponIssuer.issue(customer.id, campaign.id) }

        couponIssueSlots.release(tokens.first())
        val retried = couponIssuer.issue(customer.id, campaign.id)

        assertThat(retried.newlyIssued).isTrue()
        assertThat(countCoupons()).isOne()
        assertThat(issuedCount(campaign)).isOne()
        assertThat(heldTokens()).containsExactlyInAnyOrderElementsOf(tokens.drop(1))
    }

    @Test
    fun `an issue refused as exhausted releases its slot`() {
        val campaign = prepareCouponCampaign(totalQuota = 1)
        couponIssuer.issue(prepareUser().id, campaign.id)

        assertThrows<CouponCampaignExhaustedException> { couponIssuer.issue(prepareUser().id, campaign.id) }

        assertThat(heldTokens()).isEmpty()
        assertThat(issuedCount(campaign)).isOne()
    }

    @Test
    fun `an issue refused as suspended releases its slot`() {
        val campaign = prepareCouponCampaign()
        suspendCouponCampaign(campaign)

        assertThrows<CouponCampaignSuspendedException> { couponIssuer.issue(prepareUser().id, campaign.id) }

        assertThat(heldTokens()).isEmpty()
        assertThat(issuedCount(campaign)).isZero()
    }

    @Test
    fun `an issue refused as not found releases its slot`() {
        val exception = assertThrows<CoreException> { couponIssuer.issue(prepareUser().id, 999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
        assertThat(heldTokens()).isEmpty()
    }

    /** Acquires until the port answers `null`, so the scene holds whatever the configured capacity is. */
    private fun takeEverySlot(): List<String> {
        val tokens = generateSequence { couponIssueSlots.acquire() }.toList()
        assertThat(tokens).isNotEmpty()

        return tokens
    }

    private fun heldTokens(): Set<String> {
        return masterRedisTemplate.heldCouponIssueSlotTokens()
    }

    private fun countCoupons(): Long {
        return inNewTransaction { entityManager.countCoupons() }
    }

    private fun issuedCount(campaign: CouponCampaign): Int {
        return inNewTransaction { entityManager.couponCampaignIssuedCount(campaign.id) }
    }
}
