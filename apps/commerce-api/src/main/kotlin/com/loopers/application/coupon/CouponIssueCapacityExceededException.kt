package com.loopers.application.coupon

/**
 * Every processing slot is taken, so the issue did not start (`CONTEXT.md`'s 처리 자리). Nothing was issued and the request may be sent
 * again. It lives here and not in the domain: exhaustion and suspension are facts of a campaign, but full slots are the server's
 * state at this moment, which no entity knows.
 */
class CouponIssueCapacityExceededException : RuntimeException("쿠폰 발급을 처리할 자리가 없습니다. 잠시 뒤 다시 시도해 주세요.")
