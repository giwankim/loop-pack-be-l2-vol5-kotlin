package com.loopers.domain.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.Money
import com.loopers.domain.shared.Name
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

    @Test
    fun `registering keeps the brand, name, and stock it was given`() {
        val brand = Brand(Name("루퍼스"))

        val product = Product(brand = brand, name = Name(" 티셔츠 "), price = Money(10_000), stock = Stock(3))

        assertThat(product.brand).isSameAs(brand)
        assertThat(product.name).isEqualTo(Name("티셔츠"))
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

    private fun product(price: Money = Money(10_000), stock: Stock = Stock(1)) =
        Product(brand = Brand(Name("루퍼스")), name = Name("티셔츠"), price = price, stock = stock)
}
