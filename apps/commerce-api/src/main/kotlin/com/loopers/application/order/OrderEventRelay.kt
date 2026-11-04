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
    /** A row whose result carries a failure stays as the claim left it, so it comes due when its lease ends. */
    override fun publishDueEvents() {
        val claimedOutboxes = orderEventOutboxModifyService.claimDueOutboxes(OrderEventOutbox.CLAIM_BATCH_SIZE)

        val publishResults = orderEventPublisher.publish(claimedOutboxes)

        publishResults
            .filter { it.failureMessage == null }
            .forEach { orderEventOutboxModifyService.markPublished(it.orderEventOutboxId) }
    }
}
