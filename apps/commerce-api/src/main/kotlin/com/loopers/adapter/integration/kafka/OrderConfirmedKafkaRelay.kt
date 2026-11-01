package com.loopers.adapter.integration.kafka

import com.loopers.application.order.required.OrderConfirmedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

private val log = KotlinLogging.logger {}

/**
 * Publishes an [OrderConfirmedEvent] to Kafka after the transaction that confirmed the order, TX B, has committed (ADR 0032
 * rules 3–5). [SpringEventOrderEventPublisher] hands the event to Spring inside TX B. Spring calls this listener once TX B
 * commits and never if it rolls back, and `@Async` moves the call onto a virtual thread of the `orderEventsExecutor`.
 *
 * The publish is observed, not awaited: the outcome is one log line, and nothing is thrown. A broker problem therefore
 * never reaches the confirmation, which has already committed and answered. A failed publish leaves the order confirmed
 * and is the publish gap of order-events design 6.
 */
@Component
class OrderConfirmedKafkaRelay(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val properties: OrderEventsProperties,
) {
    /**
     * Publishes [event] as an [OrderConfirmedMessage] keyed by the order ID, so every record of one order lands on one
     * partition. A successful publish logs one INFO line with the eventId and where the record landed: topic, partition and
     * offset. A failed one logs one WARN line with the eventId, the order ID and the cause, whether `send()` threw or its
     * future failed. That future ends when the broker acknowledges the record or the producer gives up on it, at
     * `delivery.timeout.ms` at the latest, so no shorter wait is set (order-events design 5).
     *
     * It returns `Unit` on purpose. Spring runs an after-commit listener in TX B's `afterCompletion`, before it releases TX B's
     * connection, so a synchronous publish would hold that connection for the round trip to the broker, and a `send` waiting
     * for the topic's metadata would hold the confirming thread for up to `max.block.ms`. Its exception would not reach the
     * confirmation either way: Spring logs an exception from `afterCompletion` at ERROR and goes on. `@Async` hands the call
     * to the executor and returns at once. The exception of an `@Async` method that returns nothing goes to Spring's default
     * `AsyncUncaughtExceptionHandler`, which logs it at ERROR with the method and the stack trace, though not the argument:
     * the backstop for a bug in the relay itself, such as building the envelope. A method that returned a `Future` would put
     * that exception into a future nobody reads.
     */
    @Async(OrderEventsKafkaConfig.ORDER_EVENTS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun relay(event: OrderConfirmedEvent) {
        val message = OrderConfirmedMessage.from(event)

        try {
            kafkaTemplate.send(properties.topic, event.orderId.toString(), message)
                .whenComplete { result: SendResult<Any, Any>?, exception: Throwable? ->
                    if (exception != null) {
                        logFailure(event, exception)
                        return@whenComplete
                    }

                    val metadata = checkNotNull(result).recordMetadata
                    log.info {
                        "Published the order confirmed event. eventId=${event.eventId}, topic=${metadata.topic()}, " +
                            "partition=${metadata.partition()}, offset=${metadata.offset()}"
                    }
                }
        } catch (e: Exception) {
            logFailure(event, e)
        }
    }

    /** The cause's type and message without the stack trace: one line per failed publish. */
    private fun logFailure(event: OrderConfirmedEvent, cause: Throwable) {
        log.warn {
            "Could not publish the order confirmed event. eventId=${event.eventId}, orderId=${event.orderId}, cause=$cause"
        }
    }
}
