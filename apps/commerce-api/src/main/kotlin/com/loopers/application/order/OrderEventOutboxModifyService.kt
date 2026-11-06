package com.loopers.application.order

import com.loopers.application.order.provided.OrderEventRecoveryRefusedException
import com.loopers.application.order.provided.OrderEventRecoveryResult
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.required.OrderEventOutboxRepository
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.order.OrderEventOutboxStatus
import com.loopers.support.stereotype.ApplicationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * The outbox's short transactions: the relay's (ADR 0035 rule 11) and 누락 복구's, one per order (ADR 0036 rule 3). It implements no
 * provided port, and only [OrderEventRelay] and [OrderEventRecovery] call it. Its methods are public on a bean of their own because
 * a transaction is declared at the proxy, which a call within one class wouldn't pass. This isn't a saga: there is no compensation,
 * and it writes only the outbox.
 */
@ApplicationService
class OrderEventOutboxModifyService(
    private val orderEventOutboxRepository: OrderEventOutboxRepository,
    private val orderEventOutboxFactory: OrderEventOutboxFactory,
    private val orderFinder: OrderFinder,
    private val clock: Clock,
) {
    /**
     * Adds the row [orderId]'s confirmation is missing, built from the order as TX B builds it and due now, so the relay publishes it
     * on its next round. An order that already has the same row keeps it as it is, whatever its state (ADR 0036 rule 3). An order
     * whose facts don't fit, or whose row differs, is refused with an [OrderEventRecoveryRefusedException], and nothing is written.
     * Nothing is locked: two recoveries of one order at once meet at the unique event ID (rule 4). It reads the order and writes this
     * row, nothing else.
     *
     * The row that's there is the one the event ID finds: the lookup and the unique key share the column's collation, so the row a
     * new one would collide with is the row compared, even when its event ID differs in case.
     */
    fun recover(orderId: Long): OrderEventRecoveryResult {
        val order = orderFinder.findForRecovery(orderId)
        refuseUnrecoverableOrder(order)
        val recoveredOutbox = orderEventOutboxFactory.create(order)
        val existingOutbox = orderEventOutboxRepository.findByEventId(recoveredOutbox.eventId)

        if (existingOutbox != null) {
            refuseDifferingRow(existingOutbox, recoveredOutbox)

            return OrderEventRecoveryResult(orderId, recoveredOutbox.eventId, OrderEventRecoveryResult.Outcome.ALREADY_PRESENT)
        }

        orderEventOutboxRepository.save(recoveredOutbox)

        return OrderEventRecoveryResult(orderId, recoveredOutbox.eventId, OrderEventRecoveryResult.Outcome.ADDED)
    }

    /** The order facts recovery refuses rather than guess at (ADR 0036 rule 3). Checked before the row is built, so nothing is written. */
    private fun refuseUnrecoverableOrder(order: Order) {
        if (order.confirmedAt == null) {
            throw OrderEventRecoveryRefusedException.OrderNotConfirmed(order.id)
        }

        if (!order.paysThroughPg) {
            throw OrderEventRecoveryRefusedException.OrderNotPaidThroughPg(order.id)
        }
    }

    /** A row of the same event ID that isn't the same row is refused, naming the columns but not their values (ADR 0036 rule 3). */
    private fun refuseDifferingRow(existingOutbox: OrderEventOutbox, recoveredOutbox: OrderEventOutbox) {
        val differingColumns = existingOutbox.eventColumnsDifferingFrom(recoveredOutbox)

        if (differingColumns.isNotEmpty()) {
            throw OrderEventRecoveryRefusedException.OutboxRowDiffers(recoveredOutbox.eventId, differingColumns)
        }
    }

    /** Locks up to [batchSize] due rows and starts an attempt on each. The commit ends the lock, and the lease then protects the rows. */
    fun claimDueOutboxes(batchSize: Int): List<OrderEventOutbox> {
        val claimedAt = clock.instant()
        val dueOutboxes = orderEventOutboxRepository.findDueForUpdate(claimedAt, PageRequest.of(0, batchSize))

        dueOutboxes.forEach { dueOutbox -> dueOutbox.startPublishAttempt(claimedAt) }

        return orderEventOutboxRepository.saveAll(dueOutboxes)
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

        val savedOutbox = orderEventOutboxRepository.save(outbox)

        if (savedOutbox.status == OrderEventOutboxStatus.BLOCKED && statusBeforeTheFailure != OrderEventOutboxStatus.BLOCKED) {
            log.warn {
                "Stopped retrying the order confirmed event: it stays BLOCKED until someone resumes it by hand. " +
                    "eventId=${savedOutbox.eventId}, attempts=${savedOutbox.attemptCount}, cause=$failureMessage"
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
