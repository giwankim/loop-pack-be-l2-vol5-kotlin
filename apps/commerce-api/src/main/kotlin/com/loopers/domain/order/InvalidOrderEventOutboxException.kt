package com.loopers.domain.order

/**
 * An outbox row can't hold these values. Unlike the other `Invalid…Exception`s this is not a `RuleViolationException`: no request
 * carries these values, so the web adapter shouldn't answer 400 as if the caller were wrong. It fails TX B the way a failed
 * serialization does, and the caller gets the same answer (order outbox design 5).
 */
class InvalidOrderEventOutboxException(message: String) : RuntimeException(message)
