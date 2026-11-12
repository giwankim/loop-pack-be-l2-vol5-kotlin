package com.loopers.support

import org.springframework.data.redis.core.RedisTemplate

/** The adapter keeps its key private, so the tests spell it out. */
private const val COUPON_ISSUE_SLOTS_KEY = "coupon-issue:slots"

/** The tokens holding a processing slot now, read straight from the Redis Set rather than through the port under test. */
fun RedisTemplate<String, String>.heldCouponIssueSlotTokens(): Set<String> {
    return opsForSet().members(COUPON_ISSUE_SLOTS_KEY).orEmpty()
}
