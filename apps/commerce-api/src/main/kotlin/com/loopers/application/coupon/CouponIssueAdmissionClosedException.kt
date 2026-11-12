package com.loopers.application.coupon

/**
 * The coupon issue admission is closed, so the issue did not start (`CONTEXT.md`'s 발급 수용). Nothing was issued and no quota was
 * used. It is the server's condition, not the campaign's, and it promises no later issue: a closed admission does not open by itself.
 * It lives here and not in the domain for the reason [CouponIssueCapacityExceededException] does.
 */
class CouponIssueAdmissionClosedException(
    cause: Throwable? = null,
) : RuntimeException("지금은 쿠폰 발급을 받지 않습니다. 아무것도 발급하지 않았습니다.", cause)
