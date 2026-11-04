package com.loopers.adapter.integration.kafka

import com.loopers.application.order.required.OrderEventPublishResult
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.domain.order.OrderEventOutbox
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

private val log = KotlinLogging.logger {}

/**
 * [OrderEventPublisher] through the shared template (ADR 0035 rule 12). It sends every row before it waits for any, so the batch shares
 * the producer's linger and delivery timeout. The futures have no local timeout: `delivery.timeout.ms` ends them, so there is one wait
 * to reason about (rule 5). A row counts as published only once its future succeeded, because `send` returning means only that the
 * producer took the record.
 *
 * Failures don't become results yet: a failed rebuild, send or future leaves the round as an exception, and the rows it took with it come
 * due when their leases end. Issue 201 turns each into that row's own result.
 */
@Component
class KafkaOrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val orderEventsProperties: OrderEventsProperties,
    private val jsonMapper: JsonMapper,
) : OrderEventPublisher {
    override fun publish(outboxes: List<OrderEventOutbox>): List<OrderEventPublishResult> {
        val pendingSends = outboxes.map { outbox ->
            outbox to kafkaTemplate.send(orderEventsProperties.topic, outbox.messageKey, rebuildMessage(outbox))
        }

        return pendingSends.map { (outbox, sendFuture) ->
            val recordMetadata = sendFuture.join().recordMetadata
            log.info {
                "Published the order confirmed event. eventId=${outbox.eventId}, topic=${recordMetadata.topic()}, " +
                    "partition=${recordMetadata.partition()}, offset=${recordMetadata.offset()}"
            }

            OrderEventPublishResult(orderEventOutboxId = outbox.id, failureMessage = null)
        }
    }

    /** From the row's columns and payload alone, so a publish never reads current product data (ADR 0034 rule 9). */
    private fun rebuildMessage(outbox: OrderEventOutbox): OrderConfirmedMessage {
        return OrderConfirmedMessage(
            eventId = outbox.eventId,
            eventType = outbox.eventType,
            schemaVersion = outbox.schemaVersion,
            payload = jsonMapper.readValue(outbox.payload, OrderConfirmedMessage.Payload::class.java),
        )
    }
}
