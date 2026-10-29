package com.loopers.adapter.consumer

import tools.jackson.databind.JsonNode

/**
 * An order events record as the listener reads it: the envelope of order-events design 2 (purchase activity design 2). The
 * metadata is read first, and [payload] stays a JSON tree until [eventType] and [schemaVersion] say what it is, because
 * `order-events` can carry more than one type of event. It is the streamer's own type: commerce-api writes the same bytes from
 * its `OrderConfirmedMessage`, and the contract is the bytes.
 */
data class OrderEventMessage(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val payload: JsonNode,
)
