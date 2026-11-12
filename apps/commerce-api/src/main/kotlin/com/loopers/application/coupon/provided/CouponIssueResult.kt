package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.Coupon

/** The coupon the user holds. [newlyIssued] is true only for the request that created it, and the web adapter answers 201 or 200 by it. */
data class CouponIssueResult(
    val coupon: Coupon,
    val newlyIssued: Boolean,
)
