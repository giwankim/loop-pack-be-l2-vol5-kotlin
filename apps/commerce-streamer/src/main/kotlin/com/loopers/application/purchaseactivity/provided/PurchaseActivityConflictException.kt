package com.loopers.application.purchaseactivity.provided

import com.loopers.support.error.UnprocessableOrderEventException

/**
 * An order confirmed event conflicts with one recorded before (`CONTEXT.md` 충돌): the same event ID with another fact, or
 * the same order under another event ID. Neither is a redelivery but an error on the publishing side, so the recorded fact
 * is not overwritten. Redelivering the event gives the same answer, so it is an unprocessable event (ADR 0033 rule 5).
 *
 * The message is the evidence a person gets (purchase activity design 5): the incoming event ID and order ID, and for each
 * value that differs, the recorded one and the incoming one.
 */
class PurchaseActivityConflictException(
    eventId: String,
    orderId: Long,
    differences: List<String>,
) : UnprocessableOrderEventException(
    "Order confirmed event $eventId of order $orderId conflicts with the recorded one: ${differences.joinToString("; ")}.",
)
