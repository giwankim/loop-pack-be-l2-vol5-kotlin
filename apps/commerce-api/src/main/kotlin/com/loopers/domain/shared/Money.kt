package com.loopers.domain.shared

import jakarta.persistence.Embeddable

/**
 * 원 단위 정수 금액. 0 이상인 불변 값 객체이며 연산은 새 값을 돌려준다.
 * 결과가 `Long` 범위를 넘거나 음수가 되면 [InvalidMoneyException]으로 거절한다.
 * 컬럼 이름은 쓰는 엔티티가 `@AttributeOverride`로 정한다.
 */
@Embeddable
data class Money(
    val amount: Long,
) : Comparable<Money> {
    init {
        if (amount < 0) {
            throw InvalidMoneyException("금액은 0 이상이어야 합니다.")
        }
    }

    operator fun plus(other: Money): Money = Money(exact { Math.addExact(amount, other.amount) })

    operator fun minus(other: Money): Money {
        if (other.amount > amount) {
            throw InvalidMoneyException("가진 금액보다 큰 금액을 뺄 수 없습니다.")
        }
        return Money(amount - other.amount)
    }

    operator fun times(count: Int): Money = Money(exact { Math.multiplyExact(amount, count.toLong()) })

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    private inline fun exact(calculate: () -> Long): Long =
        try {
            calculate()
        } catch (e: ArithmeticException) {
            throw InvalidMoneyException("금액 계산 결과가 표현 범위를 넘습니다.")
        }
}
