package com.loopers.domain.point

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction

/**
 * 한 사용자의 포인트 잔액을 보유하는 계정. 사용자마다 하나이며 처음 잔액은 0원이다(CONTEXT.md 포인트 계정).
 * 사용자 fixture를 준비할 때 함께 만들고, 조회나 충전이 없는 계정을 만들어 주지 않는다(설계 5.9).
 *
 * 사용자를 식별자로만 가리킨다. 계정을 읽는 쪽이 사용자를 건너 읽지 않으므로 연관을 두지 않고, 외래 키
 * `FK_POINT_ACCOUNT_USER`는 `scalar-foreign-keys.sql`이 만든다(ADR 0014, 설계 12.1).
 *
 * 잔액은 0 이상인 [Money]이며 상품 가격의 상한을 따르지 않는다. `Long` 범위만 지킨다(설계 5.7).
 *
 * 계정을 삭제하는 기능은 없지만 애그리거트 루트라 논리 삭제를 기본으로 하여, 삭제된 계정은 없는 계정이다(ADR 0016).
 */
@Entity
@Table(
    uniqueConstraints = [UniqueConstraint(name = "UK_POINT_ACCOUNT_USER_ID", columnNames = ["user_id"])],
)
@SQLRestriction("deleted_at is null")
class PointAccount(
    userId: Long,
) : BaseEntity() {
    /** 계정을 가진 사용자. */
    @Column(nullable = false, updatable = false)
    val userId: Long = userId

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "balance", nullable = false))
    var balance: Money = Money.ZERO
        protected set

    /**
     * 양의 충전액만큼 잔액을 늘린다(CONTEXT.md 충전하다).
     * 충전 후 잔액이 `Long` 범위를 넘으면 [Money]가 거절하고, 0원 이하면 여기서 거절한다. 어느 쪽이든 잔액은 그대로다.
     */
    fun charge(amount: Money) {
        if (amount <= Money.ZERO) {
            throw InvalidChargeAmountException("충전액은 1원 이상이어야 합니다.")
        }
        balance = balance + amount
    }

    /**
     * 결제액만큼 잔액을 줄인다. 어느 주문의 결제인지는 모른다(ADR 0006).
     * 주문과 재고의 변경·저장은 application이 조율한다.
     */
    fun pay(amount: Money) {
        if (amount <= Money.ZERO) throw InvalidPaymentAmountException()
        if (amount > balance) throw InsufficientPointsException()
        balance = balance - amount
    }
}
