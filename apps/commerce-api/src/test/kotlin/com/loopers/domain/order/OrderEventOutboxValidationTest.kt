package com.loopers.domain.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * The checks of the plain values an outbox row takes. The class isn't named `OrderEventOutboxTest` because design 12's filter
 * `*OrderEventOutboxTest` would run it with the application's scenes of that name.
 */
class OrderEventOutboxValidationTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank event ID is rejected at creation`(eventId: String) {
        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox(eventId = eventId) }

        assertThat(exception.message).isEqualTo("아웃박스의 이벤트 ID는 공백일 수 없습니다.")
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `an order ID that is not positive is rejected at creation`(orderId: Long) {
        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox(orderId = orderId) }

        assertThat(exception.message).isEqualTo("아웃박스의 주문 ID는 양수여야 합니다.")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank event type is rejected at creation`(eventType: String) {
        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox(eventType = eventType) }

        assertThat(exception.message).isEqualTo("아웃박스의 이벤트 유형은 공백일 수 없습니다.")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `a schema version that is not positive is rejected at creation`(schemaVersion: Int) {
        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox(schemaVersion = schemaVersion) }

        assertThat(exception.message).isEqualTo("아웃박스의 스키마 버전은 양수여야 합니다.")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank message key is rejected at creation`(messageKey: String) {
        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox(messageKey = messageKey) }

        assertThat(exception.message).isEqualTo("아웃박스의 메시지 키는 공백일 수 없습니다.")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank payload is rejected at creation`(payload: String) {
        val exception = assertThrows<InvalidOrderEventOutboxException> { outbox(payload = payload) }

        assertThat(exception.message).isEqualTo("아웃박스의 payload는 공백일 수 없습니다.")
    }

    /** Whether a payload is valid JSON is the check constraint's to refuse (ADR 0034 rule 6), and `OrderEventOutboxRepositoryTest` shows it does. */
    @Test
    fun `a payload that is not JSON is left for the database to refuse`() {
        val row = outbox(payload = "not json")

        assertThat(row.payload).isEqualTo("not json")
    }

    /** The smallest values each check lets through. */
    @Test
    fun `an order ID and a schema version of 1 are kept`() {
        val row = outbox(orderId = 1, schemaVersion = 1)

        assertThat(row.orderId).isEqualTo(1)
        assertThat(row.schemaVersion).isEqualTo(1)
    }

    /** Through the constructor rather than the fixture, which has no argument for most of these columns. */
    private fun outbox(
        eventId: String = "order-confirmed:v1:7",
        orderId: Long = 7,
        eventType: String = "OrderConfirmed",
        schemaVersion: Int = 1,
        messageKey: String = "7",
        payload: String = """{"orderId":7}""",
    ): OrderEventOutbox {
        return OrderEventOutbox(
            eventId = eventId,
            orderId = orderId,
            eventType = eventType,
            schemaVersion = schemaVersion,
            messageKey = messageKey,
            payload = payload,
            nextAttemptAt = Instant.parse("2026-10-27T01:02:03Z"),
        )
    }
}
