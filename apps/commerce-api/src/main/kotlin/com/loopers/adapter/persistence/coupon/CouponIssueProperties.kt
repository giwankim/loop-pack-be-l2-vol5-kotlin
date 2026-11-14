package com.loopers.adapter.persistence.coupon

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The coupon issue's processing slots, under `coupon-issue` in `application.yml`.
 *
 * [slotCapacity] is how many issues may run at once across every campaign (`CONTEXT.md`'s 수용량). It comes from the server's
 * configuration alone, never from a request, and a value below 1 fails the start rather than leaving a server that refuses every issue.
 *
 * [instanceId] names this instance in every token it mints (`<instance-id>:<uuid>`), so the instance can tell its own leftovers from
 * another instance's live tokens. It comes from the environment (`${HOSTNAME}` outside `local` and `test`), and a missing or blank
 * name fails the start rather than letting instances share one by default. A `:` fails it too: the name ends at the first `:`, so a
 * name containing one would make another instance's tokens look like this one's.
 */
@ConfigurationProperties("coupon-issue")
data class CouponIssueProperties(
    val slotCapacity: Int,
    val instanceId: String,
) {
    init {
        require(slotCapacity >= 1) { "coupon-issue.slot-capacity must be 1 or more: $slotCapacity" }
        require(instanceId.isNotBlank()) { "coupon-issue.instance-id must not be blank" }
        require(':' !in instanceId) { "coupon-issue.instance-id must not contain ':': $instanceId" }
    }
}
