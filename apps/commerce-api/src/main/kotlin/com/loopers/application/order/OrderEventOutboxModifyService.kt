package com.loopers.application.order

import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.support.stereotype.ApplicationService
import org.springframework.data.domain.PageRequest
import java.time.Clock

/**
 * The relay's short transactions (ADR 0035 rule 11). It implements no provided port and only [OrderEventRelay] calls it. Its methods
 * are public on a bean of their own because a transaction is declared at the proxy, which a call within one class wouldn't pass.
 * This isn't a saga: there is no compensation, and it stays inside one aggregate.
 */
@ApplicationService
class OrderEventOutboxModifyService(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val clock: Clock,
) {
    /** Locks up to [batchSize] due rows and starts an attempt on each. The commit ends the lock, and the lease then protects the rows. */
    fun claimDueOutboxes(batchSize: Int): List<OrderEventOutbox> {
        val claimedAt = clock.instant()
        val dueOutboxes = orderEventOutboxRepository.findDueForUpdate(claimedAt, PageRequest.of(0, batchSize))

        return dueOutboxes.map { dueOutbox ->
            dueOutbox.startPublishAttempt(claimedAt)

            orderEventOutboxRepository.save(dueOutbox)
        }
    }

    /**
     * Reads the row again under its lock rather than saving the copy the claim returned, so that a relay past its lease can't overwrite
     * a newer state (ADR 0035 rule 9). Nothing deletes rows, so a missing one is a bug.
     */
    fun markPublished(orderEventOutboxId: Long) {
        val outbox = checkNotNull(orderEventOutboxRepository.findForUpdateById(orderEventOutboxId)) {
            "The claimed outbox row $orderEventOutboxId is gone."
        }

        outbox.markPublished(clock.instant())

        orderEventOutboxRepository.save(outbox)
    }
}
