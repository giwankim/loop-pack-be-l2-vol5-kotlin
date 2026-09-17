package com.loopers.domain.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProductTest {
    @ParameterizedTest
    @ValueSource(longs = [0L, 1_000_000_001L])
    fun `price outside 1 to 1_000_000_000 won throws InvalidPriceException`(amount: Long) {
        assertThrows<InvalidPriceException> { product(price = Money(amount)) }
    }

    @ParameterizedTest
    @ValueSource(longs = [1L, 1_000_000_000L])
    fun `price at the bounds is kept`(amount: Long) {
        val product = product(price = Money(amount))

        assertThat(product.price).isEqualTo(Money(amount))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `blank name throws InvalidNameException`(value: String) {
        assertThrows<InvalidNameException> { product(name = value) }
    }

    @Test
    fun `name of 101 chars after trimming throws InvalidNameException`() {
        assertThrows<InvalidNameException> { product(name = " " + "가".repeat(101) + " ") }
    }

    @Test
    fun `name of 100 chars after trimming is kept`() {
        val value = "가".repeat(100)

        val product = product(name = "  $value\t")

        assertThat(product.name).isEqualTo(value)
    }

    @Test
    fun `registering keeps the brand, trimmed name, and stock it was given`() {
        val brand = Brand("루퍼스")

        val product = Product(brand = brand, name = " 티셔츠 ", price = Money(10_000), stock = Stock(3))

        assertThat(product.brand).isSameAs(brand)
        assertThat(product.name).isEqualTo("티셔츠")
        assertThat(product.stock).isEqualTo(Stock(3))
    }

    @Test
    fun `updateStock with a negative quantity throws InvalidStockException and keeps the stock`() {
        val product = product(stock = Stock(5))

        assertThrows<InvalidStockException> { product.updateStock(-1) }

        assertThat(product.stock).isEqualTo(Stock(5))
    }

    @Test
    fun `updateStock sets the final quantity, including zero`() {
        val product = product(stock = Stock(5))

        product.updateStock(0)

        assertThat(product.stock).isEqualTo(Stock(0))
    }

    @Test
    fun `isSoldOut is true when the stock is zero`() {
        val product = product(stock = Stock(0))

        assertThat(product.isSoldOut()).isTrue()
    }

    @Test
    fun `isSoldOut is false when any stock remains`() {
        val product = product(stock = Stock(1))

        assertThat(product.isSoldOut()).isFalse()
    }

    private fun product(
        name: String = "티셔츠",
        price: Money = Money(10_000),
        stock: Stock = Stock(1),
    ) = Product(brand = Brand("루퍼스"), name = name, price = price, stock = stock)
}
