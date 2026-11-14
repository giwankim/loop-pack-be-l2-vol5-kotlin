package com.loopers.application.coupon.provided

/**
 * Opens the coupon issue admission (`CONTEXT.md`'s 수용을 열다). Admission starts closed and never opens by itself, so this is the only
 * way it opens; in production it is called once, at startup.
 */
interface CouponIssueAdmissionOpener {
    /**
     * Removes every processing slot token carrying this instance's name, then opens admission. Opening an open admission does nothing.
     *
     * Refuses with [CouponIssueAdmissionOpenRefusedException] while an issue of this process is running, and leaves admission closed.
     * If removing the tokens fails, admission stays closed and that failure propagates.
     */
    fun open()
}
