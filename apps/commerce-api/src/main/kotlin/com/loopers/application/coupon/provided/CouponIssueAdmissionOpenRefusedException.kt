package com.loopers.application.coupon.provided

/**
 * Opening was refused because issues of this process were still running: removing this instance's tokens then could remove a token
 * a live issue holds. Admission stays closed.
 */
class CouponIssueAdmissionOpenRefusedException(
    runningIssues: Int,
) : RuntimeException("Coupon issue admission was not opened: $runningIssues issue(s) of this process are still running")
