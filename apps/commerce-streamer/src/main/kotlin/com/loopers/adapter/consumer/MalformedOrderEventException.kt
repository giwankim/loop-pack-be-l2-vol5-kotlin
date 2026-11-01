package com.loopers.adapter.consumer

import com.loopers.support.error.UnprocessableOrderEventException

/**
 * An order confirmed event whose payload cannot be read as an [OrderConfirmedPayload]: a field is missing, null or of the
 * wrong JSON type (purchase activity design 6). Redelivering it gives the same answer, so it is an unprocessable event (ADR
 * 0033 rule 5). An envelope that cannot be read at all fails earlier, in the message converter.
 */
class MalformedOrderEventException(
    eventId: String,
    reason: String,
    cause: Throwable? = null,
) : UnprocessableOrderEventException(
    "The payload of order confirmed event $eventId cannot be read: $reason",
    cause,
)
