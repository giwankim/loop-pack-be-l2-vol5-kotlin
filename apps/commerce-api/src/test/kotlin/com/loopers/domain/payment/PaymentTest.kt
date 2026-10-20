package com.loopers.domain.payment

import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

class PaymentTest {
    companion object {
        private const val TRANSACTION_KEY = "TEST-TX-0042"
    }

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

    /**
     * 대사에서 PG 거래를 찾지 못한 것은 "결제되지 않았다"가 아니다(`CONTEXT.md` 결과 미확인). 처리 중 결제도 결과 미확인으로
     * 돌아가지 않는다.
     */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class)
    fun `reconciling no transaction leaves a payment of any status unchanged`(status: PaymentStatus) {
        val payment = paymentIn(status)
        val transactionKey = payment.transactionKey

        val result = payment.reconcile(emptyList())

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(status)
        assertThat(payment.transactionKey).isEqualTo(transactionKey)
        assertThat(payment.conflictReason).isNull()
        assertThat(payment.conflictedAt).isNull()
    }

    @Test
    fun `a pending transaction moves an unknown payment to pending and gives it the transaction key`() {
        val payment = createPayment(orderId = 42)
        val transaction = createPgTransaction(payment, status = PgStatus.PENDING)

        val result = payment.reconcile(listOf(transaction))

        assertThat(result).isEqualTo(ReconcileResult.Changed(from = PaymentStatus.UNKNOWN, to = PaymentStatus.PENDING))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.transactionKey).isEqualTo(transaction.transactionKey)
    }

    @Test
    fun `a pending transaction leaves a pending payment unchanged`() {
        val payment = paymentIn(PaymentStatus.PENDING)

        val result = payment.reconcile(
            listOf(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = PgStatus.PENDING)),
        )

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isNull()
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

        val result = payment.reconcile(listOf(transaction))

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

        val result = payment.reconcile(listOf(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus)))

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(finished)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isNull()
    }

    /** PG 주문 번호 하나에 거래가 둘 이상이면 어느 것이 이 결제인지 모르므로 고르지 않는다. */
    @Test
    fun `two transactions record a multiple transactions conflict and leave the payment unknown`() {
        val payment = createPayment(orderId = 42)
        val transactions = listOf(
            createPgTransaction(payment, status = PgStatus.SUCCESS),
            createPgTransaction(payment, status = PgStatus.SUCCESS),
        )

        val result = payment.reconcile(transactions)

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.MULTIPLE_TRANSACTIONS))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.MULTIPLE_TRANSACTIONS)
        assertThat(payment.conflictedAt).isNotNull()
    }

    /** 다른 주문의 PG 거래가 이 결제를 끝내거나 거래 키를 정하지 않는다. */
    @Test
    fun `a transaction for another PG order number records a PG order conflict and leaves the payment unknown`() {
        val payment = createPayment(orderId = 42)

        val result = payment.reconcile(listOf(createPgTransaction(payment, pgOrderId = "LP-ORD-0000000043")))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.PG_ORDER_ID_MISMATCH))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.PG_ORDER_ID_MISMATCH)
        assertThat(payment.conflictedAt).isNotNull()
    }

    @Test
    fun `a transaction for another amount records an amount conflict and leaves the payment unknown`() {
        val payment = createPayment(orderId = 42, amount = Money(15_000))

        val result = payment.reconcile(listOf(createPgTransaction(payment, amount = Money(15_001))))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.AMOUNT_MISMATCH))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.transactionKey).isNull()
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.AMOUNT_MISMATCH)
        assertThat(payment.conflictedAt).isNotNull()
    }

    /**
     * 처음 맞은 PG 거래가 거래 키를 정하고, 그 뒤의 다른 키는 다른 거래다. 성공을 말해도 처리 중 결제를 끝내지 않고,
     * 끝난 결제에는 결과보다 키의 충돌이 먼저다.
     */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class, names = ["PENDING", "SUCCESS", "FAILED"])
    fun `a transaction with another key than the known one records a key conflict and keeps status and key`(
        known: PaymentStatus,
    ) {
        val payment = paymentIn(known)

        val result = payment.reconcile(
            listOf(createPgTransaction(payment, transactionKey = "TEST-TX-OTHER", status = PgStatus.SUCCESS)),
        )

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.TRANSACTION_KEY_MISMATCH))
        assertThat(payment.status).isEqualTo(known)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.TRANSACTION_KEY_MISMATCH)
    }

    /** 반대의 결과는 사람이 볼 모순이라 반영하지 않고 남긴다. */
    @ParameterizedTest
    @CsvSource("SUCCESS, FAILED", "FAILED, SUCCESS")
    fun `a finished payment told the opposite result records a result conflict and keeps its status and key`(
        finished: PaymentStatus,
        pgStatus: PgStatus,
    ) {
        val payment = paymentIn(finished)

        val result = payment.reconcile(listOf(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus)))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.RESULT_MISMATCH))
        assertThat(payment.status).isEqualTo(finished)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.RESULT_MISMATCH)
        assertThat(payment.conflictedAt).isNotNull()
    }

    /**
     * 표의 위에서부터 처음 맞는 줄을 따른다. 여러 규칙을 어긴 답은 그 가운데 맨 위의 충돌이다. 성공한 결제에 성공을 말하는
     * 거래라 아래 줄로 내려가면 `Unchanged`로 묻힐 답이다.
     */
    @ParameterizedTest
    @CsvSource(
        "2, true, true, true, MULTIPLE_TRANSACTIONS",
        "1, true, true, true, PG_ORDER_ID_MISMATCH",
        "1, false, true, true, AMOUNT_MISMATCH",
        "1, false, false, true, TRANSACTION_KEY_MISMATCH",
    )
    fun `an answer that breaks several rules reports the first broken rule in table order`(
        count: Int,
        otherPgOrderId: Boolean,
        otherAmount: Boolean,
        otherKey: Boolean,
        reason: ConflictReason,
    ) {
        val payment = paymentIn(PaymentStatus.SUCCESS)
        val transactions = List(count) {
            createPgTransaction(
                payment,
                transactionKey = if (otherKey) "TEST-TX-OTHER-$it" else TRANSACTION_KEY,
                pgOrderId = if (otherPgOrderId) "LP-ORD-0000000043" else null,
                amount = if (otherAmount) payment.amount + Money(1) else null,
                status = PgStatus.SUCCESS,
            )
        }

        val result = payment.reconcile(transactions)

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(reason))
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isEqualTo(reason)
    }

    /**
     * 결제는 마지막 충돌만 남긴다. 두 충돌이 같은 마이크로초에 찍히면 시각을 덮어썼는지 가릴 수 없으므로, 두 번째 충돌 전에
     * 시계가 첫 시각을 넘기를 기다린다.
     */
    @Test
    fun `a later conflict overwrites the earlier reason and time and still keeps status and key`() {
        val payment = paymentIn(PaymentStatus.PENDING)
        payment.reconcile(listOf(createPgTransaction(payment, pgOrderId = "LP-ORD-0000000043")))
        val firstConflictedAt = payment.conflictedAt!!
        while (!Instant.now().truncatedTo(ChronoUnit.MICROS).isAfter(firstConflictedAt)) {
            Thread.onSpinWait()
        }

        val result = payment.reconcile(listOf(createPgTransaction(payment, transactionKey = "TEST-TX-OTHER")))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.TRANSACTION_KEY_MISMATCH))
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.TRANSACTION_KEY_MISMATCH)
        assertThat(payment.conflictedAt).isAfter(firstConflictedAt)
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
    }

    /** 알림과 조회는 차례를 지키지 않는다. 성공 뒤에 늦게 온 처리 중은 아는 것을 되돌리지 않는다. */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class, names = ["SUCCESS", "FAILED"])
    fun `a late pending transaction leaves a finished payment unchanged without a conflict`(finished: PaymentStatus) {
        val payment = paymentIn(finished)

        val result = payment.reconcile(
            listOf(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = PgStatus.PENDING)),
        )

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(finished)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isNull()
    }

    /** 맞는 생성 전 거절은 PG 거래가 없다는 증거라 결과 미확인 결제를 실패로 끝낸다(ADR 0022). 없는 거래의 키는 없다. */
    @Test
    fun `a matching rejection fails an unknown payment without a transaction key`() {
        val payment = createPayment(orderId = 42)

        val result = payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        assertThat(result).isEqualTo(ReconcileResult.Changed(from = PaymentStatus.UNKNOWN, to = PaymentStatus.FAILED))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isNull()
        assertThat(payment.conflictReason).isNull()
    }

    /**
     * 과제가 어댑터에 맡긴 "요청 orderId·amount 일치" 확인을 결제가 한다(ADR 0021). 맞지 않는 거절은 이 결제의 증거가 아니다.
     * 둘 다 다르면 PG 주문 번호가 먼저다.
     */
    @ParameterizedTest
    @CsvSource(
        "LP-ORD-0000000043, 15000, PG_ORDER_ID_MISMATCH",
        "LP-ORD-0000000042, 15001, AMOUNT_MISMATCH",
        "LP-ORD-0000000043, 15001, PG_ORDER_ID_MISMATCH",
    )
    fun `a rejection for another PG order number or amount records that conflict and leaves the payment unknown`(
        pgOrderId: String,
        amount: Long,
        reason: ConflictReason,
    ) {
        val payment = createPayment(orderId = 42, amount = Money(15_000))

        val result = payment.reject(PgRejection(pgOrderId = pgOrderId, amount = Money(amount)))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(reason))
        assertThat(payment.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(payment.conflictReason).isEqualTo(reason)
        assertThat(payment.conflictedAt).isNotNull()
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
        assertThat(payment.conflictReason).isNull()
    }

    /**
     * 맞는 생성 전 거절은 PG 거래가 없다는 증거다(ADR 0022). 그 뒤에 맞는 PG 거래가 오면 PG 상태가 무엇이든 그 증거와 모순이라,
     * 실패를 바꾸지도 거래 키를 받지도 않고 충돌로 남긴다. 실패를 말하는 PG 거래도 결과는 같지만 PG가 약속을 어긴 것이다.
     */
    @ParameterizedTest
    @EnumSource(PgStatus::class)
    fun `a matching transaction after a matching rejection records a conflict and keeps the payment failed without a key`(
        pgStatus: PgStatus,
    ) {
        val payment = createPayment(orderId = 42)
        payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        val result = payment.reconcile(listOf(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus)))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.TRANSACTION_AFTER_REJECTION))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isNull()
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.TRANSACTION_AFTER_REJECTION)
        assertThat(payment.conflictedAt).isNotNull()
    }

    @Test
    fun `a rejection leaves a payment failed by a transaction unchanged and keeps its key`() {
        val payment = paymentIn(PaymentStatus.FAILED)

        val result = payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isNull()
    }

    /** 처리 중과 성공은 PG 거래가 있다는 것을 안다. "거래가 없다"는 거절은 그것을 되돌리지 않고 충돌로 남는다. */
    @ParameterizedTest
    @EnumSource(PaymentStatus::class, names = ["PENDING", "SUCCESS"])
    fun `a rejection for a payment with a known transaction records a result conflict and keeps status and key`(
        known: PaymentStatus,
    ) {
        val payment = paymentIn(known)

        val result = payment.reject(PgRejection(pgOrderId = payment.pgOrderId, amount = payment.amount))

        assertThat(result).isEqualTo(ReconcileResult.Conflicted(ConflictReason.RESULT_MISMATCH))
        assertThat(payment.status).isEqualTo(known)
        assertThat(payment.transactionKey).isEqualTo(TRANSACTION_KEY)
        assertThat(payment.conflictReason).isEqualTo(ConflictReason.RESULT_MISMATCH)
        assertThat(payment.conflictedAt).isNotNull()
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
        payment.reconcile(listOf(createPgTransaction(payment, transactionKey = TRANSACTION_KEY, status = pgStatus)))

        return payment
    }
}
