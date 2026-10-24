package com.loopers.application.order.provided

import com.loopers.domain.order.createOrder
import com.loopers.domain.payment.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 결제 상태는 결제수단이 PG인 주문에만 있다(ADR 0028 규칙 1). 어긴 Info는 결제수단과 맞지 않는 응답을 실으므로 만들 때 거절된다. */
class OrderInfoTest {
    @Test
    fun `an order info rejects a payment status on a draft`() {
        val draft = createOrder(userId = 1)

        val exception = assertThrows<IllegalArgumentException> { OrderInfo(draft, PaymentStatus.SUCCESS) }

        assertThat(exception.message).startsWith("결제 상태는 결제수단이 PG인 주문에만 있다.")
    }

    @Test
    fun `an order info rejects an order paying through the PG without its payment status`() {
        val pending = createOrder(userId = 1).apply { startPayment() }

        val exception = assertThrows<IllegalArgumentException> { OrderInfo(pending, paymentStatus = null) }

        assertThat(exception.message).startsWith("결제 상태는 결제수단이 PG인 주문에만 있다.")
    }
}
