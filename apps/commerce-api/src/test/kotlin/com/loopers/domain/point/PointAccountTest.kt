package com.loopers.domain.point

import com.loopers.domain.product.Product
import com.loopers.domain.shared.InvalidMoneyException
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PointAccountTest {
    @Test
    fun `paying the entire balance leaves a zero balance`() {
        val account = PointAccount(User())
        account.charge(Money(3_000))

        account.pay(Money(3_000))

        assertThat(account.balance).isEqualTo(Money.ZERO)
    }

    @Test
    fun `paying zero is rejected without changing the balance`() {
        val account = PointAccount(User())
        account.charge(Money(3_000))

        assertThrows<InvalidPaymentAmountException> { account.pay(Money.ZERO) }

        assertThat(account.balance).isEqualTo(Money(3_000))
    }

    @Test
    fun `paying 4000 from a 3000 balance rejects insufficient points and preserves the balance`() {
        val account = PointAccount(User())
        account.charge(Money(3_000))

        assertThrows<InsufficientPointsException> { account.pay(Money(4_000)) }

        assertThat(account.balance).isEqualTo(Money(3_000))
    }

    @Test
    fun `a new account starts with a zero balance`() {
        val account = PointAccount(User())

        assertThat(account.balance).isEqualTo(Money.ZERO)
    }

    @Test
    fun `charging adds the amount to the balance`() {
        val account = PointAccount(User())

        account.charge(Money(10_000))
        account.charge(Money(500))

        assertThat(account.balance).isEqualTo(Money(10_500))
    }

    @Test
    fun `charging zero throws InvalidChargeAmountException and keeps the balance`() {
        val account = PointAccount(User())
        account.charge(Money(1_000))

        val exception = assertThrows<InvalidChargeAmountException> { account.charge(Money.ZERO) }

        assertThat(exception.message).isEqualTo("충전액은 1원 이상이어야 합니다.")
        assertThat(account.balance).isEqualTo(Money(1_000))
    }

    /** 충전 후 잔액도 `Long` 범위 안이어야 한다. 넘치면 거절하고 잔액은 그대로다(설계 5.7). */
    @Test
    fun `charging past Long MAX_VALUE throws InvalidMoneyException and keeps the balance`() {
        val account = PointAccount(User())
        account.charge(Money(Long.MAX_VALUE))

        assertThrows<InvalidMoneyException> { account.charge(Money(1)) }

        assertThat(account.balance).isEqualTo(Money(Long.MAX_VALUE))
    }

    /** 상품 가격의 10억 원 상한은 상품만의 규칙이다. 포인트 잔액은 `Long` 범위만 지킨다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        val account = PointAccount(User())

        account.charge(Product.MAX_PRICE + Money(1))

        assertThat(account.balance).isEqualTo(Money(1_000_000_001))
    }
}
