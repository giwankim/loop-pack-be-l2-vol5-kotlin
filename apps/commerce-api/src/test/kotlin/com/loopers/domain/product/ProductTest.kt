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
    fun `blank name throws InvalidNameException`(name: String) {
        assertThrows<InvalidNameException> { product(name = name) }
    }

    @Test
    fun `name of 101 chars after trimming throws InvalidNameException`() {
        assertThrows<InvalidNameException> { product(name = " " + "가".repeat(101) + " ") }
    }

    @Test
    fun `name of 100 chars after trimming is kept`() {
        val name = "가".repeat(100)

        val product = product(name = "  $name\t")

        assertThat(product.name).isEqualTo(name)
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
    fun `update sets the trimmed name and the price and keeps the brand`() {
        val brand = Brand("루퍼스")
        val product = Product(brand = brand, name = "티셔츠", price = Money(10_000), stock = Stock(3))

        product.update(name = " 후드티 ", price = Money(25_000))

        assertThat(product.name).isEqualTo("후드티")
        assertThat(product.price).isEqualTo(Money(25_000))
        assertThat(product.brand).isSameAs(brand)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `update with a blank name throws InvalidNameException and keeps the name and price`(name: String) {
        val product = product(name = "티셔츠", price = Money(10_000))

        assertThrows<InvalidNameException> { product.update(name = name, price = Money(25_000)) }

        assertThat(product.name).isEqualTo("티셔츠")
        assertThat(product.price).isEqualTo(Money(10_000))
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, 1_000_000_001L])
    fun `update with a price outside the bounds throws InvalidPriceException and keeps the name and price`(amount: Long) {
        val product = product(name = "티셔츠", price = Money(10_000))

        assertThrows<InvalidPriceException> { product.update(name = "후드티", price = Money(amount)) }

        assertThat(product.name).isEqualTo("티셔츠")
        assertThat(product.price).isEqualTo(Money(10_000))
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
