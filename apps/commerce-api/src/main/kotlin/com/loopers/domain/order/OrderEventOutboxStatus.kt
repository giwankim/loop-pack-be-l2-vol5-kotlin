package com.loopers.domain.order

/** Whether an outbox row's event still has to reach the broker. Step 2's relay decides whether a permanent failure needs a value. */
enum class OrderEventOutboxStatus {
    PENDING,
    PUBLISHED,
}
