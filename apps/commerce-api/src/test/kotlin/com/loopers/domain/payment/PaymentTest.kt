package com.loopers.domain.payment

import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class PaymentTest {
    companion object {
        private const val TRANSACTION_KEY = "TEST-TX-0042"
        private const val PG_ORDER_ID = "0b6f3c1e-5a2d-4c8b-9e7f-1d2a3b4c5d6e"
        private const val OTHER_PG_ORDER_ID = "7c9d2e4f-1b3a-4d5c-8e6f-9a0b1c2d3e4f"
    }

    /** 결제를 시작하는 쪽은 PG 주문 번호를 고르지 않는다. 생성기가 결제마다 한 번 새 값을 만든다(ADR 0029). */
    @Test
    fun `start begins a result unknown payment with its order, key, amount and a PG order ID from one generator call`() {
        var calls = 0
        val generator = PgOrderIdGenerator {
            calls++
            PG_ORDER_ID
        }

        val payment = Payment.start(
            orderId = 42,
            idempotencyKey = "checkout-42",
            amount = Money(15_000),
            pgOrderIdGenerator = generator,
        )

        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.orderId).isEqualTo(42)
        assertThat(payment.idempotencyKey).isEqualTo("checkout-42")
        assertThat(payment.amount).isEqualTo(Money(15_000))
        assertThat(payment.pgOrderId).isEqualTo(PG_ORDER_ID)
        assertThat(calls).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank idempotency key is rejected at creation`(idempotencyKey: String) {
        val exception = assertThrows<InvalidPaymentException> { createPayment(orderId = 42, idempotencyKey = idempotencyKey) }

        assertThat(exception.message).isEqualTo("멱등 키는 공백일 수 없습니다.")
    }

    /** 키는 떼거나 접지 않고 그대로 비교하므로(ADR 0025 규칙 2) 앞뒤나 가운데의 공백도 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = ["checkout 42", " checkout-42", "checkout-42 ", "checkout\t42", "checkout\n42"])
    fun `an idempotency key containing whitespace is rejected at creation`(idempotencyKey: String) {
        val exception = assertThrows<InvalidPaymentException> { createPayment(orderId = 42, idempotencyKey = idempotencyKey) }

        assertThat(exception.message).isEqualTo("멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다.")
    }

    /** ASCII 밖의 문자는 ASCII와 닮아 보여도(전각 문자, 줄 바꿈 없는 공백) 거절한다. ASCII의 제어 문자도 보이지 않으므로 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = ["결제-42", "café-42", "ｃｈｅｃｋｏｕｔ", "checkout\u00A042", "checkout\u000042", "checkout\u007F42"])
    fun `an idempotency key with a character outside visible ASCII is rejected at creation`(idempotencyKey: String) {
        val exception = assertThrows<InvalidPaymentException> { createPayment(orderId = 42, idempotencyKey = idempotencyKey) }

        assertThat(exception.message).isEqualTo("멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다.")
    }

    /** 보이는 ASCII는 `!`(0x21)부터 `~`(0x7E)까지 94자다. 64자 한도 안에 들도록 두 키로 나눠 모두 받아들이는지 본다. */
    @Test
    fun `every visible ASCII character is allowed in an idempotency key`() {
        val keys = ('!'..'~').joinToString("").chunked(64)

        val payments = keys.map { createPayment(orderId = 42, idempotencyKey = it) }

        assertThat(payments.map { it.idempotencyKey }).isEqualTo(keys)
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

    /** 재요청인지는 결제를 시작한 키와 그대로 견주어 정한다. 대소문자만 다른 키는 다른 키다(ADR 0025 규칙 2). */
    @ParameterizedTest
    @CsvSource("Checkout-42, true", "checkout-42, false", "CHECKOUT-42, false", "Checkout-43, false")
    fun `a payment is started only with exactly its own idempotency key`(idempotencyKey: String, startedWith: Boolean) {
        val payment = createPayment(orderId = 42, idempotencyKey = "Checkout-42")

        assertThat(payment.isStartedWith(idempotencyKey)).isEqualTo(startedWith)
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

    @Test
    fun `a pending transaction moves an unknown payment to pending and gives it the transaction key`() {
        val payment = createPayment(orderId = 42)
        val transaction = createPgTransaction(payment, status = PgStatus.PENDING)

        val result = payment.reconcile(transaction)

        assertThat(result).isEqualTo(ReconcileResult.Changed(from = PaymentStatus.UNKNOWN, to = PaymentStatus.PENDING))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.transactionKey).isEqualTo(transaction.transactionKey)
    }

    @Test
    fun `a pending transaction leaves a pending payment unchanged`() {
        val payment = paymentIn(PaymentStatus.PENDING)

        val result = payment.reconcile(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = PgStatus.PENDING))

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    @ParameterizedTest
    @CsvSource("UNKNOWN, SUCCESS, SUCCESS", "UNKNOWN, FAILED, FAILED", "PENDING, SUCCESS, SUCCESS", "PENDING, FAILED, FAILED")
    fun `a final transaction finishes an unfinished payment with its result and transaction key`(
        from: PaymentStatus,
        pgStatus: PgStatus,
        to: PaymentStatus,
    ) {
        val payment = paymentIn(from)
        val transaction = createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus)

        val result = payment.reconcile(transaction)

        assertThat(result).isEqualTo(ReconcileResult.Changed(from = from, to = to))
        assertThat(payment.status).isEqualTo(to)
        assertThat(payment.transactionKey).isEqualTo(transaction.transactionKey)
    }

    @ParameterizedTest
    @CsvSource("SUCCESS, SUCCESS", "FAILED, FAILED")
    fun `a finished payment that hears its own result again stays unchanged without a conflict`(
        finished: PaymentStatus,
        pgStatus: PgStatus,
    ) {
        val payment = paymentIn(finished)

        val result = payment.reconcile(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus))

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(finished)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /** 다른 주문의 PG 거래가 이 결제를 끝내거나 거래 키를 정하지 않는다. */
    @Test
    fun `a transaction for another PG order ID reports a PG order ID mismatch and leaves the payment unknown`() {
        val payment = createPayment(orderId = 42)

        val result = payment.reconcile(createPgTransaction(payment, pgOrderId = OTHER_PG_ORDER_ID))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.PG_ORDER_ID_MISMATCH))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
    }

    @Test
    fun `a transaction for another amount reports an amount conflict and leaves the payment unknown`() {
        val payment = createPayment(orderId = 42, amount = Money(15_000))

        val result = payment.reconcile(createPgTransaction(payment, amount = Money(15_001)))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.AMOUNT_MISMATCH))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
    }

    /**
     * 처음 맞은 PG 거래가 거래 키를 정하고, 그 뒤의 다른 키는 다른 PG 거래다. 성공을 말해도 처리 중 결제를 끝내지 않고,
     * 끝난 결제에는 결과보다 키의 충돌이 먼저다.
     */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class, names = ["PENDING", "SUCCESS", "FAILED"])
    fun `a transaction with another key than the known one reports a key conflict and keeps status and key`(
        known: PaymentStatus,
    ) {
        val payment = paymentIn(known)

        val result = payment.reconcile(createPgTransaction(payment, transactionKey = "TEST-TX-OTHER", status = PgStatus.SUCCESS))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.TRANSACTION_KEY_MISMATCH))
        assertThat(payment.status).isEqualTo(known)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /** 반대의 결과는 반영하지 않고 충돌로 돌려준다. */
    @ParameterizedTest
    @CsvSource("SUCCESS, FAILED", "FAILED, SUCCESS")
    fun `a finished payment told the opposite result reports a result conflict and keeps its status and key`(
        finished: PaymentStatus,
        pgStatus: PgStatus,
    ) {
        val payment = paymentIn(finished)

        val result = payment.reconcile(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.RESULT_MISMATCH))
        assertThat(payment.status).isEqualTo(finished)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /**
     * 표의 위에서부터 처음 맞는 줄을 따른다. 여러 규칙을 어긴 답은 그 가운데 맨 위의 충돌이다. 성공한 결제에 성공을 말하는
     * PG 거래라 아래 줄로 내려가면 `Unchanged`로 묻힐 답이다.
     */
    @ParameterizedTest
    @CsvSource(
        "true, true, true, PG_ORDER_ID_MISMATCH",
        "false, true, true, AMOUNT_MISMATCH",
        "false, false, true, TRANSACTION_KEY_MISMATCH",
    )
    fun `an answer that breaks several rules reports the first broken rule in table order`(
        otherPgOrderId: Boolean,
        otherAmount: Boolean,
        otherKey: Boolean,
        reason: ConflictReason,
    ) {
        val payment = paymentIn(PaymentStatus.SUCCESS)
        val transaction = createPgTransaction(
            payment,
            transactionKey = if (otherKey) "TEST-TX-OTHER" else TRANSACTION_KEY,
            pgOrderId = if (otherPgOrderId) OTHER_PG_ORDER_ID else null,
            amount = if (otherAmount) payment.amount + Money(1) else null,
            status = PgStatus.SUCCESS,
        )

        val result = payment.reconcile(transaction)

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(reason))
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /** 알림과 조회는 차례를 지키지 않는다. 성공 뒤에 늦게 온 처리 중은 아는 것을 되돌리지 않는다. */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class, names = ["SUCCESS", "FAILED"])
    fun `a late pending transaction leaves a finished payment unchanged without a conflict`(finished: PaymentStatus) {
        val payment = paymentIn(finished)

        val result = payment.reconcile(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = PgStatus.PENDING))

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(finished)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /** 맞는 생성 전 거절은 PG 거래가 없다는 증거라 결과 미확인 결제를 실패로 끝낸다(ADR 0022). 없는 PG 거래의 거래 키는 없다. */
    @Test
    fun `a matching rejection fails an unknown payment without a transaction key`() {
        val payment = createPayment(orderId = 42)

        val result = payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        assertThat(result).isEqualTo(ReconcileResult.Changed(from = PaymentStatus.UNKNOWN, to = PaymentStatus.FAILED))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isNull()
    }

    /**
     * 과제가 어댑터에 맡긴 "요청 orderId·amount 일치" 확인을 결제가 한다(ADR 0021). 맞지 않는 거절은 이 결제의 증거가 아니다.
     * 둘 다 다르면 PG 주문 번호가 먼저다.
     */
    @ParameterizedTest
    @CsvSource(
        "$OTHER_PG_ORDER_ID, 15000, PG_ORDER_ID_MISMATCH",
        "$PG_ORDER_ID, 15001, AMOUNT_MISMATCH",
        "$OTHER_PG_ORDER_ID, 15001, PG_ORDER_ID_MISMATCH",
    )
    fun `a rejection for another PG order ID or amount reports that conflict and leaves the payment unknown`(
        pgOrderId: String,
        amount: Long,
        reason: ConflictReason,
    ) {
        val payment = createPayment(orderId = 42, amount = Money(15_000), pgOrderId = PG_ORDER_ID)

        val result = payment.reject(PgRejection(pgOrderId = pgOrderId, amount = Money(amount)))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(reason))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
    }

    /** 같은 거절을 다시 들어도 바뀌는 것이 없어야 2단계가 예약을 두 번 풀지 않는다. */
    @Test
    fun `a repeated rejection leaves the failed payment unchanged`() {
        val payment = createPayment(orderId = 42)
        val rejection = PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount)
        payment.reject(rejection)

        val result = payment.reject(rejection)

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isNull()
    }

    /**
     * 맞는 생성 전 거절은 PG 거래가 없다는 증거다(ADR 0022). 그 뒤에 맞는 PG 거래가 오면 PG 상태가 무엇이든 그 증거와 모순이라,
     * 실패를 바꾸지도 거래 키를 받지도 않고 충돌로 돌려준다. 실패를 말하는 PG 거래도 결과는 같지만 PG가 약속을 어긴 것이다.
     */
    @ParameterizedTest
    @EnumSource(PgStatus::class)
    fun `a matching transaction after a matching rejection reports a conflict and keeps the payment failed without a key`(
        pgStatus: PgStatus,
    ) {
        val payment = createPayment(orderId = 42)
        payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        val result = payment.reconcile(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.TRANSACTION_AFTER_REJECTION))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isNull()
    }

    @Test
    fun `a rejection leaves a payment failed by a transaction unchanged and keeps its key`() {
        val payment = paymentIn(PaymentStatus.FAILED)

        val result = payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /** 처리 중과 성공은 PG 거래가 있다는 것을 안다. "PG 거래가 없다"는 거절은 충돌이다. */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class, names = ["PENDING", "SUCCESS"])
    fun `a rejection for a payment with a known transaction reports a result conflict and keeps status and key`(
        known: PaymentStatus,
    ) {
        val payment = paymentIn(known)

        val result = payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.RESULT_MISMATCH))
        assertThat(payment.status).isEqualTo(known)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /**
     * [status]에 이른 결제. 결과 미확인이 아니면 그 상태를 말하는, 거래 키가 [TRANSACTION_KEY]인 PG 거래로 이른다.
     * 두 상태의 짝은 여기서 따로 적는다. 결제의 변환을 이름으로 뒤집으면 그 변환이 틀려도 함께 틀린다.
     */
    private fun paymentIn(status: PaymentStatus): Payment {
        val payment = createPayment(orderId = 42)
        val pgStatus = when (status) {
            PaymentStatus.UNKNOWN -> return payment
            PaymentStatus.PENDING -> PgStatus.PENDING
            PaymentStatus.SUCCESS -> PgStatus.SUCCESS
            PaymentStatus.FAILED -> PgStatus.FAILED
        }
        payment.reconcile(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus))

        return payment
    }
}
