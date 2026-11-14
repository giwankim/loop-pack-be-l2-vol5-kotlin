package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.Coupon
import jakarta.validation.Valid
import org.springframework.data.domain.Slice

/** Reads of coupons. The customer's own-coupon list calls it. */
interface CouponFinder {
    /**
     * A slice of [userId]'s coupons, newest issue first, and the larger ID first among equal issue times. The web boundary has already
     * accepted the requester, so it is not checked again (ADR 0015), and the only user ID taken is the requester's.
     *
     * Each coupon carries the campaign's name and discount amount as they were at issue, so the list reads coupons only: it never
     * touches the campaign, and never Redis, so it keeps working when the campaign is exhausted or suspended, or Redis is down.
     */
    fun findAll(userId: Long, @Valid request: CouponListRequest): Slice<Coupon>
}
