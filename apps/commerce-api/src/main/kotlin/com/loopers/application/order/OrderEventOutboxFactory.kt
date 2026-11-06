package com.loopers.application.order

import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.order.required.OrderEventSerializer
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderEventOutbox
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Builds the outbox row of a confirmed order. TX B and 누락 복구 both build their rows here, so the two paths can't drift apart
 * (ADR 0036 rule 1, order-outbox design 10). It saves nothing and opens no transaction: the caller saves the row in its own, so a
 * failure to serialize fails that transaction (ADR 0034 rule 2).
 *
 * The row is due at the `Clock` bean's now. For TX B that is the confirmation, and for recovery the moment it adds the row.
 */
@Component
class OrderEventOutboxFactory(
    private val orderEventSerializer: OrderEventSerializer,
    private val clock: Clock,
) {
    /** The row of [order]'s [OrderConfirmedEvent]. An order without a confirmation time has none, so it is refused. */
    fun create(order: Order): OrderEventOutbox {
        val orderConfirmedEvent = OrderConfirmedEvent.from(order)
        val serializedOrderEvent = orderEventSerializer.serialize(orderConfirmedEvent)

        return OrderEventOutbox(
            eventId = orderConfirmedEvent.eventId,
            orderId = orderConfirmedEvent.orderId,
            eventType = serializedOrderEvent.eventType,
            schemaVersion = serializedOrderEvent.schemaVersion,
            messageKey = serializedOrderEvent.messageKey,
            payload = serializedOrderEvent.payload,
            nextAttemptAt = clock.instant(),
        )
    }
}
