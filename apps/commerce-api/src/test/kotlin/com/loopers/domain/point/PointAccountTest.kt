package com.loopers.domain.point

import com.loopers.domain.product.Product
import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PointAccountTest {
    @Test
    fun `a new account starts with a zero balance`() {
        val account = PointAccount(userId = 1L)

        assertThat(account.balance).isEqualTo(Money.ZERO)
    }

    @Test
    fun `charging adds the amount to the balance`() {
        val account = PointAccount(userId = 1L)

        account.charge(Money(10_000))
        account.charge(Money(500))

        assertThat(account.balance).isEqualTo(Money(10_500))
    }

    @Test
    fun `charging zero throws InvalidChargeAmountException and keeps the balance`() {
        val account = PointAccount(userId = 1L)
        account.charge(Money(1_000))

        val exception = assertThrows<InvalidChargeAmountException> { account.charge(Money.ZERO) }

        assertThat(exception.message).isEqualTo("충전액은 1원 이상이어야 합니다.")
        assertThat(account.balance).isEqualTo(Money(1_000))
    }

    @Test
    fun `charging past Long MAX_VALUE preserves the exact balance`() {
        val account = PointAccount(userId = 1L)
        account.charge(Money(Long.MAX_VALUE))

        account.charge(Money(1))

        assertThat(account.balance.amount.toString()).isEqualTo("9223372036854775808")
    }

    /** 상품 가격의 10억 원 상한은 상품만의 규칙이다. 포인트 잔액에는 이 상한이 없다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        val account = PointAccount(userId = 1L)

        account.charge(Product.MAX_PRICE + Money(1))

        assertThat(account.balance).isEqualTo(Money(1_000_000_001))
    }
}
