package com.loopers.application.order.required

import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderIn
import com.loopers.domain.order.createOrderProduct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class OrderConfirmedEventTest {
    companion object {
        /** When [orderIn] confirms an order. Already in microseconds, so the order keeps it as it is. */
        private val CONFIRMED_AT: Instant = Instant.parse("2026-10-27T01:02:03.123456Z")
    }

    /**
     * An unsaved order's ID is 0, so this test cannot tell the order's ID from a constant. `OrderEventPublishingTest` pins the
     * ID and event ID of a saved order.
     */
    @Test
    fun `an event made from a confirmed order carries its ID, confirmation time and quantities in ascending product ID order`() {
        val products = listOf(
            createOrderProduct(productId = 30, quantity = 3),
            createOrderProduct(productId = 10, quantity = 1),
            createOrderProduct(productId = 20, quantity = 2),
        )
        val order = createOrderIn(OrderStatus.CONFIRMED, products, confirmedAt = CONFIRMED_AT)

        val event = OrderConfirmedEvent.from(order)

        assertThat(event.orderId).isEqualTo(order.id)
        assertThat(event.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        assertThat(event.items).containsExactly(
            OrderConfirmedEvent.Item(productId = 10, quantity = 1),
            OrderConfirmedEvent.Item(productId = 20, quantity = 2),
            OrderConfirmedEvent.Item(productId = 30, quantity = 3),
        )
    }

    @Test
    fun `the event ID is derived from the order ID under a versioned prefix`() {
        val event = OrderConfirmedEvent(
            orderId = 42,
            confirmedAt = CONFIRMED_AT,
            items = listOf(OrderConfirmedEvent.Item(productId = 7, quantity = 2)),
        )

        assertThat(event.eventId).isEqualTo("order-confirmed:v1:42")
    }

    @ParameterizedTest
    @EnumSource(OrderStatus::class, names = ["CONFIRMED"], mode = EnumSource.Mode.EXCLUDE)
    fun `an order that is not confirmed has no confirmation to publish`(status: OrderStatus) {
        val order = createOrderIn(status)

        assertThrows<IllegalArgumentException> { OrderConfirmedEvent.from(order) }
    }
}
