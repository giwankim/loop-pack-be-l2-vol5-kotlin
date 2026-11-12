package com.loopers.application.coupon.provided

import com.loopers.application.coupon.CouponIssueCapacityExceededException
import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.Coupon
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.support.Pause
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Slice
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.util.AopTestUtils

private val log = KotlinLogging.logger {}

/**
 * The capacity bound with issues in flight, on real Redis and MySQL: the assignment's §5 scene (spec 226 stories 21 and 22). The
 * context runs at a capacity of 3, so this class has a context of its own.
 *
 * The held issues are a controlled injection: a spy pauses each one at the start of the issuing step, after its slot was acquired and
 * before any SQL, so a held issue holds a slot and no campaign lock, and its peers on the same campaign are not stuck behind it.
 */
@TestPropertySource(properties = ["coupon-issue.slot-capacity=$CAPACITY"])
class CouponIssueSlotsIntegrationTest(
    private val couponIssuer: CouponIssuer,
    private val couponFinder: CouponFinder,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseCommittingApplicationServiceTest() {
    /** Where the held issues pause. Unstubbed, it is the real issuing transaction. */
    @MockkSpyBean
    private lateinit var couponIssueSteps: CouponIssueSteps

    /**
     * Removing the service's `release` makes this scene fail: the fourth user's retry is refused, because the finished issue's token stays
     * in the Set, and the Set is not empty at the end. That red check was run as a local edit, never committed.
     */
    @Test
    fun `with every slot held by an issue in flight the next user is refused, and is issued once one finishes`() {
        val campaign = prepareCouponCampaign()
        val heldCustomers = List(CAPACITY) { prepareUser() }
        val latecomer = prepareUser()
        val pauses = heldCustomers.map { pauseIssuing(it.id) }

        val holding = heldCustomers.map { customer -> inAnotherThread { couponIssuer.issue(customer.id, campaign.id) } }
        pauses.forEach { it.awaitHeld() }
        val tokensWhileHeld = masterRedisTemplate.heldCouponIssueSlotTokens()
        assertThat(tokensWhileHeld).hasSize(CAPACITY)

        assertThrows<CouponIssueCapacityExceededException> { couponIssuer.issue(latecomer.id, campaign.id) }
        assertThat(couponsOf(latecomer.id).content).isEmpty()
        assertThat(issuedCount(campaign)).isZero()

        pauses.first().release()
        assertThat(holding.first().await().newlyIssued).isTrue()
        val retried = couponIssuer.issue(latecomer.id, campaign.id)
        assertThat(retried.newlyIssued).isTrue()

        pauses.drop(1).forEach { it.release() }
        assertThat(holding.drop(1).map { it.await().newlyIssued }).containsOnly(true)
        val tokensAfter = masterRedisTemplate.heldCouponIssueSlotTokens()
        val couponRows = countCoupons()
        val committedCount = issuedCount(campaign)
        val issuedUsers = CAPACITY + 1
        assertThat(tokensAfter).isEmpty()
        assertThat(couponRows).isEqualTo(issuedUsers.toLong())
        assertThat(committedCount).isEqualTo(issuedUsers)
        log.info {
            "Capacity scene: capacity $CAPACITY: ${tokensWhileHeld.size} issues held in their slots, the next user refused " +
                "with ${CouponIssueCapacityExceededException::class.simpleName}, issued on retry after one finished; " +
                "slots held at the end ${tokensAfter.size}, coupon rows $couponRows, issued count $committedCount"
        }
    }

    /** [userId]'s issue pauses at the start of the issuing step, holding its slot and no lock. Other users' issues are not stubbed. */
    private fun pauseIssuing(userId: Long): Pause {
        val pause = Pause()
        val target = AopTestUtils.getUltimateTargetObject<CouponIssueSteps>(couponIssueSteps)
        every { target.issueUnderLock(userId, any()) } answers {
            pause.hold()
            callOriginal()
        }
        return pause
    }

    private fun couponsOf(userId: Long): Slice<Coupon> {
        return inNewTransaction { couponFinder.findAll(userId, CouponListRequest()) }
    }

    private fun countCoupons(): Long {
        return inNewTransaction { entityManager.countCoupons() }
    }

    private fun issuedCount(campaign: CouponCampaign): Int {
        return inNewTransaction { entityManager.couponCampaignIssuedCount(campaign.id) }
    }
}

/** The scene's capacity, below the default of 10 so that holding every slot takes few threads. */
private const val CAPACITY = 3
