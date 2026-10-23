package com.loopers.domain.shared

import jakarta.persistence.Embeddable
import java.math.BigInteger

@Embeddable
data class Money(
    val amount: BigInteger,
) : Comparable<Money> {
    constructor(amount: Long) : this(BigInteger.valueOf(amount))

    init {
        if (amount.signum() < 0) {
            throw InvalidMoneyException("금액은 0 이상이어야 합니다.")
        }
    }

    operator fun plus(other: Money): Money {
        return Money(amount + other.amount)
    }

    operator fun minus(other: Money): Money {
        if (other.amount > amount) {
            throw InvalidMoneyException("가진 금액보다 큰 금액을 뺄 수 없습니다.")
        }

        return Money(amount - other.amount)
    }

    operator fun times(count: Int): Money {
        return Money(amount * BigInteger.valueOf(count.toLong()))
    }

    override fun compareTo(other: Money): Int {
        return amount.compareTo(other.amount)
    }

    companion object {
        val ZERO = Money(BigInteger.ZERO)
    }
}
