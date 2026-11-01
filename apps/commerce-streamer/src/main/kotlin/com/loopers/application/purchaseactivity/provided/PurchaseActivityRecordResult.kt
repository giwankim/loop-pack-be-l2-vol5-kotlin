package com.loopers.application.purchaseactivity.provided

/** What [PurchaseActivityRecorder.record] did with an order confirmed event that it did not refuse (`CONTEXT.md` 기록하다). */
enum class PurchaseActivityRecordResult {
    /** The event was new: one processed order confirmation and one purchase activity per item were written. */
    RECORDED,

    /** The event is the same fact as one recorded before (`CONTEXT.md` 같은 사실), so nothing was written. */
    ALREADY_RECORDED,
}
