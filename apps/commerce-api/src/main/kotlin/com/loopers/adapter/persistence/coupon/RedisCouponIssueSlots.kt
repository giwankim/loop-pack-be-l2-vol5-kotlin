package com.loopers.adapter.persistence.coupon

import com.loopers.application.coupon.required.CouponIssueSlots
import com.loopers.config.redis.RedisConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The processing slots as one Redis Set whose members are the tokens of the issues running now.
 *
 * - The key is a constant, not a property: every instance must count in the same Set, and a name per instance would let each
 *   admit its own capacity. It is not a cache key either, and nothing expires it or its members.
 * - [acquire] runs `redis/coupon-issue-slot-acquire.lua`, which checks the token's membership, the Set's size and adds the token
 *   in one step, so two calls cannot both take the last slot. The token is a UUID minted per call, unrelated to the user, the
 *   campaign or anything a request sends.
 * - [release] is a plain `SREM` of the caller's own token. It is atomic as it is, and a second or unknown token removes nothing else.
 * - Every command goes to the master, so admission never rests on a replica's stale view of the Set.
 *
 * Redis failures are not caught or translated here; they reach the caller as Spring's `DataAccessException`s.
 */
@Component
class RedisCouponIssueSlots(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redisTemplate: RedisTemplate<String, String>,
    private val properties: CouponIssueProperties,
) : CouponIssueSlots {
    companion object {
        private const val SLOTS_KEY = "coupon-issue:slots"

        /** The script's answer when the token holds a slot, whether it was added now or was already a member. 0 means every slot is taken. */
        private const val ADMITTED = 1L

        private val ACQUIRE_SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/coupon-issue-slot-acquire.lua"))
            resultType = Long::class.javaObjectType
        }
    }

    override fun acquire(): String? {
        val token = UUID.randomUUID().toString()
        val answer = redisTemplate.execute(ACQUIRE_SCRIPT, listOf(SLOTS_KEY), properties.slotCapacity.toString(), token)

        return if (answer == ADMITTED) token else null
    }

    override fun release(token: String) {
        redisTemplate.opsForSet().remove(SLOTS_KEY, token)
    }
}
