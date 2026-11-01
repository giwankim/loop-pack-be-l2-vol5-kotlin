package com.loopers.application.order.required

import com.loopers.domain.order.Order
import java.time.Instant

/**
 * The first confirmation of an order paid through the PG, as the order slice publishes it through [OrderEventPublisher]
 * (ADR 0032 rule 2). TX B builds it from the order it holds locked, so nothing reads the entity again after the commit.
 *
 * [items] hold one entry per product, in ascending product ID order. An order already has one line item per product
 * (`Order`'s init check), so building the event only sorts them.
 */
data class OrderConfirmedEvent(
    val orderId: Long,
    val confirmedAt: Instant,
    val items: List<Item>,
) {
    /**
     * Derived from the order and never stored (ADR 0032 rule 6). An order is confirmed only once, so a republished or
     * redelivered event carries the same ID. No publish count, clock reading or random value goes into it.
     */
    val eventId: String
        get() = "order-confirmed:v1:$orderId"

    data class Item(
        val productId: Long,
        val quantity: Int,
    )

    companion object {
        /** The confirmation of [order]. An order without a confirmation time has none, so it is refused. */
        fun from(order: Order): OrderConfirmedEvent {
            val confirmedAt = requireNotNull(order.confirmedAt) { "Order ${order.id} is not confirmed." }
            val items = order.productQuantities.entries
                .sortedBy { it.key }
                .map { (productId, quantity) -> Item(productId = productId, quantity = quantity) }

            return OrderConfirmedEvent(orderId = order.id, confirmedAt = confirmedAt, items = items)
        }
    }
}
