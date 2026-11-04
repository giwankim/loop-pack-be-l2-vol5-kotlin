package com.loopers.application.order

import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.support.stereotype.ApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import java.time.Clock

private val log = KotlinLogging.logger {}

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

    fun markPublished(orderEventOutboxId: Long) {
        val outbox = findForUpdate(orderEventOutboxId)

        outbox.markPublished(clock.instant())

        orderEventOutboxRepository.save(outbox)
    }

    /**
     * The backoff runs from now, after the wait for the broker, rather than from the claim. The WARN is here because only this method
     * sees the row on both sides of the failure: the publisher doesn't know the row's state, and the entity doesn't log. The failure
     * text is the adapter's, which is safe to log (ADR 0035 rule 4).
     */
    fun markFailed(orderEventOutboxId: Long, failureMessage: String) {
        val outbox = findForUpdate(orderEventOutboxId)
        val statusBeforeTheFailure = outbox.status

        outbox.markFailed(failureMessage, clock.instant())

        orderEventOutboxRepository.save(outbox)

        if (outbox.status == OrderEventOutboxStatus.BLOCKED && statusBeforeTheFailure != OrderEventOutboxStatus.BLOCKED) {
            log.warn {
                "Stopped retrying the order confirmed event: it stays BLOCKED until someone resumes it by hand. " +
                    "eventId=${outbox.eventId}, attempts=${outbox.attemptCount}, cause=$failureMessage"
            }
        }
    }

    /**
     * Reads the row again under its lock rather than saving the copy the claim returned, so that a relay past its lease can't overwrite
     * a newer state (ADR 0035 rule 9). Nothing deletes rows, so a missing one is a bug.
     */
    private fun findForUpdate(orderEventOutboxId: Long): OrderEventOutbox {
        return checkNotNull(orderEventOutboxRepository.findForUpdateById(orderEventOutboxId)) {
            "The claimed outbox row $orderEventOutboxId is gone."
        }
    }
}
