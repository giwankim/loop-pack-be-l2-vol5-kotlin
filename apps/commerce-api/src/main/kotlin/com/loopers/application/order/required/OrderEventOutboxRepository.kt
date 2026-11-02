package com.loopers.application.order.required

import com.loopers.domain.order.OrderEventOutbox
import org.springframework.data.repository.Repository

/** The outbox's store. Spring Data makes the implementation. A row that isn't there is null. */
interface OrderEventOutboxRepository : Repository<OrderEventOutbox, Long> {
    fun save(orderEventOutbox: OrderEventOutbox): OrderEventOutbox

    /** The row of one event. `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID` allows at most one. */
    fun findByEventId(eventId: String): OrderEventOutbox?

    fun count(): Long
}
