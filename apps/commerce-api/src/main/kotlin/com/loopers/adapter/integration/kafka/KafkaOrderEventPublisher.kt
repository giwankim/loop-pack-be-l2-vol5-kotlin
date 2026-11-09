package com.loopers.adapter.integration.kafka

import com.loopers.application.order.required.OrderEventPublishResult
import com.loopers.application.order.required.OrderEventPublisher
import com.loopers.domain.order.OrderEventOutbox
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CompletableFuture

private val log = KotlinLogging.logger {}

/**
 * [OrderEventPublisher] through the shared template (ADR 0035 rule 12). It sends every row before it waits for any, so the batch shares
 * the producer's linger and delivery timeout. The futures have no local timeout: `delivery.timeout.ms` ends them, so there is one wait
 * to reason about (rule 5). A row counts as published only once its future succeeded, because `send` returning means only that the
 * producer took the record.
 *
 * Each row gets its own result (rule 4). A failed rebuild and a `send` that throws become failed futures, so they meet a failed send
 * on one path, and the rest of the batch is unaffected.
 */
@Component
class KafkaOrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val orderEventsProperties: OrderEventsProperties,
    private val jsonMapper: JsonMapper,
) : OrderEventPublisher {
    override fun publish(outboxes: List<OrderEventOutbox>): List<OrderEventPublishResult> {
        val pendingSends = outboxes.map { outbox -> outbox to send(outbox) }

        return pendingSends.map { (outbox, sendFuture) -> awaitResult(outbox, sendFuture) }
    }

    private fun send(outbox: OrderEventOutbox): CompletableFuture<SendResult<Any, Any>> {
        try {
            return kafkaTemplate.send(orderEventsProperties.topic, outbox.messageKey, rebuildMessage(outbox))
        } catch (exception: Exception) {
            return CompletableFuture.failedFuture(exception)
        }
    }

    private fun awaitResult(
        outbox: OrderEventOutbox,
        sendFuture: CompletableFuture<SendResult<Any, Any>>,
    ): OrderEventPublishResult {
        try {
            val recordMetadata = sendFuture.join().recordMetadata
            log.info {
                "Published the order confirmed event. eventId=${outbox.eventId}, topic=${recordMetadata.topic()}, " +
                    "partition=${recordMetadata.partition()}, offset=${recordMetadata.offset()}"
            }

            return OrderEventPublishResult(orderEventOutboxId = outbox.id, failureMessage = null)
        } catch (exception: Exception) {
            val failureMessage = describeFailure(exception)
            log.warn { "Failed to publish the order confirmed event. eventId=${outbox.eventId}, cause=$failureMessage" }

            return OrderEventPublishResult(orderEventOutboxId = outbox.id, failureMessage = failureMessage)
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

    /**
     * The type and message of the root cause, since the template and the future only wrap it. A failure with Jackson in its chain is the
     * exception: both Jackson's message and its cause's (a date's `DateTimeParseException`) quote the refused value, which is a piece of
     * the payload. So it names Jackson's type and the JSON path instead, and the text stays safe to log and store.
     */
    private fun describeFailure(exception: Exception): String {
        val causes = generateSequence<Throwable>(exception) { it.cause }
        val jacksonException = causes.filterIsInstance<JacksonException>().firstOrNull()

        if (jacksonException != null) {
            return "${jacksonException.javaClass.name}: at ${jacksonException.pathReference}"
        }

        val rootCause = causes.last()

        return "${rootCause.javaClass.name}: ${rootCause.message}"
    }
}
