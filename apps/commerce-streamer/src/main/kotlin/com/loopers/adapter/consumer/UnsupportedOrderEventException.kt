package com.loopers.adapter.consumer

import com.loopers.support.error.UnprocessableOrderEventException

/**
 * An order confirmed event at a schema version the streamer does not read (purchase activity design 6). Its payload may mean
 * something else under that version, so it is not read as version [OrderConfirmedPayload.SCHEMA_VERSION]. Redelivering it gives
 * the same answer, so it is an unprocessable event (ADR 0033 rule 5).
 */
class UnsupportedOrderEventException(
    eventId: String,
    schemaVersion: Int,
) : UnprocessableOrderEventException(
    "Order confirmed event $eventId is at schema version $schemaVersion, " +
        "and the streamer reads version ${OrderConfirmedPayload.SCHEMA_VERSION}.",
)
