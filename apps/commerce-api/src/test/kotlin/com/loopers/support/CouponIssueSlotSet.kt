package com.loopers.support

import org.springframework.data.redis.core.RedisTemplate

/** The adapter keeps its key private, so the tests spell it out. */
private const val COUPON_ISSUE_SLOTS_KEY = "coupon-issue:slots"

/** The tokens holding a processing slot now, read straight from the Redis Set rather than through the port under test. */
fun RedisTemplate<String, String>.heldCouponIssueSlotTokens(): Set<String> {
    return opsForSet().members(COUPON_ISSUE_SLOTS_KEY).orEmpty()
}

/** Puts [token] in the Set straight, the way another instance's acquire would, without going through the port under test. */
fun RedisTemplate<String, String>.addCouponIssueSlotToken(token: String) {
    opsForSet().add(COUPON_ISSUE_SLOTS_KEY, token)
}

/** The Set key's time to live in seconds, as Redis' `TTL` answers it: -1 when the key exists without an expiry, -2 when it is missing. */
fun RedisTemplate<String, String>.couponIssueSlotsTimeToLive(): Long? {
    return getExpire(COUPON_ISSUE_SLOTS_KEY)
}
