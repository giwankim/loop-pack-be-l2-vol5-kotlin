package com.loopers.adapter.persistence.coupon

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The coupon issue's processing slots, under `coupon-issue` in `application.yml`.
 *
 * [slotCapacity] is how many issues may run at once across every campaign (`CONTEXT.md`'s 수용량). It comes from the server's
 * configuration alone, never from a request, and a value below 1 fails the start rather than leaving a server that refuses every issue.
 */
@ConfigurationProperties("coupon-issue")
data class CouponIssueProperties(
    val slotCapacity: Int,
) {
    init {
        require(slotCapacity >= 1) { "coupon-issue.slot-capacity must be 1 or more: $slotCapacity" }
    }
}
