package com.loopers.adapter.integration.kafka

import com.loopers.application.order.required.OrderConfirmedEvent
import com.loopers.application.order.required.OrderEventSerializer
import com.loopers.application.order.required.SerializedOrderEvent
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * [OrderEventSerializer] in the shape of [OrderConfirmedMessage] (ADR 0034 rule 4). It writes the message's body with the
 * [JsonMapper] bean, and the key is the same order ID string a record of the event goes under.
 */
@Component
class JacksonOrderEventSerializer(
    private val jsonMapper: JsonMapper,
) : OrderEventSerializer {
    override fun serialize(orderConfirmedEvent: OrderConfirmedEvent): SerializedOrderEvent {
        val orderConfirmedMessage = OrderConfirmedMessage.from(orderConfirmedEvent)

        return SerializedOrderEvent(
            eventType = OrderConfirmedMessage.EVENT_TYPE,
            schemaVersion = OrderConfirmedMessage.SCHEMA_VERSION,
            messageKey = orderConfirmedEvent.orderId.toString(),
            payload = jsonMapper.writeValueAsString(orderConfirmedMessage.payload),
        )
    }
}
