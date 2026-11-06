package com.loopers.application.order.provided

/**
 * 누락 복구: adds the outbox row an order confirmed without one is missing, from the order's stored facts, so the relay publishes
 * the event that was never published (ADR 0036). It covers only the orders a person names. Nothing looks for orders without a row.
 *
 * Each order runs in its own transaction, in the given order, and gets one [OrderEventRecoveryResult]. An order recovery can't take
 * as it is ends the call with an [OrderEventRecoveryRefusedException]: the orders before it keep their rows, the ones after it aren't
 * touched, and a rerun of the same list finishes the rest (ADR 0036 rule 3). Recovery never calls the PG, confirms an order again
 * or touches its reservation: it reads the order and writes one row at most. A trigger calls it, outside any transaction.
 */
interface OrderEventRecoverer {
    fun recover(orderIds: List<Long>): List<OrderEventRecoveryResult>
}

/** What recovery did for one order. [eventId] is the event's, derived from the order as every event ID is. */
data class OrderEventRecoveryResult(
    val orderId: Long,
    val eventId: String,
    val outcome: Outcome,
) {
    enum class Outcome {
        /** The order had no row, so one was added, pending and due at once. */
        ADDED,

        /** The order already had the same row, equal in all six event columns, in whatever state, so nothing changed. */
        ALREADY_PRESENT,
    }
}
