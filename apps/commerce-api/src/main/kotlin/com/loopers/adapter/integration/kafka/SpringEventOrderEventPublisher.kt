package com.loopers.adapter.integration.kafka

import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.order.required.OrderEventPublisher
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [OrderEventPublisher] on Spring's application events (ADR 0032 rule 3). It hands the event to [ApplicationEventPublisher]
 * inside the caller's transaction, so [OrderConfirmedKafkaRelay] publishes it to Kafka once that transaction commits, and
 * never if it rolls back.
 *
 * It refuses a call outside a transaction, as `ProductListRevisionMarker` does. A transactional event listener silently drops
 * an event published without one, so the promise to deliver after the commit would be lost without a trace. It is a plain
 * `@Component`, not an `@ApplicationService`: that stereotype's `@Transactional` would open a transaction of its own for such
 * a call, and the event would follow that empty transaction instead of the confirmation.
 */
@Component
class SpringEventOrderEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) : OrderEventPublisher {
    override fun publish(event: OrderConfirmedEvent) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "An order confirmed event can only be published inside the transaction that confirms the order."
        }

        applicationEventPublisher.publishEvent(event)
    }
}
