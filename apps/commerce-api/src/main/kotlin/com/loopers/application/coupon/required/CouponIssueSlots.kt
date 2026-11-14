package com.loopers.application.coupon.required

/**
 * The processing slots every coupon issue shares, whatever its campaign (`CONTEXT.md`'s 처리 자리). There are as many as the
 * server's capacity, and they know nothing of a campaign's quota: holding a slot issues nothing, and releasing one changes no count.
 *
 * Every token carries the name of the instance that minted it, so an instance can tell its own leftovers from another instance's
 * live tokens. Nothing expires a token: one is gone only when it is released.
 *
 * Running out of slots is a plain result, `null`, and a token missing at release is a plain `false`; an exception means only that
 * the store behind the slots failed or answered something it should never answer.
 */
interface CouponIssueSlots {
    /** Acquires an empty slot and returns the token that holds it, minted for this call alone, or `null` when every slot is taken. Never waits. */
    fun acquire(): String?

    /**
     * Releases the slot [token] holds and answers whether the token was holding one. `false` means it was already gone: released
     * before, removed by [releaseOwnTokens], or never acquired. Either way no other token's slot is released.
     */
    fun release(token: String): Boolean

    /**
     * Releases every slot held by a token carrying this instance's name, whichever issue acquired it, and leaves other instances'
     * tokens alone. It is not one atomic step, so it is only safe while this instance acquires nothing and runs no issue.
     */
    fun releaseOwnTokens()
}
