package com.loopers.domain.shared

import com.loopers.domain.InvalidPriceException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {
    @Test
    fun `negative amount throws InvalidPriceException`() {
        assertThrows<InvalidPriceException> { Money(-1) }
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
    fun `plus beyond Long MAX_VALUE throws InvalidPriceException`() {
        assertThrows<InvalidPriceException> { Money(Long.MAX_VALUE).plus(Money(1)) }
    }

    @Test
    fun `minus returns the difference and allows zero`() {
        assertThat(Money(1_000).minus(Money(400))).isEqualTo(Money(600))
        assertThat(Money(1_000).minus(Money(1_000))).isEqualTo(Money(0))
    }

    @Test
    fun `minus of a larger amount throws InvalidPriceException`() {
        assertThrows<InvalidPriceException> { Money(1_000).minus(Money(1_001)) }
    }

    @Test
    fun `times returns the amount multiplied by the count`() {
        assertThat(Money(1_500).times(3)).isEqualTo(Money(4_500))
    }

    @Test
    fun `times beyond Long MAX_VALUE throws InvalidPriceException`() {
        assertThrows<InvalidPriceException> { Money(Long.MAX_VALUE / 2 + 1).times(2) }
    }

    @Test
    fun `compareTo orders by amount`() {
        assertThat(Money(999)).isLessThan(Money(1_000))
        assertThat(Money(1_000)).isEqualByComparingTo(Money(1_000))
    }
}
