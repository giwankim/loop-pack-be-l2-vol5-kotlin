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

    companion object {
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 64
    }
}
