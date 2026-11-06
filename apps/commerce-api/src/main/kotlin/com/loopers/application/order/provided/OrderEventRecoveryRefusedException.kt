package com.loopers.application.order.provided

/**
 * 누락 복구 refused an order rather than guess (ADR 0036 rule 3). Each subclass is one row of the rule's table that ends in a refusal.
 * The refused order's transaction rolls back, so it writes no row, and [OrderEventRecoverer] stops there: the orders after it
 * aren't touched, and the ones before keep the rows they got. A rerun recovers those again as already present.
 *
 * It widens `RuntimeException` rather than `RuleViolationException`: no request carries the order ID, a person names it, so nothing
 * should answer 400 as if a request were wrong. Unlike the PG's exceptions (ADR 0023 rule 2) the refusals share a sealed supertype,
 * because whoever runs recovery handles them alike: the run stops and the refusal shows.
 */
sealed class OrderEventRecoveryRefusedException(
    message: String,
) : RuntimeException(message) {
    /** No order has the named ID. */
    class OrderNotFound(
        orderId: Long,
    ) : OrderEventRecoveryRefusedException("Refused 누락 복구: order $orderId doesn't exist.")

    /** The order has no confirmation time: a draft, a payment still pending, or a payment that failed. It has no event to recover. */
    class OrderNotConfirmed(
        orderId: Long,
    ) : OrderEventRecoveryRefusedException("Refused 누락 복구: order $orderId has no confirmation time.")

    /**
     * The order was confirmed, but not through the PG: a point-paid order of an earlier week. Only TX B leaves the event, and only
     * for a PG order, so recovering this one would backfill an event that was never missing (ADR 0036, not chosen: finding them all).
     */
    class OrderNotPaidThroughPg(
        orderId: Long,
    ) : OrderEventRecoveryRefusedException("Refused 누락 복구: order $orderId wasn't paid through the PG.")

    /**
     * The order's event already has a row, and it differs from the row recovery builds in [columns], in the table's column order.
     * The message names the event ID and the columns, never a payload: the row is there to be inspected, not copied into a log.
     */
    class OutboxRowDiffers(
        val eventId: String,
        val columns: List<String>,
    ) : OrderEventRecoveryRefusedException(
        "Refused 누락 복구: the outbox row of event $eventId differs in ${columns.joinToString()}.",
    )
}
