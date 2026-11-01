package com.loopers.adapter.integration.kafka

import com.loopers.application.order.required.OrderConfirmedEvent
import java.time.Instant

/**
 * The order confirmed event as it goes over the wire: the envelope of order-events design 2 (ADR 0032 rule 6). The metadata
 * that tells a consumer what it is reading sits outside, the business fields inside [payload], because `order-events` is a
 * topic that can carry more than one type of event. It is the adapter's type, like the PG adapter's request and response
 * bodies. The consumer keeps a DTO of its own, so the contract is the bytes and design 2 is its document.
 */
data class OrderConfirmedMessage(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val payload: Payload,
) {
    /** The business fields of design 2's table and nothing else: no user, amount, unit price, payment or PG identifier. */
    data class Payload(
        val orderId: Long,
        val confirmedAt: Instant,
        val items: List<Item>,
    ) {
        data class Item(
            val productId: Long,
            val quantity: Int,
        )
    }

    companion object {
        const val EVENT_TYPE = "OrderConfirmed"
        const val SCHEMA_VERSION = 1

        /** The envelope of [event]. The items keep the event's ascending product ID order. */
        fun from(event: OrderConfirmedEvent): OrderConfirmedMessage {
            val items = event.items.map { Payload.Item(productId = it.productId, quantity = it.quantity) }

            return OrderConfirmedMessage(
                eventId = event.eventId,
                eventType = EVENT_TYPE,
                schemaVersion = SCHEMA_VERSION,
                payload = Payload(orderId = event.orderId, confirmedAt = event.confirmedAt, items = items),
            )
        }
    }
}
