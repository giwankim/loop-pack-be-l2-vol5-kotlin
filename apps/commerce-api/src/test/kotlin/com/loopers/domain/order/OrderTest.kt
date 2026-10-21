package com.loopers.domain.order

import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class OrderTest {
    @Test
    fun `an order line amount can exceed Long MAX_VALUE`() {
        val product = createOrderProduct(unitPrice = Money(Long.MAX_VALUE), quantity = 2)

        val order = createOrder(userId = 1, products = listOf(product))

        assertThat(order.lineItems.single().lineAmount.amount.toString()).isEqualTo("18446744073709551614")
    }

    @Test
    fun `an order total can exceed Long MAX_VALUE`() {
        val products = listOf(1L, 2L).map { productId ->
            createOrderProduct(productId = productId, unitPrice = Money(Long.MAX_VALUE / 2 + 1), quantity = 1)
        }

        val order = createOrder(userId = 1, products = products)
        order.confirm()

        assertThat(order.totalAmount.amount.toString()).isEqualTo("9223372036854775808")
        assertThat(order.paidAmount).isEqualTo(order.totalAmount)
    }

    @Test
    fun `confirming a draft records the total as the paid amount and the confirmation time`() {
        val order = createOrder(userId = 1)

        order.confirm()

        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paidAmount).isEqualTo(order.totalAmount)
        assertThat(order.confirmedAt).isNotNull()
    }

    @Test
    fun `a draft passes the confirmation check and stays a draft`() {
        val order = createOrder(userId = 1)

        assertDoesNotThrow { order.ensureDraft() }

        assertThat(order.status).isEqualTo(OrderStatus.DRAFT)
    }

    @Test
    fun `a confirmed order fails the confirmation check`() {
        val order = createOrder(userId = 1)
        order.confirm()

        val exception = assertThrows<OrderAlreadyConfirmedException> { order.ensureDraft() }

        assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.")
    }

    /**
     * application이 차감 전에 [Order.ensureDraft]로 먼저 거절하므로 정상 흐름은 여기에 닿지 않는다. 그래도
     * 애그리거트가 스스로 거절해야 결제액·확정 시각을 두 번 쓰는 길이 남지 않는다.
     */
    @Test
    fun `confirming an already confirmed order is rejected and keeps the first payment and time`() {
        val order = createOrder(userId = 1)
        order.confirm()
        val paidAmount = order.paidAmount
        val confirmedAt = order.confirmedAt

        val exception = assertThrows<OrderAlreadyConfirmedException> { order.confirm() }

        assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.")
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paidAmount).isEqualTo(paidAmount)
        assertThat(order.confirmedAt).isEqualTo(confirmedAt)
    }
}
