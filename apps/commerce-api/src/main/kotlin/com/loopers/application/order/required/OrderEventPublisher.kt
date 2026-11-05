package com.loopers.application.order.required

import com.loopers.domain.order.OrderEventOutbox

/**
 * Sends claimed rows to the broker and waits for the broker's answer about each (ADR 0035 rules 4 and 5). It is only called outside a
 * transaction, because it waits on the network. The implementation is in `adapter.integration.kafka`.
 */
interface OrderEventPublisher {
    /** One result per row, since the broker answers each record on its own. */
    fun publish(outboxes: List<OrderEventOutbox>): List<OrderEventPublishResult>
}

/** What became of one row's send. With no [failureMessage], the broker confirmed the record. */
data class OrderEventPublishResult(
    val orderEventOutboxId: Long,
    val failureMessage: String?,
)
