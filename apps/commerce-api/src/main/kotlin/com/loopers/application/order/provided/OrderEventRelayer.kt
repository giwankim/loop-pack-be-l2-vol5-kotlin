package com.loopers.application.order.provided

/** Publishes the outbox rows whose turn has come (ADR 0035). A trigger calls it, outside any transaction. */
interface OrderEventRelayer {
    fun publishDueEvents()
}
