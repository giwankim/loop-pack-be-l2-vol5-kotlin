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

        val order = orderIn(OrderStatus.CONFIRMED, products)

        assertThat(order.totalAmount.amount.toString()).isEqualTo("9223372036854775808")
        assertThat(order.paidAmount).isEqualTo(order.totalAmount)
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
    fun `starting payment on a confirmed order is rejected as already confirmed and leaves it unchanged`() {
        val order = orderIn(OrderStatus.CONFIRMED)
        val confirmedAt = order.confirmedAt

        val exception = assertThrows<OrderAlreadyConfirmedException> { order.startPayment() }

        assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.")
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paymentMethod).isEqualTo(PaymentMethod.PG)
        assertThat(order.confirmedAt).isEqualTo(confirmedAt)
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
    fun `a confirmed order has paid its total`() {
        val products = listOf(
            createOrderProduct(productId = 1, unitPrice = Money(1_000), quantity = 5),
            createOrderProduct(productId = 2, unitPrice = Money(2_000), quantity = 1),
        )

        val order = orderIn(OrderStatus.CONFIRMED, products)

        assertThat(order.paidAmount).isEqualTo(Money(7_000))
    }

    @ParameterizedTest
    @EnumSource(names = ["DRAFT", "PAYMENT_PENDING", "PAYMENT_FAILED"])
    fun `an order that is not confirmed has no paid amount`(status: OrderStatus) {
        val order = orderIn(status)

        assertThat(order.paidAmount).isNull()
    }

    @Test
    fun `an order gives each line item's quantity by its product`() {
        val products = listOf(
            createOrderProduct(productId = 2, unitPrice = Money(1_000), quantity = 5),
            createOrderProduct(productId = 1, unitPrice = Money(2_000), quantity = 1),
        )

        val order = createOrder(userId = 1, products = products)

        assertThat(order.productQuantities).isEqualTo(mapOf(1L to 1, 2L to 5))
    }

    @Test
    fun `a draft does not pay through the PG`() {
        val order = createOrder(userId = 1)

        assertThat(order.paysThroughPg).isFalse()
    }

    @ParameterizedTest
    @EnumSource(names = ["PAYMENT_PENDING", "CONFIRMED", "PAYMENT_FAILED"])
    fun `an order whose payment has started pays through the PG`(status: OrderStatus) {
        val order = orderIn(status)

        assertThat(order.paysThroughPg).isTrue()
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
        val order = orderIn(OrderStatus.CONFIRMED)

        val exception = assertThrows<OrderAlreadyConfirmedException> { order.ensureDraft() }

        assertThat(exception.message).isEqualTo("이미 확정된 주문입니다.")
    }

    /**
     * 상태를 직접 쓰지 않고 그 상태에 이르는 전이를 차례로 부른다. 확정된 주문은 PG로 결제한 주문이다.
     * 포인트로 확정된 이전 주문에 이르는 전이는 포인트 결제의 길과 함께 지웠다(ADR 0027). 그런 주문은 저장된 행으로만 있으므로
     * 그 조회와 재확정의 거절은 저장된 행을 읽는 `OrderConfirmationApiTest`가 본다.
     */
    private fun orderIn(status: OrderStatus, products: List<OrderProduct>? = null): Order {
        return when (status) {
            OrderStatus.DRAFT -> createOrder(userId = 1, products = products)
            OrderStatus.PAYMENT_PENDING -> orderIn(OrderStatus.DRAFT, products).apply { startPayment() }
            OrderStatus.CONFIRMED -> orderIn(OrderStatus.PAYMENT_PENDING, products).apply { completePayment() }
            OrderStatus.PAYMENT_FAILED -> orderIn(OrderStatus.PAYMENT_PENDING, products).apply { failPayment() }
        }
    }
}
