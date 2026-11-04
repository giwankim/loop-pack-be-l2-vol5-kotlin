package com.loopers.domain.order

/** Whether an outbox row's event still has to reach the broker. A `BLOCKED` row does, but the relay no longer tries it (ADR 0035 rule 7). */
enum class OrderEventOutboxStatus {
    PENDING,
    PUBLISHED,
    BLOCKED,
}
