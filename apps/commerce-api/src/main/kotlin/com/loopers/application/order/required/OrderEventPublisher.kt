package com.loopers.application.order.required

/**
 * Publishes an order's first confirmation (ADR 0032 rule 1). The saga step that confirms the order calls it inside the
 * transaction that confirms it, TX B, and nothing else does. The implementation is in `adapter.integration.kafka`.
 *
 * It promises the caller three things:
 * - the event is delivered only if the enclosing transaction commits,
 * - it is dropped if that transaction rolls back,
 * - delivering it never throws into the caller, so a broker problem never undoes a confirmation.
 *
 * Calling it outside a transaction is a programming error, not a delivery problem: there is no commit to deliver after.
 * The implementation refuses such a call with [IllegalStateException].
 */
interface OrderEventPublisher {
    fun publish(event: OrderConfirmedEvent)
}
