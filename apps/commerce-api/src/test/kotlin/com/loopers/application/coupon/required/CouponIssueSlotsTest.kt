package com.loopers.application.coupon.required

import com.loopers.adapter.persistence.coupon.CouponIssueProperties
import com.loopers.adapter.persistence.coupon.RedisCouponIssueSlots
import com.loopers.config.redis.RedisConfig
import com.loopers.support.CONFIGURED_COUPON_ISSUE_INSTANCE_ID
import com.loopers.support.addCouponIssueSlotToken
import com.loopers.support.couponIssueSlotsTimeToLive
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import java.util.UUID

/**
 * The processing slots on the real test Redis. Most scenes build the adapter at a capacity of [CAPACITY] over the context's master
 * template, so the class shares the base's context instead of starting one with another `coupon-issue.slot-capacity`.
 * One scene goes through the context's own bean to see the configured capacity and the wiring.
 *
 * The base is the committing one only for its start gate ([runConcurrently]); the slots touch no table. Redis is flushed after each test.
 */
class CouponIssueSlotsTest(
    private val couponIssueSlots: CouponIssueSlots,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        private const val CAPACITY = 3
        private const val INSTANCE_ID = "commerce-api-1"
        private const val OTHER_INSTANCE_ID = "commerce-api-2"
        private const val THREADS = 8

        /** `application.yml`'s `coupon-issue.slot-capacity`, the same in every profile. */
        private const val CONFIGURED_CAPACITY = 10

        /** Redis' `TTL` answer for a key that exists without an expiry. */
        private const val NO_EXPIRY = -1L
    }

    private val slots = RedisCouponIssueSlots(
        masterRedisTemplate,
        CouponIssueProperties(slotCapacity = CAPACITY, instanceId = INSTANCE_ID),
    )

    @Test
    fun `acquiring returns a token capacity times and null the next time, and the set holds capacity members`() {
        val tokens = List(CAPACITY) { slots.acquire() }

        val overflow = slots.acquire()

        assertThat(tokens).doesNotContainNull()
        assertThat(overflow).isNull()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrderElementsOf(tokens)
    }

    @Test
    fun `after one release one more acquire returns a token`() {
        val tokens = List(CAPACITY) { slots.acquire()!! }

        slots.release(tokens.first())
        val next = slots.acquire()

        assertThat(next).isNotNull()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrder(next, tokens[1], tokens[2])
    }

    @Test
    fun `every acquire mints a different token`() {
        val first = slots.acquire()
        slots.release(first!!)

        val second = slots.acquire()

        assertThat(second).isNotNull().isNotEqualTo(first)
    }

    @Test
    fun `a token starts with the instance name and a colon`() {
        val token = slots.acquire()

        assertThat(token).startsWith("$INSTANCE_ID:")
    }

    @Test
    fun `releasing a held token answers true and the same token again answers false, leaving the other tokens in the set`() {
        val tokens = List(CAPACITY) { slots.acquire()!! }

        val first = slots.release(tokens.first())
        val second = slots.release(tokens.first())

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrder(tokens[1], tokens[2])
    }

    @Test
    fun `releasing a token that was never acquired answers false and leaves every token in the set`() {
        val tokens = List(CAPACITY) { slots.acquire()!! }

        val released = slots.release("$INSTANCE_ID:${UUID.randomUUID()}")

        assertThat(released).isFalse()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrderElementsOf(tokens)
    }

    @Test
    fun `releasing this instance's tokens leaves another instance's token in the set`() {
        repeat(2) { slots.acquire()!! }
        val otherToken = "$OTHER_INSTANCE_ID:${UUID.randomUUID()}"
        masterRedisTemplate.addCouponIssueSlotToken(otherToken)

        slots.releaseOwnTokens()

        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactly(otherToken)
    }

    @Test
    fun `releasing this instance's tokens leaves a token whose instance name only starts with this one's`() {
        slots.acquire()!!
        val longerNameToken = "$INSTANCE_ID-canary:${UUID.randomUUID()}"
        masterRedisTemplate.addCouponIssueSlotToken(longerNameToken)

        slots.releaseOwnTokens()

        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactly(longerNameToken)
    }

    @Test
    fun `releasing this instance's tokens with none held changes nothing`() {
        slots.releaseOwnTokens()

        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).isEmpty()
    }

    @Test
    fun `after an acquire the set has no expiry`() {
        slots.acquire()!!

        assertThat(masterRedisTemplate.couponIssueSlotsTimeToLive()).isEqualTo(NO_EXPIRY)
    }

    @Test
    fun `eight threads acquiring at once against a capacity of three get exactly three tokens`() {
        val results = runConcurrently(List(THREADS) { { slots.acquire() } })

        val outcomes = tally(results)
        // A full slot is a null, not an exception, so every call is a success to tally.
        assertThat(outcomes.successes).isEqualTo(THREADS)
        assertThat(outcomes.technicalErrors).isZero()
        assertThat(outcomes.total).isEqualTo(THREADS)
        val tokens = results.mapNotNull { it.getOrThrow() }
        assertThat(tokens).hasSize(CAPACITY).doesNotHaveDuplicates()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrderElementsOf(tokens)
    }

    @Test
    fun `the context's slots are acquired up to the configured capacity and refused after`() {
        val tokens = List(CONFIGURED_CAPACITY) { couponIssueSlots.acquire() }

        val overflow = couponIssueSlots.acquire()

        assertThat(tokens).doesNotContainNull().allSatisfy { assertThat(it).startsWith("$CONFIGURED_COUPON_ISSUE_INSTANCE_ID:") }
        assertThat(overflow).isNull()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrderElementsOf(tokens)
    }
}
