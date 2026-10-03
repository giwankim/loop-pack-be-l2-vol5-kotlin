package com.loopers.domain.product

import com.loopers.domain.brand.createBrand
import com.loopers.domain.shared.Money
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * `ProductFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다. Instancio가 생성자를 건너뛰므로 규칙은 여기서 지킨다(ADR 0010).
 */
class ProductFixturesTest {
    companion object {
        private const val SAMPLES = 1_000

        /** 등록 요청의 브랜드 ID에는 제약이 없다. 있는 브랜드인지는 Service가 본다. */
        private const val BRAND_ID = 1L
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    /**
     * 0이면 품절이라 주문·좋아요 테스트가 가끔 깨진다. 100 이상이면 기본 주문 수량(1..10)보다 크다.
     * Instancio가 만든 엔티티라면 `id`와 `deletedAt`이 무작위다.
     */
    @Test
    fun `createProduct builds unsaved products of the given brand with 100 to 1_000 in stock`() {
        val brand = createBrand()

        val products = List(SAMPLES) { createProduct(brand) }

        assertThat(products).allSatisfy { product ->
            assertThat(product.brand).isSameAs(brand)
            assertThat(product.id).isZero()
            assertThat(product.deletedAt).isNull()
            assertThat(product.stock.quantity).isBetween(100, 1_000)
        }
    }

    @Test
    fun `createProduct builds products the real constructor accepts`() {
        val brand = createBrand()

        val products = List(SAMPLES) { createProduct(brand) }

        assertThat(products).allSatisfy { product ->
            assertDoesNotThrow { Product(product.brand, product.name, product.price, product.stock) }
        }
    }

    @Test
    fun `createProduct uses every argument it is given`() {
        val brand = createBrand()

        val product = createProduct(brand, name = "운동화", price = Money(39_000), stock = Stock(0))

        assertThat(product.brand).isSameAs(brand)
        assertThat(product.name).isEqualTo("운동화")
        assertThat(product.price).isEqualTo(Money(39_000))
        assertThat(product.stock).isEqualTo(Stock(0))
    }

    @Test
    fun `createProductAdminRegisterRequest satisfies the request's own constraints and never registers a sold-out product`() {
        val requests = List(SAMPLES) { createProductAdminRegisterRequest(brandId = BRAND_ID) }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.stock).isPositive() }
    }

    @Test
    fun `createProductAdminRegisterRequest uses every argument it is given`() {
        val request = createProductAdminRegisterRequest(brandId = BRAND_ID, name = "운동화", price = 39_000, stock = 0)

        assertThat(request.brandId).isEqualTo(BRAND_ID)
        assertThat(request.name).isEqualTo("운동화")
        assertThat(request.price).isEqualTo(39_000)
        assertThat(request.stock).isZero()
    }

    @Test
    fun `createProductAdminUpdateRequest satisfies the request's own constraints`() {
        val violations = List(SAMPLES) { createProductAdminUpdateRequest() }.flatMap { validator.validate(it) }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `createProductAdminUpdateRequest uses every argument it is given`() {
        val request = createProductAdminUpdateRequest(name = "운동화", price = 39_000)

        assertThat(request.name).isEqualTo("운동화")
        assertThat(request.price).isEqualTo(39_000)
    }

    @Test
    fun `createProductAdminStockUpdateRequest satisfies the request's own constraints and never sells out`() {
        val requests = List(SAMPLES) { createProductAdminStockUpdateRequest() }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request -> assertThat(request.quantity).isPositive() }
    }

    @Test
    fun `createProductAdminStockUpdateRequest uses the quantity it is given`() {
        val request = createProductAdminStockUpdateRequest(quantity = 0)

        assertThat(request.quantity).isZero()
    }
}
