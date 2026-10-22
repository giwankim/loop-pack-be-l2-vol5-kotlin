package com.loopers.domain.order

import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

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
    fun `confirming a draft with points records the point method, the total as the paid amount and the confirmation time`() {
        val order = createOrder(userId = 1)

        order.confirm()

        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.POINT)
        assertThat(order.paidAmount).isEqualTo(order.totalAmount)
        assertThat(order.confirmedAt).isNotNull()
    }

    @Test
    fun `starting payment on a draft makes it a payment pending order paid through the PG`() {
        val order = createOrder(userId = 1)

        order.startPayment()

        assertThat(order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.PG)
        assertThat(order.confirmedAt).isNull()
    }

    @Test
    fun `completing the payment of a payment pending order confirms it and records the confirmation time`() {
        val order = createOrder(userId = 1)
        order.startPayment()

        order.completePayment()

        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.PG)
        assertThat(order.confirmedAt).isNotNull()
    }

    @Test
    fun `failing the payment of a payment pending order makes it a payment failed order`() {
        val order = createOrder(userId = 1)
        order.startPayment()

        order.failPayment()

        assertThat(order.status).isEqualTo(OrderStatus.PAYMENT_FAILED)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.PG)
        assertThat(order.confirmedAt).isNull()
    }

    @ParameterizedTest
    @EnumSource(names = ["PAYMENT_PENDING", "PAYMENT_FAILED"])
    fun `starting payment on an order whose payment has started is rejected and leaves it unchanged`(status: OrderStatus) {
        val order = orderIn(status)

        val exception = assertThrows<OrderStatusConflictException> { order.startPayment() }

        assertThat(exception.message).isEqualTo("결제를 시작한 주문입니다.")
        assertThat(order.status).isEqualTo(status)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.PG)
        assertThat(order.confirmedAt).isNull()
    }

    @Test
    fun `starting payment on an order confirmed through the PG or with points is rejected as already confirmed`() {
        val pgConfirmed = orderIn(OrderStatus.CONFIRMED)
        val pointConfirmed = createOrder(userId = 1).apply { confirm() }

        listOf(pgConfirmed to PaymentMethod.PG, pointConfirmed to PaymentMethod.POINT).forEach { (order, paymentMethod) ->
            val confirmedAt = order.confirmedAt

            val exception = assertThrows<OrderAlreadyConfirmedException> { order.startPayment() }

            assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.")
            assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
            assertThat(order.paymentMethod).isEqualTo(paymentMethod)
            assertThat(order.confirmedAt).isEqualTo(confirmedAt)
        }
    }

    @ParameterizedTest
    @EnumSource(names = ["DRAFT", "CONFIRMED", "PAYMENT_FAILED"])
    fun `completing the payment of an order not payment pending is rejected and leaves it unchanged`(status: OrderStatus) {
        val order = orderIn(status)
        val paymentMethod = order.paymentMethod
        val confirmedAt = order.confirmedAt

        val exception = assertThrows<OrderStatusConflictException> { order.completePayment() }

        assertThat(exception.message).isEqualTo("결제 중인 주문이 아닙니다.")
        assertThat(order.status).isEqualTo(status)
        assertThat(order.paymentMethod).isEqualTo(paymentMethod)
        assertThat(order.confirmedAt).isEqualTo(confirmedAt)
    }

    @ParameterizedTest
    @EnumSource(names = ["DRAFT", "CONFIRMED", "PAYMENT_FAILED"])
    fun `failing the payment of an order not payment pending is rejected and leaves it unchanged`(status: OrderStatus) {
        val order = orderIn(status)
        val paymentMethod = order.paymentMethod
        val confirmedAt = order.confirmedAt

        val exception = assertThrows<OrderStatusConflictException> { order.failPayment() }

        assertThat(exception.message).isEqualTo("결제 중인 주문이 아닙니다.")
        assertThat(order.status).isEqualTo(status)
        assertThat(order.paymentMethod).isEqualTo(paymentMethod)
        assertThat(order.confirmedAt).isEqualTo(confirmedAt)
    }

    @Test
    fun `an order confirmed through the PG or with points has paid its total`() {
        val products = listOf(
            createOrderProduct(productId = 1, unitPrice = Money(1_000), quantity = 5),
            createOrderProduct(productId = 2, unitPrice = Money(2_000), quantity = 1),
        )
        val pgConfirmed = orderIn(OrderStatus.CONFIRMED, products)
        val pointConfirmed = createOrder(userId = 1, products = products).apply { confirm() }

        assertThat(pgConfirmed.paidAmount).isEqualTo(Money(7_000))
        assertThat(pointConfirmed.paidAmount).isEqualTo(Money(7_000))
    }

    @ParameterizedTest
    @EnumSource(names = ["DRAFT", "PAYMENT_PENDING", "PAYMENT_FAILED"])
    fun `an order that is not confirmed has no paid amount`(status: OrderStatus) {
        val order = orderIn(status)

        assertThat(order.paidAmount).isNull()
    }

    @Test
    fun `a payment pending order passes the payment pending check and stays pending`() {
        val order = orderIn(OrderStatus.PAYMENT_PENDING)

        assertDoesNotThrow { order.ensurePaymentPending() }

        assertThat(order.status).isEqualTo(OrderStatus.PAYMENT_PENDING)
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

    /** 상태를 직접 쓰지 않고 그 상태에 이르는 전이를 차례로 부른다. 확정된 주문은 PG로 결제한 주문이다. */
    private fun orderIn(status: OrderStatus, products: List<OrderProduct>? = null): Order {
        return when (status) {
            OrderStatus.DRAFT -> createOrder(userId = 1, products = products)
            OrderStatus.PAYMENT_PENDING -> orderIn(OrderStatus.DRAFT, products).apply { startPayment() }
            OrderStatus.CONFIRMED -> orderIn(OrderStatus.PAYMENT_PENDING, products).apply { completePayment() }
            OrderStatus.PAYMENT_FAILED -> orderIn(OrderStatus.PAYMENT_PENDING, products).apply { failPayment() }
        }
    }
}
