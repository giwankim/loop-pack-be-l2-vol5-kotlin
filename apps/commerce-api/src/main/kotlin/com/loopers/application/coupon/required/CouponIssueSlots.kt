package com.loopers.application.coupon.required

/**
 * The processing slots every coupon issue shares, whatever its campaign (`CONTEXT.md`'s 처리 자리). There are as many as the
 * server's capacity, and they know nothing of a campaign's quota: holding a slot issues nothing, and releasing one changes no count.
 *
 * Running out of slots is a plain result, `null`; an exception means only that the store behind the slots failed.
 */
interface CouponIssueSlots {
    /** Takes a free slot and returns the token that holds it, minted for this call alone, or `null` when every slot is taken. Never waits. */
    fun acquire(): String?

    /** Frees the slot [token] holds. Releasing a token twice, or one that was never acquired, frees no other slot. */
    fun release(token: String)
}
