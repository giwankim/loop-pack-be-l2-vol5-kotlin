package com.loopers.application.coupon.required

import com.loopers.adapter.persistence.coupon.CouponIssueProperties
import com.loopers.adapter.persistence.coupon.RedisCouponIssueSlots
import com.loopers.config.redis.RedisConfig
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
        private const val THREADS = 8

        /** `application.yml`'s `coupon-issue.slot-capacity`, the same in every profile. */
        private const val CONFIGURED_CAPACITY = 10
    }

    private val slots = RedisCouponIssueSlots(masterRedisTemplate, CouponIssueProperties(slotCapacity = CAPACITY))

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
    fun `releasing the same token twice leaves the other tokens in the set`() {
        val tokens = List(CAPACITY) { slots.acquire()!! }

        slots.release(tokens.first())
        slots.release(tokens.first())

        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrder(tokens[1], tokens[2])
    }

    @Test
    fun `releasing a token that was never acquired leaves every token in the set`() {
        val tokens = List(CAPACITY) { slots.acquire()!! }

        slots.release(UUID.randomUUID().toString())

        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrderElementsOf(tokens)
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

        assertThat(tokens).doesNotContainNull()
        assertThat(overflow).isNull()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).containsExactlyInAnyOrderElementsOf(tokens)
    }
}
