package com.loopers.domain.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {
    @Test
    fun `negative amount throws InvalidMoneyException`() {
        assertThrows<InvalidMoneyException> { Money(-1) }
    }

    @Test
    fun `zero amount is allowed`() {
        assertThat(Money(0).amount).isZero()
    }

    @Test
    fun `plus returns the sum`() {
        assertThat(Money(1_000).plus(Money(500))).isEqualTo(Money(1_500))
    }

    @Test
    fun `plus beyond Long MAX_VALUE preserves the exact sum`() {
        assertThat((Money(Long.MAX_VALUE) + Money(1)).amount.toString()).isEqualTo("9223372036854775808")
    }

    @Test
    fun `minus returns the difference and allows zero`() {
        assertThat(Money(1_000).minus(Money(400))).isEqualTo(Money(600))
        assertThat(Money(1_000).minus(Money(1_000))).isEqualTo(Money(0))
    }

    @Test
    fun `minus of a larger amount throws InvalidMoneyException`() {
        assertThrows<InvalidMoneyException> { Money(1_000).minus(Money(1_001)) }
    }

    @Test
    fun `times returns the amount multiplied by the count`() {
        assertThat(Money(1_500).times(3)).isEqualTo(Money(4_500))
    }

    @Test
    fun `times beyond Long MAX_VALUE preserves the exact product`() {
        assertThat((Money(Long.MAX_VALUE) * 2).amount.toString()).isEqualTo("18446744073709551614")
    }

    @Test
    fun `amounts beyond database precision can still be calculated exactly in memory`() {
        val amount = "100000000000000000000000000000000000000000000000000000000000000000000".toBigInteger()

        assertThat((Money(amount) + Money(1)).amount.toString())
            .isEqualTo("100000000000000000000000000000000000000000000000000000000000000000001")
    }

    @Test
    fun `minus beyond Long MAX_VALUE preserves the exact difference`() {
        val amount = Money("18446744073709551614".toBigInteger())

        assertThat((amount - Money(Long.MAX_VALUE)).amount).isEqualTo(Long.MAX_VALUE.toBigInteger())
    }

    @Test
    fun `negative BigInteger amount throws InvalidMoneyException`() {
        assertThrows<InvalidMoneyException> { Money("-9223372036854775809".toBigInteger()) }
    }

    @Test
    fun `times zero returns zero and a negative result is rejected`() {
        assertThat(Money(Long.MAX_VALUE) * 0).isEqualTo(Money.ZERO)
        assertThrows<InvalidMoneyException> { Money(1) * -1 }
    }

    @Test
    fun `compareTo orders by amount`() {
        assertThat(Money(999)).isLessThan(Money(1_000))
        assertThat(Money(1_000)).isEqualByComparingTo(Money(1_000))
        assertThat(Money("9223372036854775808".toBigInteger())).isGreaterThan(Money(Long.MAX_VALUE))
    }
}
