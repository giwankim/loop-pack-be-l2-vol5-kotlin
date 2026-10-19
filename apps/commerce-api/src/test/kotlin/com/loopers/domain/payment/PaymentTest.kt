package com.loopers.domain.payment

import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

class PaymentTest {
    @Test
    fun `a new payment is result unknown and carries its order, key and amount`() {
        val payment = Payment(orderId = 42, idempotencyKey = "checkout-42", amount = Money(15_000))

        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.orderId).isEqualTo(42)
        assertThat(payment.idempotencyKey).isEqualTo("checkout-42")
        assertThat(payment.amount).isEqualTo(Money(15_000))
    }

    /** 10자리는 최소 폭이다. 더 긴 주문 식별자를 자르면 두 주문이 같은 PG 주문 번호를 갖는다. */
    @ParameterizedTest
    @CsvSource("1, LP-ORD-0000000001", "42, LP-ORD-0000000042", "12345678901, LP-ORD-12345678901")
    fun `the PG order number is LP-ORD- and the order id zero-padded to 10 digits`(orderId: Long, pgOrderId: String) {
        val payment = createPayment(orderId = orderId)

        assertThat(payment.pgOrderId).isEqualTo(pgOrderId)
    }

    /** PG 주문 번호는 PG에 보내는 값이라 JVM 기본 로캘의 숫자를 따르지 않는다. 태국 숫자를 쓰는 로캘에서도 ASCII 숫자다. */
    @Test
    fun `the PG order number keeps ASCII digits under a default locale with native digits`() {
        val defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"))
        try {
            val payment = createPayment(orderId = 42)

            assertThat(payment.pgOrderId).isEqualTo("LP-ORD-0000000042")
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank idempotency key is rejected at creation`(idempotencyKey: String) {
        val exception = assertThrows<InvalidPaymentException> { createPayment(orderId = 42, idempotencyKey = idempotencyKey) }

        assertThat(exception.message).isEqualTo("멱등 키는 공백일 수 없습니다.")
    }

    @Test
    fun `an idempotency key of 65 characters is rejected at creation`() {
        val exception = assertThrows<InvalidPaymentException> { createPayment(orderId = 42, idempotencyKey = "k".repeat(65)) }

        assertThat(exception.message).isEqualTo("멱등 키는 64자 이하여야 합니다.")
    }

    @Test
    fun `an idempotency key of 64 characters is kept`() {
        val payment = createPayment(orderId = 42, idempotencyKey = "k".repeat(64))

        assertThat(payment.idempotencyKey).hasSize(64)
    }

    /** 음수는 [Money]가 먼저 거절하므로 결제가 거절하는 것은 0원이다. */
    @Test
    fun `an amount of zero is rejected at creation`() {
        val exception = assertThrows<InvalidPaymentException> { createPayment(orderId = 42, amount = Money.ZERO) }

        assertThat(exception.message).isEqualTo("결제 금액은 1원 이상이어야 합니다.")
    }

    @Test
    fun `an amount of one won is kept`() {
        val payment = createPayment(orderId = 42, amount = Money(1))

        assertThat(payment.amount).isEqualTo(Money(1))
    }
}
