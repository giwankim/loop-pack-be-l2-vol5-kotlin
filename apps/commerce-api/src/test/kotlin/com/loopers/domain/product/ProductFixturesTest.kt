package com.loopers.domain.product

import com.loopers.domain.brand.createBrand
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `ProductFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다(ADR 0007).
 */
class ProductFixturesTest {
    companion object {
        private const val SAMPLES = 1_000

        /** 등록 요청의 브랜드 ID에는 제약이 없다. 있는 브랜드인지는 Service가 본다. */
        private const val BRAND_ID = 1L
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `productName draws 1 to NAME_MAX_LENGTH uppercase letters`() {
        val names = List(SAMPLES) { productName() }

        assertThat(names).allSatisfy { name ->
            assertThat(name).hasSizeBetween(1, Product.NAME_MAX_LENGTH).matches("[A-Z]+")
        }
    }

    @Test
    fun `productPrice draws from the minimum to the maximum price`() {
        val prices = List(SAMPLES) { productPrice() }

        assertThat(prices).allSatisfy { price ->
            assertThat(price.amount).isBetween(Product.MIN_PRICE_AMOUNT, Product.MAX_PRICE_AMOUNT)
        }
    }

    /** 0이면 품절이라 주문·좋아요 테스트가 가끔 깨진다. 100 이상이면 기본 주문 수량(1..10)보다 크다. */
    @Test
    fun `productStock draws 100 to 1_000 so a default product is never sold out`() {
        val stocks = List(SAMPLES) { productStock() }

        assertThat(stocks).allSatisfy { stock ->
            assertThat(stock.quantity).isBetween(100, 1_000)
        }
    }

    /** 만들어지는 것 자체가 생성자의 이름·가격 규칙을 지났다는 뜻이다. Instancio가 만든 엔티티라면 `id`와 `deletedAt`이 무작위다. */
    @Test
    fun `createProduct builds unsaved products of the given brand that are not sold out`() {
        val brand = createBrand()

        val products = List(SAMPLES) { createProduct(brand) }

        assertThat(products).allSatisfy { product ->
            assertThat(product.brand).isSameAs(brand)
            assertThat(product.id).isZero()
            assertThat(product.deletedAt).isNull()
            assertThat(product.isSoldOut()).isFalse()
        }
    }

    @Test
    fun `createProductAdminRegisterRequest satisfies the request's own constraints and never registers a sold-out product`() {
        val requests = List(SAMPLES) { createProductAdminRegisterRequest(brandId = BRAND_ID) }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.stock).isPositive() }
    }

    @Test
    fun `createProductAdminUpdateRequest satisfies the request's own constraints`() {
        val violations = List(SAMPLES) { createProductAdminUpdateRequest() }.flatMap { validator.validate(it) }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `createProductAdminStockUpdateRequest satisfies the request's own constraints and never sells out`() {
        val requests = List(SAMPLES) { createProductAdminStockUpdateRequest() }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.quantity).isPositive() }
    }
}
