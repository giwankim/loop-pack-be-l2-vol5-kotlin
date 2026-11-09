package com.loopers.application.order

import com.loopers.application.order.provided.OrderEventRelayer
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.support.stereotype.ApplicationService
import org.springframework.transaction.annotation.Propagation

/**
 * [OrderEventRelayer]'s implementation: claim, send, then save each result, every write in its own transaction (ADR 0035 rules 1 and 11).
 * It only sequences the calls, and the entity decides the states. `NEVER` makes Spring refuse a call inside a transaction, so a later
 * change can't quietly wrap the network wait in one.
 *
 * It doesn't catch the database's exceptions. One leaves the round, and a row whose result wasn't saved is published again once its
 * lease ends (order-outbox design 13.5).
 */
@ApplicationService(propagation = Propagation.NEVER)
class OrderEventRelay(
    private val orderEventOutboxModifyService: OrderEventOutboxModifyService,
    private val orderEventPublisher: OrderEventPublisher,
) : OrderEventRelayer {
    /**
     * Claims until a batch comes back short, so the batch size doesn't cap throughput at one batch per trigger (ADR 0035 rule 1). A healthy
     * batch takes milliseconds, so such a round ends once the backlog is gone. In an outage a batch takes a delivery timeout, so failed rows
     * can come due again before the round reaches a short batch. The growing backoff ends that for a modest backlog, and past what a 5 minute
     * backoff spans the round goes on until the broker returns or the rows are `BLOCKED`. That wait paces it, so it doesn't spin.
     */
    override fun publishDueEvents() {
        do {
            val claimedCount = publishNextBatch()
        } while (claimedCount == OrderEventOutbox.CLAIM_BATCH_SIZE)
    }

    /** Returns how many rows it claimed. */
    private fun publishNextBatch(): Int {
        val claimedOutboxes = orderEventOutboxModifyService.claimDueOutboxes(OrderEventOutbox.CLAIM_BATCH_SIZE)

        val publishResults = orderEventPublisher.publish(claimedOutboxes)

        publishResults.forEach { publishResult ->
            val failureMessage = publishResult.failureMessage

            if (failureMessage == null) {
                orderEventOutboxModifyService.markPublished(publishResult.orderEventOutboxId)
            } else {
                orderEventOutboxModifyService.markFailed(publishResult.orderEventOutboxId, failureMessage)
            }
        }

        return claimedOutboxes.size
    }
}
