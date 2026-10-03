package com.loopers.domain.order

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class OrderTest {
    @Test
    fun `confirming a draft records the stored total and the confirmation time`() {
        val order = createOrder(userId = 1)

        order.confirm()

        assertAll(
            { assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED) },
            { assertThat(order.paidAmount).isEqualTo(order.totalAmount) },
            { assertThat(order.confirmedAt).isNotNull() },
        )
    }

    @Test
    fun `a draft passes the confirmation check and stays a draft`() {
        val order = createOrder(userId = 1)

        assertDoesNotThrow { order.validateConfirmable() }

        assertThat(order.status).isEqualTo(OrderStatus.DRAFT)
    }

    @Test
    fun `a confirmed order fails the confirmation check`() {
        val order = createOrder(userId = 1)
        order.confirm()

        val exception = assertThrows<OrderAlreadyConfirmedException> { order.validateConfirmable() }

        assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.")
    }

    /**
     * application이 차감 전에 [Order.validateConfirmable]로 먼저 거절하므로 정상 흐름은 여기에 닿지 않는다. 그래도
     * 애그리거트가 스스로 거절해야 결제액·확정 시각을 두 번 쓰는 길이 남지 않는다.
     */
    @Test
    fun `confirming an already confirmed order is rejected and keeps the first payment and time`() {
        val order = createOrder(userId = 1)
        order.confirm()
        val paidAmount = order.paidAmount
        val confirmedAt = order.confirmedAt

        val exception = assertThrows<OrderAlreadyConfirmedException> { order.confirm() }

        assertAll(
            { assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.") },
            { assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED) },
            { assertThat(order.paidAmount).isEqualTo(paidAmount) },
            { assertThat(order.confirmedAt).isEqualTo(confirmedAt) },
        )
    }
}
