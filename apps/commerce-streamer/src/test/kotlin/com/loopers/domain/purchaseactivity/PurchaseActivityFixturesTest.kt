package com.loopers.domain.purchaseactivity

import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Checks the defaults `PurchaseActivityFixtures.kt` promises. A default out of range breaks the tests that use it only now and
 * then, so drawing many here shows it every time (ADR 0010). The real constructor builds every activity, so building them all
 * without an exception is the constructor's own check.
 */
class PurchaseActivityFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    /** If two drawn activities shared an order and a product, a test saving both would now and then hit the unique key. */
    @Test
    fun `createPurchaseActivity builds unsaved activities of distinct pairs with a quantity of 1 to 10 confirmed since 1970`() {
        val activities = List(SAMPLES) { createPurchaseActivity() }
        val now = Instant.now()

        assertThat(activities.map { it.orderId to it.productId }).doesNotHaveDuplicates()
        assertThat(activities).allSatisfy { activity ->
            assertThat(activity.id).isZero()
            assertThat(activity.deletedAt).isNull()
            assertThat(activity.orderId).isPositive()
            assertThat(activity.productId).isPositive()
            assertThat(activity.quantity).isBetween(1, 10)
            assertThat(activity.confirmedAt).isAfterOrEqualTo(Instant.EPOCH).isBefore(now)
        }
    }

    @Test
    fun `createPurchaseActivity uses every argument it is given`() {
        val activity = createPurchaseActivity(
            orderId = 7,
            productId = 3,
            quantity = 11,
            confirmedAt = Instant.parse("2026-10-27T01:02:03.123456Z"),
        )

        assertThat(activity.orderId).isEqualTo(7)
        assertThat(activity.productId).isEqualTo(3)
        assertThat(activity.quantity).isEqualTo(11)
        assertThat(activity.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
    }

    /**
     * Requests built apart never share an order or an event. The time has no digit past the microsecond, as commerce-api
     * publishes it, so a test comparing a stored time with the request's sees the same value.
     */
    @Test
    fun `createPurchaseActivityRecordRequest satisfies its constraints with distinct orders and microsecond times`() {
        val requests = List(SAMPLES) { createPurchaseActivityRecordRequest() }
        val now = Instant.now()

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests.map { it.orderId }).doesNotHaveDuplicates()
        assertThat(requests).allSatisfy { request ->
            assertThat(request.orderId).isPositive()
            assertThat(request.eventId).isEqualTo("order-confirmed:v1:${request.orderId}")
            assertThat(request.confirmedAt).isAfterOrEqualTo(Instant.EPOCH).isBefore(now)
            assertThat(request.confirmedAt.nano % 1_000).isZero()
            assertThat(request.items).hasSizeBetween(1, 5).allSatisfy { item ->
                assertThat(item.productId).isPositive()
                assertThat(item.quantity).isBetween(1, 10)
            }
        }
    }

    @Test
    fun `createPurchaseActivityRecordRequest uses every argument it is given`() {
        val request = createPurchaseActivityRecordRequest(
            eventId = "order-confirmed:v2:7",
            orderId = 7,
            confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"),
            items = listOf(PurchaseActivityRecordRequest.Item(productId = 3, quantity = 11)),
        )

        assertThat(request).isEqualTo(
            PurchaseActivityRecordRequest(
                eventId = "order-confirmed:v2:7",
                orderId = 7,
                confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"),
                items = listOf(PurchaseActivityRecordRequest.Item(productId = 3, quantity = 11)),
            ),
        )
    }

    @Test
    fun `createPurchaseActivityRecordRequest derives the event id from the order id it is given`() {
        val request = createPurchaseActivityRecordRequest(orderId = 7)

        assertThat(request.eventId).isEqualTo("order-confirmed:v1:7")
    }
}
