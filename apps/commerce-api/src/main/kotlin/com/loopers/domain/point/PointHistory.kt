package com.loopers.domain.point

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.IdempotencyKey
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 성공한 충전으로 잔액이 달라진 기록(CONTEXT.md 포인트 이력). 실패한 시도는 기록하지 않고, 남긴 뒤에는 바꾸지 않는다.
 * 같은 충전 키의 재요청은 이 기록의 [amount]로 의도를 견주고 [balanceAfter]로 첫 응답을 다시 만든다(ADR 0004).
 *
 * [account]는 읽기용 참조이며 DB 외래 키의 자리다(설계 12.1). 계정은 이력 컬렉션을 갖지 않는다.
 * 결제(PAYMENT)의 이력과 주문 참조는 주문 확정 조각에서 더한다.
 *
 * 충전 키 열은 대소문자를 구분해 견주고 유일 제약도 그 비교로 지킨다. `Charge-A`와 `charge-a`는 다른 키다.
 * 열의 정의는 [IdempotencyKey]가 정한다(설계 5.8, 12.2).
 */
@Entity
@Table(
    name = "point_history",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_point_history_point_account_id_charge_key", columnNames = ["point_account_id", "charge_key"]),
    ],
)
class PointHistory private constructor(
    account: PointAccount,
    type: PointHistoryType,
    amount: Money,
    balanceAfter: Money,
    chargeKey: String,
) : BaseEntity() {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "point_account_id",
        nullable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_point_history_point_account"),
    )
    val account: PointAccount = account

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    val type: PointHistoryType = type

    /** 잔액을 바꾼 금액. 양수다. */
    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "amount", nullable = false, updatable = false))
    val amount: Money = amount

    /** 이 기록 직후의 잔액. 뒤에 결제가 있어도 바뀌지 않는다. */
    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "balance_after", nullable = false, updatable = false))
    val balanceAfter: Money = balanceAfter

    /** 충전 요청의 `Idempotency-Key`. 계정 안에서 유일하며 대소문자를 구분한다. */
    @Column(name = "charge_key", nullable = false, updatable = false, columnDefinition = IdempotencyKey.COLUMN_DEFINITION)
    val chargeKey: String = chargeKey

    companion object {
        /** 성공한 충전의 기록. [PointAccount.charge]만 부른다. */
        internal fun charge(account: PointAccount, amount: Money, balanceAfter: Money, chargeKey: String): PointHistory =
            PointHistory(
                account = account,
                type = PointHistoryType.CHARGE,
                amount = amount,
                balanceAfter = balanceAfter,
                chargeKey = chargeKey,
            )
    }
}
