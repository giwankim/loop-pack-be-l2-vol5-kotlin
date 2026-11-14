package com.loopers.support

import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.testcontainers.StoppableRedis
import io.mockk.every
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.util.AopTestUtils

/**
 * [userId]'s issue pauses on the returned [Pause] at the start of the issuing step, after its slot was acquired and before any SQL:
 * it holds its slot and no campaign lock. Other users' issues are not stubbed.
 */
fun CouponIssueSteps.pauseIssuing(userId: Long): Pause {
    val pause = Pause()
    beforeTheIssueOf(userId) { pause.hold() }
    return pause
}

/**
 * [userId]'s issue finds its own slot token gone from the Set when the issuing step starts, as if Redis had lost it, so its release
 * removes nothing and closes the coupon issue admission on a working Redis (spec 231). Tokens held when the stub is set, by issues
 * already running, stay.
 */
fun CouponIssueSteps.loseTheSlotTokenOf(userId: Long, masterRedisTemplate: RedisTemplate<String, String>) {
    val heldBefore = masterRedisTemplate.heldCouponIssueSlotTokens()
    beforeTheIssueOf(userId) {
        masterRedisTemplate.removeCouponIssueSlotTokens(masterRedisTemplate.heldCouponIssueSlotTokens() - heldBefore)
    }
}

/**
 * [userId]'s issue stops [stoppableRedis] when the issuing step starts, after its slot was acquired, so the issue commits and its
 * release fails on a Redis that is really down.
 */
fun CouponIssueSteps.stopRedisDuringTheIssueOf(userId: Long, stoppableRedis: StoppableRedis) {
    beforeTheIssueOf(userId) { stoppableRedis.stop() }
}

/**
 * Runs [action] when [userId]'s issue enters the issuing step, then runs the real step. Other users' issues are not stubbed. The
 * receiver is a `@MockkSpyBean` field, a CGLIB proxy, so the stub goes on the spy it wraps.
 */
private fun CouponIssueSteps.beforeTheIssueOf(userId: Long, action: () -> Unit) {
    val target = AopTestUtils.getUltimateTargetObject<CouponIssueSteps>(this)
    every { target.issueUnderLock(userId, any()) } answers {
        action()
        callOriginal()
    }
}
