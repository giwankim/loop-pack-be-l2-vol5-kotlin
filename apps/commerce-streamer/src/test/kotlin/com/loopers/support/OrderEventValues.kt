package com.loopers.support

import com.loopers.adapter.consumer.OrderConfirmedPayload
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The value of an order events record: the envelope of order-events design 2 around [payload]. It is a JSON tree, not
 * bytes, because the shared template's `JacksonJsonSerializer` writes a byte array as a base64 string. A tree it writes as
 * the JSON the tree holds, compact and in the order written, as it writes commerce-api's message, and a string stays the
 * string written.
 */
fun orderEventValue(
    eventId: String,
    payload: String,
    eventType: String = OrderConfirmedPayload.EVENT_TYPE,
    schemaVersion: Int = OrderConfirmedPayload.SCHEMA_VERSION,
): JsonNode {
    return JsonMapper.shared().readTree(
        """{"eventId":"$eventId","eventType":"$eventType","schemaVersion":$schemaVersion,"payload":$payload}""",
    )
}

/**
 * [event] as an order confirmed record, with [confirmedAt] on the wire as written. By default it is the event's own time
 * as `Instant.toString` writes it, which is `ISO_INSTANT`, as commerce-api's mapper writes it.
 */
fun orderConfirmedValue(
    event: PurchaseActivityRecordRequest,
    confirmedAt: String = event.confirmedAt.toString(),
    schemaVersion: Int = OrderConfirmedPayload.SCHEMA_VERSION,
): JsonNode {
    val items = event.items.joinToString(",", "[", "]") { """{"productId":${it.productId},"quantity":${it.quantity}}""" }
    val payload = """{"orderId":${event.orderId},"confirmedAt":"$confirmedAt","items":$items}"""

    return orderEventValue(event.eventId, payload, schemaVersion = schemaVersion)
}
