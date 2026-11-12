package com.loopers.application.coupon.provided

import com.loopers.application.coupon.CouponIssueCapacityExceededException
import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.config.redis.RedisConfig
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.pauseIssuing
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.context.TestPropertySource

private val log = KotlinLogging.logger {}

/**
 * The capacity bound with issues in flight, on real Redis and MySQL: the assignment's §5 scene (spec 226 stories 21 and 22). The
 * context runs at a capacity of 3, so this class has a context of its own.
 *
 * The held issues are a controlled injection: a spy pauses each one at the start of the issuing step, after its slot was acquired and
 * before any SQL. The step's transactional proxy has already begun the transaction, so a held issue holds a slot and a pooled
 * connection but no campaign lock, and its peers on the same campaign are not stuck behind it.
 */
@TestPropertySource(properties = ["coupon-issue.slot-capacity=$CAPACITY"])
class CouponIssueSlotsIntegrationTest(
    private val couponIssuer: CouponIssuer,
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
        val heldUsers = List(CAPACITY) { prepareUser() }
        val latecomer = prepareUser()
        val pauses = heldUsers.map { couponIssueSteps.pauseIssuing(it.id) }

        val holding = heldUsers.map { user -> inAnotherThread { couponIssuer.issue(user.id, campaign.id) } }
        pauses.forEach { it.awaitHeld() }
        val tokensWhileHeld = masterRedisTemplate.heldCouponIssueSlotTokens()
        assertThat(tokensWhileHeld).hasSize(CAPACITY)

        assertThrows<CouponIssueCapacityExceededException> { couponIssuer.issue(latecomer.id, campaign.id) }
        assertThat(committedCouponsOf(latecomer.id).content).isEmpty()
        assertThat(committedIssuedCount(campaign)).isZero()

        pauses.first().release()
        assertThat(holding.first().await().newlyIssued).isTrue()
        val retried = couponIssuer.issue(latecomer.id, campaign.id)
        assertThat(retried.newlyIssued).isTrue()

        pauses.drop(1).forEach { it.release() }
        assertThat(holding.drop(1).map { it.await().newlyIssued }).containsOnly(true)
        val tokensAfter = masterRedisTemplate.heldCouponIssueSlotTokens()
        val couponRows = committedCouponCount()
        val committedCount = committedIssuedCount(campaign)
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
}

/** The scene's capacity, below the default of 10 so that holding every slot takes few threads. */
private const val CAPACITY = 3
