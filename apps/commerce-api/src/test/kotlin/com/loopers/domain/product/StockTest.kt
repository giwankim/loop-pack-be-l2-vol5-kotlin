package com.loopers.domain.product

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StockTest {
    @Test
    fun `negative quantity throws InvalidStockException`() {
        assertThrows<InvalidStockException> { Stock(-1) }
    }

    @Test
    fun `zero quantity is allowed`() {
        val stock = Stock(0)

        assertThat(stock.quantity).isZero()
    }

    @Test
    fun `positive quantity is kept`() {
        val stock = Stock(5)

        assertThat(stock.quantity).isEqualTo(5)
    }
}
