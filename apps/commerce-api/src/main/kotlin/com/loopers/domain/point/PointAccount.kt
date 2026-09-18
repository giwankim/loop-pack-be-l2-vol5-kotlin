package com.loopers.domain.point

import com.loopers.domain.BaseEntity
import com.loopers.domain.order.Order
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 한 사용자의 포인트 잔액을 보유하는 계정. 사용자마다 하나이며 처음 잔액은 0원이다(CONTEXT.md 포인트 계정).
 * 사용자 fixture를 준비할 때 함께 만들고, 조회나 충전이 없는 계정을 만들어 주지 않는다(설계 5.9).
 *
 * [user]는 읽기용 참조다. 사용자를 객체로 가리키는 까닭은 `ddl-auto=create`가 연관에서만 DB 외래 키를 만들기
 * 때문이다(설계 12.1). 사용자에게는 삭제 상태가 없어 상품–브랜드의 삭제 필터 문제가 없다.
 *
 * 잔액은 0 이상인 [Money]이며 상품 가격의 상한을 따르지 않는다. `Long` 범위만 지킨다(설계 5.7).
 */
@Entity
@Table(
    name = "point_account",
    uniqueConstraints = [UniqueConstraint(name = "uk_point_account_user_id", columnNames = ["user_id"])],
)
class PointAccount(
    user: User,
) : BaseEntity() {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, foreignKey = ForeignKey(name = "fk_point_account_user"))
    val user: User = user

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "balance", nullable = false))
    var balance: Money = Money.ZERO
        protected set

    /** 계정을 가진 사용자의 식별자. 프록시가 들고 있는 값이라 사용자를 읽지 않는다. */
    val userId: Long
        get() = user.id

    /**
     * 양의 충전액만큼 잔액을 늘리고 그 충전의 CHARGE 이력을 돌려준다(CONTEXT.md 충전하다).
     * 충전 후 잔액이 `Long` 범위를 넘으면 [Money]가 거절하고, 0원 이하면 여기서 거절한다. 어느 쪽이든 잔액은 그대로다.
     *
     * 이력을 여기서 만드는 까닭은 "이력의 잔액은 그 충전 직후의 잔액"이라는 규칙을 계정이 지키게 하려는 것이다.
     * 저장은 부르는 쪽이 한다. 계정은 이력 컬렉션을 갖지 않는다(설계 7).
     */
    fun charge(amount: Money, chargeKey: String): PointHistory {
        if (amount <= Money.ZERO) {
            throw InvalidChargeAmountException("충전액은 1원 이상이어야 합니다.")
        }
        balance = balance + amount
        return PointHistory.charge(account = this, amount = amount, balanceAfter = balance, chargeKey = chargeKey)
    }

    /** 결제하고 직후 잔액을 담은 PAYMENT 이력을 돌려준다. 주문과 재고의 변경·저장은 application이 조율한다. */
    fun pay(amount: Money, order: Order): PointHistory {
        if (amount <= Money.ZERO) throw InvalidPaymentAmountException()
        if (amount > balance) throw InsufficientPointsException()
        balance = balance - amount
        return PointHistory.payment(account = this, amount = amount, balanceAfter = balance, order = order)
    }
}
