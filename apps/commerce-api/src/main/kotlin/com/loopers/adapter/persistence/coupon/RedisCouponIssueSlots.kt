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
 *   hand out its own capacity. It is not a cache key either, and nothing expires it or its members.
 * - A token is `<instance-id>:<uuid>`: this instance's [CouponIssueProperties.instanceId], which never contains `:`, and a UUID
 *   minted per call, unrelated to the user, the campaign or anything a request sends.
 * - [acquire] runs `redis/coupon-issue-slot-acquire.lua`, which checks the token's membership, the Set's size and adds the token
 *   in one step, so two calls cannot both take the last slot. An answer other than 1 or 0 throws rather than reading as a full Set.
 * - [release] is a plain `SREM` of the caller's own token, answered by the count it removed. It is atomic as it is, and a second or
 *   unknown token removes nothing else.
 * - [releaseOwnTokens] reads the members and `SREM`s those starting with `<instance-id>:`. It deletes no key and touches no other key.
 *   A token acquired between the read and the `SREM` survives, which is why the port asks for no issue to run meanwhile.
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

        /** The script's answer when the token holds a slot, whether it was added now or was already a member. */
        private const val ACQUIRED = 1L

        /** The script's answer when every slot is taken. */
        private const val FULL = 0L

        private val ACQUIRE_SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/coupon-issue-slot-acquire.lua"))
            resultType = Long::class.javaObjectType
        }
    }

    private val ownTokenPrefix = "${properties.instanceId}:"

    override fun acquire(): String? {
        val token = ownTokenPrefix + UUID.randomUUID()
        val answer = redisTemplate.execute(ACQUIRE_SCRIPT, listOf(SLOTS_KEY), properties.slotCapacity.toString(), token)

        return when (answer) {
            ACQUIRED -> token
            FULL -> null
            else -> throw IllegalStateException("The slot acquire script answered $answer, neither $ACQUIRED nor $FULL")
        }
    }

    override fun release(token: String): Boolean {
        val removed = redisTemplate.opsForSet().remove(SLOTS_KEY, token)

        return removed == 1L
    }

    override fun releaseOwnTokens() {
        val ownTokens = redisTemplate.opsForSet().members(SLOTS_KEY).orEmpty().filter { it.startsWith(ownTokenPrefix) }
        if (ownTokens.isEmpty()) {
            return
        }

        redisTemplate.opsForSet().remove(SLOTS_KEY, *ownTokens.toTypedArray())
    }
}
