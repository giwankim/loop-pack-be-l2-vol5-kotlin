package com.loopers.application.order.required

/**
 * Turns an event into what an outbox row keeps (ADR 0034 rule 4). The wire format is the adapter's knowledge, so the outbox's
 * policy stays in the application's language and the application still doesn't know Kafka. The implementation is in
 * `adapter.integration.kafka`, and a failure to serialize is TX B's failure, which rolls the confirmation back (rule 2).
 */
interface OrderEventSerializer {
    fun serialize(orderConfirmedEvent: OrderConfirmedEvent): SerializedOrderEvent
}

/**
 * The columns of an outbox row that the wire format decides. [payload] is the body's JSON, never the envelope: the event ID,
 * type and version are columns, so a second copy in the JSON could drift from them (ADR 0034 rule 5). The event gives the event
 * ID and order ID.
 */
data class SerializedOrderEvent(
    val eventType: String,
    val schemaVersion: Int,
    val messageKey: String,
    val payload: String,
)
