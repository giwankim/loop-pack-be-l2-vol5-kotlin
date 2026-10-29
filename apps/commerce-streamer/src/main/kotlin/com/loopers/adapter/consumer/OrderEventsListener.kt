package com.loopers.adapter.consumer

import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordResult
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecorder
import com.loopers.support.error.UnprocessableOrderEventException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

private val log = KotlinLogging.logger {}

/**
 * Records the order confirmed events commerce-api publishes to `order-events` as purchase activity (ADR 0033 rules 3 and 6,
 * purchase activity design 5). Each record is read as an [OrderEventMessage] by the factory's converter, and its payload is
 * read once the envelope says what it is.
 *
 * The listener acknowledges a record only after [PurchaseActivityRecorder.record] has returned, so after its transaction has
 * committed, and `MANUAL_IMMEDIATE` commits the offset right there. A process that stops in between reads the record again,
 * and recording the same fact again writes nothing. Every exception leaves the listener unacknowledged, and the factory's
 * error handler stops the container on it (ADR 0033 rule 5).
 *
 * The listener `id` lets a test and step 3 find the container in the `KafkaListenerEndpointRegistry`. `idIsGroup` is false
 * because by default the `id` becomes the group, over `spring.kafka.consumer.group-id`.
 */
@Component
class OrderEventsListener(
    private val purchaseActivityRecorder: PurchaseActivityRecorder,
    private val jsonMapper: JsonMapper,
) {
    /**
     * Records [message] and acknowledges it, logging one INFO line with where it was read and what recording did. A record of
     * an event type the streamer does not record is skipped: acknowledged with an INFO line, and nothing is read further
     * (`CONTEXT.md` 건너뛰다). An order confirmed event at another schema version is refused with
     * [UnsupportedOrderEventException], and a payload that cannot be read with [MalformedOrderEventException].
     *
     * An unprocessable event (`CONTEXT.md` 처리할 수 없는 이벤트) is refused with one ERROR line before the exception leaves:
     * an [UnprocessableOrderEventException], or a `ConstraintViolationException` from the request's validation. Those are the
     * two types a `DefaultErrorHandler` would register as not retryable (purchase activity design 6), and the line is the
     * evidence a person gets (design 5). It names the order by the record's [key], the order ID's string by the contract
     * (order-events design 2), because a payload that cannot be read, or is not read at its schema version, gives none.
     */
    @KafkaListener(
        id = "order-events",
        idIsGroup = false,
        topics = ["\${order-events.topic:${OrderEventsProperties.DEFAULT_TOPIC}}"],
        containerFactory = OrderEventsListenerConfig.ORDER_EVENTS_LISTENER,
    )
    fun listen(
        message: OrderEventMessage,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) key: String?,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        acknowledgment: Acknowledgment,
    ) {
        if (message.eventType != OrderConfirmedPayload.EVENT_TYPE) {
            log.info {
                "Skipped an order event of a type the streamer does not record. eventId=${message.eventId}, " +
                    "eventType=${message.eventType}, partition=$partition, offset=$offset"
            }
            acknowledgment.acknowledge()
            return
        }

        try {
            recordOrderConfirmed(message, partition, offset)
        } catch (e: UnprocessableOrderEventException) {
            logRefusal(message, key, partition, offset, e)
            throw e
        } catch (e: ConstraintViolationException) {
            logRefusal(message, key, partition, offset, e)
            throw e
        }

        acknowledgment.acknowledge()
    }

    /** Records [message], an order confirmed event, and logs the INFO line. */
    private fun recordOrderConfirmed(message: OrderEventMessage, partition: Int, offset: Long) {
        if (message.schemaVersion != OrderConfirmedPayload.SCHEMA_VERSION) {
            throw UnsupportedOrderEventException(message.eventId, message.schemaVersion)
        }

        val payload = readPayload(message)
        val result = recordSettlingARace(payload.toRecordRequest(message.eventId))
        log.info {
            "Recorded the order confirmed event. eventId=${message.eventId}, orderId=${payload.orderId}, " +
                "partition=$partition, offset=$offset, result=$result"
        }
    }

    private fun logRefusal(message: OrderEventMessage, key: String?, partition: Int, offset: Long, cause: RuntimeException) {
        log.error {
            "Refused an order confirmed event that cannot be processed. eventId=${message.eventId}, orderId=$key, " +
                "partition=$partition, offset=$offset, cause=$cause"
        }
    }

    /** The payload of [message], which is an order confirmed event at the schema version the streamer reads. */
    private fun readPayload(message: OrderEventMessage): OrderConfirmedPayload {
        val payload: OrderConfirmedPayload? = try {
            jsonMapper.treeToValue(message.payload, OrderConfirmedPayload::class.java)
        } catch (e: JacksonException) {
            throw MalformedOrderEventException(message.eventId, e.originalMessage, e)
        }

        return payload ?: throw MalformedOrderEventException(message.eventId, "the payload is null.")
    }

    /**
     * Records [request], and once more if the first call lost a race to the unique keys. Two consumers record the same event at
     * once only across a rebalance, and the one whose insert loses gets a [DataIntegrityViolationException] after its
     * transaction rolled back. Calling again reads what the other wrote, so the answer is [PurchaseActivityRecordResult
     * .ALREADY_RECORDED] or a conflict (ADR 0033 rule 3). This is not a retry of the error handler: a second violation leaves
     * the listener and stops the container.
     */
    private fun recordSettlingARace(request: PurchaseActivityRecordRequest): PurchaseActivityRecordResult {
        return try {
            purchaseActivityRecorder.record(request)
        } catch (e: DataIntegrityViolationException) {
            purchaseActivityRecorder.record(request)
        }
    }
}
