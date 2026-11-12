package com.loopers.application.coupon.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.application.coupon.CouponIssueAdmissionClosedException
import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.support.addCouponIssueSlotToken
import com.loopers.support.captureSql
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.linesAt
import com.loopers.support.loseTheSlotTokenOf
import com.loopers.support.pauseIssuing
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate

/**
 * Closing and opening the coupon issue admission through the provided ports, on a working Redis and MySQL. Admission is closed by
 * something a test can cause on a working Redis: a held token removed from the Set while its issue runs, so that the issue's release
 * removes nothing (spec 231). The scenes of a stopped or paused Redis are `CouponIssueRedisFailureTest`'s.
 *
 * The context opened admission at startup, and every scene opens it again in cleanup, so a closing scene never reaches the next test.
 * Closing is prepared through [CouponIssuer] on purpose: admission closes only on a real issue's release, and the class has no other
 * way to reach that state.
 */
class CouponIssueAdmissionClosingTest(
    private val couponIssuer: CouponIssuer,
    private val couponIssueAdmissionOpener: CouponIssueAdmissionOpener,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseCommittingApplicationServiceTest() {
    /** Where a scene removes an issue's token or holds an issue. Unstubbed, it is the real issuing transaction. */
    @MockkSpyBean
    private lateinit var couponIssueSteps: CouponIssueSteps

    /** Logs from the application's coupon package, where closing admission logs its ERROR. */
    private val couponApplicationLogs = ListAppender<ILoggingEvent>()

    private val couponApplicationLogger = LoggerFactory.getLogger("com.loopers.application.coupon") as Logger

    @BeforeEach
    fun captureLogs() {
        couponApplicationLogs.start()
        couponApplicationLogger.addAppender(couponApplicationLogs)
    }

    @AfterEach
    fun openAdmissionAgain() {
        couponApplicationLogger.detachAppender(couponApplicationLogs)
        couponApplicationLogs.stop()
        couponIssueAdmissionOpener.open()
    }

    @Test
    fun `an issue whose token vanished is still issued, and the next new user is refused as closed without any SQL lock`() {
        val campaign = prepareCouponCampaign()
        val losing = prepareUser()
        val latecomer = prepareUser()
        couponIssueSteps.loseTheSlotTokenOf(losing.id, masterRedisTemplate)

        val issued = couponIssuer.issue(losing.id, campaign.id)
        val captured = captureSql {
            assertThrows<CouponIssueAdmissionClosedException> { couponIssuer.issue(latecomer.id, campaign.id) }
        }

        assertThat(issued.newlyIssued).isTrue()
        assertThat(captured.statements.filter { it.contains("for update", ignoreCase = true) }).isEmpty()
        assertThat(committedCouponsOf(latecomer.id).content).isEmpty()
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).isEmpty()
        assertThat(couponApplicationLogs.linesAt(Level.ERROR)).hasSize(1)
    }

    @Test
    fun `while admission is closed a holder still gets the same coupon`() {
        val campaign = prepareCouponCampaign()
        val held = prepareCoupon(campaign)
        closeAdmission(campaign)

        val repeat = couponIssuer.issue(held.userId, campaign.id)

        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(held.id)
    }

    @Test
    fun `opening is refused while an issue holds its slot, and that issue still finishes`() {
        val campaign = prepareCouponCampaign()
        val held = prepareUser()
        val pause = couponIssueSteps.pauseIssuing(held.id)
        val holding = inAnotherThread { couponIssuer.issue(held.id, campaign.id) }
        pause.awaitHeld()
        closeAdmission(campaign)

        assertThrows<CouponIssueAdmissionOpenRefusedException> { couponIssueAdmissionOpener.open() }

        assertThrows<CouponIssueAdmissionClosedException> { couponIssuer.issue(prepareUser().id, campaign.id) }
        pause.release()
        assertThat(holding.await().newlyIssued).isTrue()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).isEmpty()
    }

    @Test
    fun `opening removes this instance's leftover tokens, keeps another instance's, and the next new user is issued`() {
        val campaign = prepareCouponCampaign()
        closeAdmission(campaign)
        masterRedisTemplate.addCouponIssueSlotToken("$INSTANCE_ID:left-over")
        masterRedisTemplate.addCouponIssueSlotToken("$OTHER_INSTANCE_ID:live")

        couponIssueAdmissionOpener.open()

        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactly("$OTHER_INSTANCE_ID:live")
        assertThat(couponIssuer.issue(prepareUser().id, campaign.id).newlyIssued).isTrue()
    }

    /** Closes admission the way the first scene does: a new user's issue of [campaign] loses its token, and is still issued. */
    private fun closeAdmission(campaign: CouponCampaign) {
        val losing = prepareUser()
        couponIssueSteps.loseTheSlotTokenOf(losing.id, masterRedisTemplate)
        couponIssuer.issue(losing.id, campaign.id)
    }
}

/** The test profile's `coupon-issue.instance-id`. */
private const val INSTANCE_ID = "localhost"

/** A name no instance of this context has, standing for another instance sharing the Set. */
private const val OTHER_INSTANCE_ID = "other-instance"
