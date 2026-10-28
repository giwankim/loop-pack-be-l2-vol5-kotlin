package com.loopers.domain.purchaseactivity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Checks the defaults `ProcessedOrderConfirmationFixtures.kt` promises. A default out of range breaks the tests that use it
 * only now and then, so drawing many here shows it every time (ADR 0010). The real constructor builds every confirmation, so
 * building them all without an exception is the constructor's own check.
 */
class ProcessedOrderConfirmationFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    /** If two drawn confirmations shared an order or an event, a test saving both would now and then hit a unique key. */
    @Test
    fun `createProcessedOrderConfirmation builds unsaved confirmations of distinct orders and events confirmed since 1970`() {
        val confirmations = List(SAMPLES) { createProcessedOrderConfirmation() }
        val now = Instant.now()

        assertThat(confirmations.map { it.orderId }).doesNotHaveDuplicates()
        assertThat(confirmations.map { it.eventId }).doesNotHaveDuplicates()
        assertThat(confirmations).allSatisfy { confirmation ->
            assertThat(confirmation.id).isZero()
            assertThat(confirmation.deletedAt).isNull()
            assertThat(confirmation.orderId).isPositive()
            assertThat(confirmation.confirmedAt).isAfterOrEqualTo(Instant.EPOCH).isBefore(now)
        }
    }

    @Test
    fun `createProcessedOrderConfirmation derives the event id from the order id as commerce-api does`() {
        val drawn = createProcessedOrderConfirmation()
        val given = createProcessedOrderConfirmation(orderId = 7)

        assertThat(drawn.eventId).isEqualTo("order-confirmed:v1:${drawn.orderId}")
        assertThat(given.eventId).isEqualTo("order-confirmed:v1:7")
    }

    @Test
    fun `createProcessedOrderConfirmation uses every argument it is given`() {
        val confirmation = createProcessedOrderConfirmation(
            eventId = "order-confirmed:v1:8",
            orderId = 7,
            confirmedAt = Instant.parse("2026-10-27T01:02:03.123456Z"),
        )

        assertThat(confirmation.eventId).isEqualTo("order-confirmed:v1:8")
        assertThat(confirmation.orderId).isEqualTo(7)
        assertThat(confirmation.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
    }
}
