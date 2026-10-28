package com.loopers.support.error

/**
 * The type every exception the streamer throws for an order events record it cannot process extends: an unknown schema
 * version, a payload that cannot be read, a conflict with a recorded fact (ADR 0033 rule 5). Redelivering such a record gives
 * the same result, so retrying it is pointless. The order events listener logs one ERROR line for it, with where the record
 * was read, before it leaves (purchase activity design 5). Today the stopping error handler stops the consumers on any
 * exception; a `DefaultErrorHandler` with a dead-letter topic would register this type as not retryable, together with the
 * request validation's `ConstraintViolationException` (purchase activity design 6).
 */
abstract class UnprocessableOrderEventException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
