package com.loopers.adapter.consumer

import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import java.time.Instant

/**
 * The payload of an [OrderEventMessage] whose type is [EVENT_TYPE] at [SCHEMA_VERSION] (order-events design 2). [confirmedAt]
 * is read as an instant, not a string: commerce-api writes it in `ISO_INSTANT`, which drops each all-zero group of three
 * digits, so one time can arrive as `…03.100Z` or `…03Z` (purchase activity design 2).
 */
data class OrderConfirmedPayload(
    val orderId: Long,
    val confirmedAt: Instant,
    val items: List<Item>,
) {
    companion object {
        const val EVENT_TYPE = "OrderConfirmed"
        const val SCHEMA_VERSION = 1
    }

    data class Item(
        val productId: Long,
        val quantity: Int,
    )

    /** The request to record this payload as the event [eventId]. */
    fun toRecordRequest(eventId: String): PurchaseActivityRecordRequest {
        val requestItems = items.map { PurchaseActivityRecordRequest.Item(productId = it.productId, quantity = it.quantity) }

        return PurchaseActivityRecordRequest(
            eventId = eventId,
            orderId = orderId,
            confirmedAt = confirmedAt,
            items = requestItems,
        )
    }
}
