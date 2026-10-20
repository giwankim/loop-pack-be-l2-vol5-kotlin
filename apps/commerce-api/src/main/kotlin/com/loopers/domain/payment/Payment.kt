package com.loopers.domain.payment

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 확정 전 주문 하나의 금액을 PG로 치르는 일과, 그에 대해 커머스가 남긴 기록(`CONTEXT.md` 결제). 주문과 다른 애그리거트이며
 * 주문을 식별자로만 가리킨다(ADR 0021). 주문마다 하나, 멱등 키마다 하나이고, 두 유일 키가 그것을 지킨다.
 *
 * 연관이 없으므로 주문 외래 키 `FK_PAYMENT_ON_ORDER_ID`는 `scalar-foreign-keys.sql`이 만든다(ADR 0014). `payment`는
 * 가리키는 `orders`보다 이름 차례가 뒤라, 스키마를 다시 만들 때 `scalar-foreign-keys-drop.sql`이 그 외래 키를 먼저 지운다
 * (포인트·주문 설계 8.2).
 *
 * 결과 미확인으로 시작한다. 주문 식별자, 멱등 키, 금액, PG 주문 번호는 생성 때 정해지고 바뀌지 않는다. 카드는 PG에 넘기기만 하고
 * 담지 않는다.
 *
 * PG의 답은 [reconcile]과 [reject]로만 받는다. 결제 상태는 그 둘에서만 앞으로 가고, 맞지 않는 답은 상태를 바꾸지 않고 충돌로
 * 남는다(ADR 0021 규칙 1). PG에게서 답을 얻지 못한 것은 여기에 오지 않는다.
 *
 * 결제를 삭제하는 기능은 없지만 애그리거트 루트라 논리 삭제를 기본으로 하여, 삭제된 결제는 없는 결제다(ADR 0016).
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "UK_PAYMENT_ON_ORDER_ID", columnNames = ["order_id"]),
        UniqueConstraint(name = "UK_PAYMENT_ON_IDEMPOTENCY_KEY", columnNames = ["idempotency_key"]),
    ],
)
@SQLRestriction("deleted_at is null")
class Payment(
    @Column(nullable = false, updatable = false)
    val orderId: Long,

    /** 고객이 결제를 시작할 때 보낸 키. 공백뿐이지 않고 [IDEMPOTENCY_KEY_MAX_LENGTH]자 이하다. */
    @Column(nullable = false, updatable = false, length = IDEMPOTENCY_KEY_MAX_LENGTH)
    val idempotencyKey: String,

    /** PG로 치를 금액. 1원 이상이다. */
    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "amount", nullable = false, updatable = false))
    val amount: Money,
) : BaseEntity() {
    /**
     * PG에 알리는 주문 식별자(`CONTEXT.md` PG 주문 번호). `LP-ORD-` 뒤에 주문 식별자를 10자리로 0 채움한다.
     * 10자리는 최소 폭이라 더 긴 식별자도 자르지 않는다. PG에 보내는 값이라 `format`의 `%d`처럼 JVM 기본 로캘의 숫자를
     * 따르는 길을 쓰지 않는다.
     */
    @Column(nullable = false, updatable = false)
    val pgOrderId: String = "LP-ORD-" + orderId.toString().padStart(10, '0')

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: PaymentStatus = PaymentStatus.UNKNOWN
        protected set

    /**
     * 이 결제의 PG 거래에 PG가 붙인 식별자(`CONTEXT.md` 거래 키). 처음 맞은 PG 거래에서 받고, 그 뒤의 다른 키는 충돌이다.
     * 모르면 null이다. 생성 전 거절로 실패한 결제는 PG 거래가 없으므로 끝까지 null이다.
     */
    var transactionKey: String? = null
        protected set

    /** 마지막 충돌의 까닭. 충돌이 없었으면 null이다. 다음 충돌이 덮어쓰고, 뒤에 온 맞는 답은 지우지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    var conflictReason: ConflictReason? = null
        protected set

    /** 마지막 충돌의 시각. [BaseEntity]의 시각처럼 마이크로초로 자른다. */
    var conflictedAt: Instant? = null
        protected set

    init {
        if (idempotencyKey.isBlank()) {
            throw InvalidPaymentException("멱등 키는 공백일 수 없습니다.")
        }

        if (idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw InvalidPaymentException("멱등 키는 ${IDEMPOTENCY_KEY_MAX_LENGTH}자 이하여야 합니다.")
        }

        if (amount <= Money.ZERO) {
            throw InvalidPaymentException("결제 금액은 1원 이상이어야 합니다.")
        }
    }

    /**
     * PG가 이 결제의 PG 주문 번호로 말한 PG 거래들과 대사한다(ADR 0021 규칙 2). 알림, 대사 API, 받아들여진 생성 응답이 모두
     * 이 메서드를 부른다. 위에서부터 처음 맞는 규칙을 따른다.
     *
     * 1. 0건이면 아는 것이 늘지 않아 그대로다. 찾지 못한 것은 실패가 아니다.
     * 2. 2건 이상이면 어느 것이 이 결제인지 모르므로 충돌이다.
     * 3. PG 주문 번호, 금액, 이미 아는 거래 키가 다르면 충돌이다.
     * 4. 생성 전 거절로 실패한 결제에는 PG 상태가 무엇이든 충돌이다. 거절은 PG 거래가 없다는 증거였다(ADR 0022).
     * 5. 끝난 결제에 반대의 결과면 충돌이고, 같은 결과나 처리 중이면 그대로다.
     * 6. 처리 중 결제에 처리 중이면 그대로다.
     * 7. 그 밖에는 거래 키를 받고 PG 상태를 따라 앞으로 간다.
     */
    fun reconcile(transactions: List<PgTransaction>): ReconcileResult {
        if (transactions.isEmpty()) {
            return ReconcileResult.Unchanged
        }

        if (transactions.size > 1) {
            return conflict(ConflictReason.MULTIPLE_TRANSACTIONS)
        }

        val transaction = transactions.single()
        val reason = conflictReasonFor(transaction.pgOrderId, transaction.amount)
        if (reason != null) {
            return conflict(reason)
        }

        if (transactionKey != null && transaction.transactionKey != transactionKey) {
            return conflict(ConflictReason.TRANSACTION_KEY_MISMATCH)
        }

        // 대사는 끝내기 전에 거래 키를 받으므로, 거래 키 없이 실패한 결제는 생성 전 거절로 실패한 결제다.
        if (status == PaymentStatus.FAILED && transactionKey == null) {
            return conflict(ConflictReason.TRANSACTION_AFTER_REJECTION)
        }

        val reported = transaction.status.toPaymentStatus()
        if (status.isFinished && reported.isFinished && reported != status) {
            return conflict(ConflictReason.RESULT_MISMATCH)
        }

        if (status.isFinished || reported == status) {
            return ReconcileResult.Unchanged
        }

        transactionKey = transaction.transactionKey

        return moveTo(reported)
    }

    /**
     * 생성 전 거절을 받는다(ADR 0022). PG 주문 번호와 금액이 다르면 [reconcile]과 같은 충돌이다. 맞으면 결과 미확인은 실패로
     * 끝나고 이미 실패한 결제는 그대로다. 처리 중과 성공은 PG 거래가 있다는 것을 알므로 "PG 거래가 없다"는 거절과 충돌한다.
     * 거절에는 PG 거래가 없으므로 거래 키를 받지 않는다.
     */
    fun reject(rejection: PgRejection): ReconcileResult {
        val reason = conflictReasonFor(rejection.pgOrderId, rejection.amount)
        if (reason != null) {
            return conflict(reason)
        }

        return when (status) {
            PaymentStatus.UNKNOWN -> moveTo(PaymentStatus.FAILED)
            PaymentStatus.FAILED -> ReconcileResult.Unchanged
            PaymentStatus.PENDING, PaymentStatus.SUCCESS -> conflict(ConflictReason.RESULT_MISMATCH)
        }
    }

    /** 답의 PG 주문 번호나 금액이 이 결제의 것과 다르면 그 충돌의 까닭이고, 같으면 null이다. 둘 다 다르면 PG 주문 번호다. */
    private fun conflictReasonFor(pgOrderId: String, amount: Money): ConflictReason? {
        if (pgOrderId != this.pgOrderId) {
            return ConflictReason.PG_ORDER_ID_MISMATCH
        }

        if (amount != this.amount) {
            return ConflictReason.AMOUNT_MISMATCH
        }

        return null
    }

    /** 부르는 쪽이 앞으로 가는 상태만 넘긴다. 결과 미확인에서 셋 모두로, 처리 중에서 성공·실패로 간다. */
    private fun moveTo(to: PaymentStatus): ReconcileResult {
        val from = status
        status = to

        return ReconcileResult.Changed(from, to)
    }

    /**
     * 마지막 충돌의 까닭과 시각을 바꾼다. 결제 상태와 거래 키는 그대로다. 충돌의 WARN 로그는 결과를 반영하는 응용 서비스가
     * 쓴다(2단계). 엔티티는 로그를 쓰지 않는다.
     */
    private fun conflict(reason: ConflictReason): ReconcileResult {
        conflictReason = reason
        conflictedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)

        return ReconcileResult.Conflicted(reason)
    }

    private fun PgStatus.toPaymentStatus(): PaymentStatus {
        return when (this) {
            PgStatus.PENDING -> PaymentStatus.PENDING
            PgStatus.SUCCESS -> PaymentStatus.SUCCESS
            PgStatus.FAILED -> PaymentStatus.FAILED
        }
    }

    companion object {
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 64
    }
}
