package com.loopers.domain.product

import com.loopers.domain.brand.createBrand
import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 생성자와 `update`가 받는 인자 가운데 테스트가 보지 않는 것은 `valid`에서 가져온다. `valid`는 fixture가 만든 상품이라
 * 그 이름·가격·재고는 규칙을 지난다.
 */
class ProductTest {
    @ParameterizedTest
    @ValueSource(longs = [0L, 1_000_000_001L])
    fun `price outside 1 to 1_000_000_000 won throws InvalidPriceException`(amount: Long) {
        val valid = createProduct(createBrand())

        assertThrows<InvalidPriceException> {
            Product(brand = valid.brand, name = valid.name, price = Money(amount), stock = valid.stock)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [1L, 1_000_000_000L])
    fun `price at the bounds is kept`(amount: Long) {
        val valid = createProduct(createBrand())

        val product = Product(brand = valid.brand, name = valid.name, price = Money(amount), stock = valid.stock)

        assertThat(product.price).isEqualTo(Money(amount))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `blank name throws InvalidNameException`(name: String) {
        val valid = createProduct(createBrand())

        assertThrows<InvalidNameException> {
            Product(brand = valid.brand, name = name, price = valid.price, stock = valid.stock)
        }
    }

    @Test
    fun `name of 101 chars counting surrounding spaces throws InvalidNameException`() {
        val valid = createProduct(createBrand())

        assertThrows<InvalidNameException> {
            Product(brand = valid.brand, name = " " + "가".repeat(99) + " ", price = valid.price, stock = valid.stock)
        }
    }

    @Test
    fun `name of 100 chars counting surrounding spaces is kept as sent`() {
        val valid = createProduct(createBrand())
        val name = " " + "가".repeat(98) + " "

        val product = Product(brand = valid.brand, name = name, price = valid.price, stock = valid.stock)

        assertThat(product.name).isEqualTo(name)
    }

    @Test
    fun `negative stock throws InvalidStockException`() {
        val valid = createProduct(createBrand())

        assertThrows<InvalidStockException> {
            Product(brand = valid.brand, name = valid.name, price = valid.price, stock = -1)
        }
    }

    @Test
    fun `zero stock is kept`() {
        val valid = createProduct(createBrand())

        val product = Product(brand = valid.brand, name = valid.name, price = valid.price, stock = 0)

        assertThat(product.stock).isZero()
    }

    @Test
    fun `registering keeps the brand, the name as sent, and the stock it was given`() {
        val valid = createProduct(createBrand())

        val product = Product(brand = valid.brand, name = " 티셔츠 ", price = valid.price, stock = 3)

        assertThat(product.brand).isSameAs(valid.brand)
        assertThat(product.name).isEqualTo(" 티셔츠 ")
        assertThat(product.stock).isEqualTo(3)
    }

    @Test
    fun `update sets the name as sent and the price and keeps the brand`() {
        val brand = createBrand()
        val product = createProduct(brand)

        product.update(name = " 후드티 ", price = Money(25_000))

        assertThat(product.name).isEqualTo(" 후드티 ")
        assertThat(product.price).isEqualTo(Money(25_000))
        assertThat(product.brand).isSameAs(brand)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `update with a blank name throws InvalidNameException and keeps the name and price`(name: String) {
        val product = createProduct(createBrand(), name = "티셔츠", price = Money(10_000))
        val valid = createProduct(product.brand)

        assertThrows<InvalidNameException> { product.update(name = name, price = valid.price) }

        assertThat(product.name).isEqualTo("티셔츠")
        assertThat(product.price).isEqualTo(Money(10_000))
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, 1_000_000_001L])
    fun `update with a price outside the bounds throws InvalidPriceException and keeps the name and price`(amount: Long) {
        val product = createProduct(createBrand(), name = "티셔츠", price = Money(10_000))
        val valid = createProduct(product.brand)

        assertThrows<InvalidPriceException> { product.update(name = valid.name, price = Money(amount)) }

        assertThat(product.name).isEqualTo("티셔츠")
        assertThat(product.price).isEqualTo(Money(10_000))
    }

    @Test
    fun `updateStock with a negative quantity throws InvalidStockException and keeps the stock`() {
        val product = createProduct(createBrand(), stock = 5)

        assertThrows<InvalidStockException> { product.updateStock(-1) }

        assertThat(product.stock).isEqualTo(5)
    }

    @Test
    fun `updateStock sets the final quantity, including zero`() {
        val product = createProduct(createBrand())

        product.updateStock(0)

        assertThat(product.stock).isZero()
    }

    @Test
    fun `isSoldOut is true when the stock is zero`() {
        val product = createProduct(createBrand(), stock = 0)

        assertThat(product.isSoldOut()).isTrue()
    }

    @Test
    fun `isSoldOut is false when any stock remains`() {
        val product = createProduct(createBrand(), stock = 1)

        assertThat(product.isSoldOut()).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, Int.MIN_VALUE])
    fun `deductStock rejects nonpositive quantities without changing stock`(quantity: Int) {
        val product = createProduct(createBrand(), stock = 5)

        assertThrows<InvalidStockException> { product.deductStock(quantity) }

        assertThat(product.stock).isEqualTo(5)
    }

    @ParameterizedTest
    @ValueSource(ints = [6, Int.MAX_VALUE])
    fun `deductStock rejects shortages without changing stock`(quantity: Int) {
        val product = createProduct(createBrand(), stock = 5)

        assertThrows<InsufficientStockException> { product.deductStock(quantity) }

        assertThat(product.stock).isEqualTo(5)
    }

    @Test
    fun `deductStock can sell the final unit`() {
        val product = createProduct(createBrand(), stock = 1)

        product.deductStock(1)

        assertThat(product.isSoldOut()).isTrue()
    }
}
