package com.loopers.application.purchaseactivity

import com.loopers.application.purchaseactivity.provided.PurchaseActivityConflictException
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordResult
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecorder
import com.loopers.application.purchaseactivity.required.ProcessedOrderConfirmationRepository
import com.loopers.application.purchaseactivity.required.PurchaseActivityRepository
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.PurchaseActivity
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [PurchaseActivityRecorder]'s implementation: one transaction that reads first and writes only a new event (ADR 0033 rule
 * 3). It reads the processed order confirmation by event ID, and if there is none, by order ID.
 *
 * - Neither is found: the event is new. It saves the confirmation and the activities, explicitly (catalog design 5.37).
 * - One is found by event ID: it compares the order ID, the confirmation time and the items as a set of products and
 *   quantities. All equal is the same fact, and nothing is written. Any difference is a conflict (ADR 0033 rule 4).
 * - One is found by order ID only: the same order under another event ID, a conflict.
 *
 * The incoming fact is built as the entities it would be saved as, and compared as that. The entities cut the confirmation
 * time to microseconds, so an event whose time is finer still matches the time it was recorded with. Each product appears
 * once on both sides, by the activities' unique key and the request's validation, so a map from product to quantity is that
 * set, and it is what the conflict's message prints.
 *
 * The unique keys back the read when two consumers record the same event at once, across a rebalance. The one that loses gets
 * a `DataIntegrityViolationException` from its insert. It is not caught here: the transaction rolls back, and the listener
 * adapter calls again, which reads what the other wrote (ADR 0033 rule 3).
 */
@ValidatedApplicationService
class PurchaseActivityModifyService(
    private val processedOrderConfirmationRepository: ProcessedOrderConfirmationRepository,
    private val purchaseActivityRepository: PurchaseActivityRepository,
) : PurchaseActivityRecorder {
    override fun record(request: PurchaseActivityRecordRequest): PurchaseActivityRecordResult {
        val confirmation = ProcessedOrderConfirmation(
            eventId = request.eventId,
            orderId = request.orderId,
            confirmedAt = request.confirmedAt,
        )
        val activities = request.items.map { item ->
            PurchaseActivity(
                orderId = request.orderId,
                productId = item.productId,
                quantity = item.quantity,
                confirmedAt = request.confirmedAt,
            )
        }

        val sameEvent = processedOrderConfirmationRepository.findByEventId(request.eventId)
        if (sameEvent != null) {
            checkSameFact(sameEvent, confirmation, activities)

            return PurchaseActivityRecordResult.ALREADY_RECORDED
        }

        val sameOrder = processedOrderConfirmationRepository.findByOrderId(request.orderId)
        if (sameOrder != null) {
            val difference = difference("event ID", sameOrder.eventId, request.eventId)
            throw PurchaseActivityConflictException(request.eventId, request.orderId, listOf(difference))
        }

        processedOrderConfirmationRepository.save(confirmation)
        purchaseActivityRepository.saveAll(activities)

        return PurchaseActivityRecordResult.RECORDED
    }

    private fun checkSameFact(
        recorded: ProcessedOrderConfirmation,
        incoming: ProcessedOrderConfirmation,
        incomingActivities: List<PurchaseActivity>,
    ) {
        val recordedItems = purchaseActivityRepository.findAllByOrderId(recorded.orderId).quantitiesByProduct()
        val incomingItems = incomingActivities.quantitiesByProduct()
        val differences = listOfNotNull(
            differenceOrNull("order ID", recorded.orderId, incoming.orderId),
            differenceOrNull("confirmation time", recorded.confirmedAt, incoming.confirmedAt),
            differenceOrNull("items (product=quantity)", recordedItems, incomingItems),
        )
        if (differences.isNotEmpty()) {
            throw PurchaseActivityConflictException(incoming.eventId, incoming.orderId, differences)
        }
    }

    private fun differenceOrNull(name: String, recorded: Any, incoming: Any): String? {
        if (recorded == incoming) {
            return null
        }

        return difference(name, recorded, incoming)
    }

    private fun difference(name: String, recorded: Any, incoming: Any): String {
        return "$name recorded $recorded, incoming $incoming"
    }

    private fun List<PurchaseActivity>.quantitiesByProduct(): Map<Long, Int> {
        return associate { it.productId to it.quantity }.toSortedMap()
    }
}
