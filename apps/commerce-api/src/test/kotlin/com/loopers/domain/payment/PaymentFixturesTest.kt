package com.loopers.domain.payment

import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `PaymentFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다(ADR 0010).
 */
class PaymentFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    /**
     * 진짜 팩터리로 만들므로 만들어지는 것이 곧 멱등 키·금액 규칙의 검사다. 멱등 키나 PG 주문 번호가 겹치면 따로 만든 두 결제를
     * 저장하는 테스트가 가끔 유일 키에 걸린다.
     */
    @Test
    fun `createPayment builds unsaved result unknown payments of the given order with unique keys and 1 to 1_000_000_000 won`() {
        val payments = List(SAMPLES) { createPayment(orderId = 42) }

        assertThat(payments.map { it.idempotencyKey }).doesNotHaveDuplicates()
        assertThat(payments.map { it.pgOrderId }).doesNotHaveDuplicates()
        assertThat(payments).allSatisfy { payment ->
            assertThat(payment.id).isZero()
            assertThat(payment.deletedAt).isNull()
            assertThat(payment.orderId).isEqualTo(42)
            assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
            assertThat(payment.idempotencyKey).hasSize(36)
            assertThat(payment.pgOrderId).hasSize(36)
            assertThat(payment.amount.amount).isBetween(1.toBigInteger(), 1_000_000_000.toBigInteger())
        }
    }

    @Test
    fun `createPayment uses every argument it is given`() {
        val payment = createPayment(
            orderId = 42,
            idempotencyKey = "checkout-42",
            amount = Money(15_000),
            pgOrderId = "0b6f3c1e-5a2d-4c8b-9e7f-1d2a3b4c5d6e",
        )

        assertThat(payment.orderId).isEqualTo(42)
        assertThat(payment.idempotencyKey).isEqualTo("checkout-42")
        assertThat(payment.amount).isEqualTo(Money(15_000))
        assertThat(payment.pgOrderId).isEqualTo("0b6f3c1e-5a2d-4c8b-9e7f-1d2a3b4c5d6e")
    }

    /** 거래 키가 겹치면, 거래 키가 다른 두 PG 거래를 기본값으로 만드는 테스트가 가끔 같은 키를 얻는다. */
    @Test
    fun `createPgTransaction builds transactions matching the payment with unique keys and every status`() {
        val payment = createPayment(orderId = 42)

        val transactions = List(SAMPLES) { createPgTransaction(payment) }

        assertThat(transactions.map { it.transactionKey }).doesNotHaveDuplicates()
        assertThat(transactions.map { it.status }).containsOnly(*PgStatus.entries.toTypedArray())
        assertThat(transactions).allSatisfy { transaction ->
            assertThat(transaction.pgOrderId).isEqualTo(payment.pgOrderId)
            assertThat(transaction.amount).isEqualTo(payment.amount)
        }
    }

    @Test
    fun `createPgTransaction uses every argument it is given`() {
        val payment = createPayment(orderId = 42, amount = Money(15_000))

        val transaction = createPgTransaction(
            payment,
            transactionKey = "TEST-TX-0042",
            pgOrderId = "7c9d2e4f-1b3a-4d5c-8e6f-9a0b1c2d3e4f",
            amount = Money(15_001),
            status = PgStatus.FAILED,
        )

        assertThat(transaction.transactionKey).isEqualTo("TEST-TX-0042")
        assertThat(transaction.pgOrderId).isEqualTo("7c9d2e4f-1b3a-4d5c-8e6f-9a0b1c2d3e4f")
        assertThat(transaction.amount).isEqualTo(Money(15_001))
        assertThat(transaction.status).isEqualTo(PgStatus.FAILED)
    }
}
