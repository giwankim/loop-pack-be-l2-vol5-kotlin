package com.loopers.application.purchaseactivity.provided

import jakarta.validation.Valid

/** Recording one order confirmed event as purchase activity (`CONTEXT.md` 기록하다). */
interface PurchaseActivityRecorder {
    /**
     * Records [request] as one processed order confirmation and one purchase activity per item, in one transaction, and
     * returns [PurchaseActivityRecordResult.RECORDED]. If the same fact was recorded before, it writes nothing and returns
     * [PurchaseActivityRecordResult.ALREADY_RECORDED], so a redelivered event is safe to record again.
     *
     * Throws [PurchaseActivityConflictException] and writes nothing when the event conflicts with a recorded one: the same
     * event ID with another order, confirmation time or set of products and quantities, or the same order under another event
     * ID (`CONTEXT.md` 충돌). A request that breaks its own validation is refused with `ConstraintViolationException`.
     *
     * Two consumers recording the same event at once, across a rebalance, are settled by the unique keys: the one that loses
     * gets a `DataIntegrityViolationException`, and calling again reads what the other wrote (ADR 0033 rule 3).
     */
    fun record(@Valid request: PurchaseActivityRecordRequest): PurchaseActivityRecordResult
}
