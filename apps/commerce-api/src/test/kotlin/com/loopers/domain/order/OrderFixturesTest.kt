package com.loopers.domain.order

import com.loopers.adapter.persistence.product.ProductRepositoryImpl
import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.order.OrderCreateRequest
import com.loopers.config.jpa.DataSourceConfig
import com.loopers.config.jpa.QueryDslConfig
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.testcontainers.MySqlTestContainersConfig
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import

/**
 * `OrderFixtures.kt`가 약속하는 기본값의 범위를 확인한다. 기본값이 범위를 벗어나면 그것을 쓰는 테스트가 가끔만 깨지므로,
 * 여기서 기본값을 많이 뽑아 결정적으로 드러낸다(ADR 0010).
 *
 * 상품을 스냅숏하는 품목은 저장된 상품의 식별자가 있어야 만들어지므로 [com.loopers.adapter.persistence.product.ProductRepositoryTest]와
 * 같은 설정으로 상품을 저장한다. 같은 설정이라 Spring 컨텍스트를 함께 쓴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DataSourceConfig::class,
    QueryDslConfig::class,
    MySqlTestContainersConfig::class,
    ProductRepositoryImpl::class,
)
class OrderFixturesTest(
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
) {
    companion object {
        private const val SAMPLES = 1_000

        /** 주문 요청의 상품 ID는 양수이기만 하면 된다. 있는 상품인지는 Service가 본다. */
        private val PRODUCT_IDS = listOf(3L, 1L, 2L)
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    /** 1..10개라 기본 재고(100..1,000개)를 넘지 않아 기본값끼리 주문해도 품절이 나지 않는다. */
    @Test
    fun `createOrderProduct snapshots the saved product it is given and orders 1 to 10 of it`() {
        val brand = brandRepository.save(createBrand())
        val product = productRepository.save(createProduct(brand))

        val lines = List(SAMPLES) { createOrderProduct(product) }

        assertThat(lines).allSatisfy { line ->
            assertThat(line.productId).isEqualTo(product.id)
            assertThat(line.productName).isEqualTo(product.name)
            assertThat(line.unitPrice).isEqualTo(product.price)
            assertThat(line.quantity).isBetween(1, 10)
        }
    }

    @Test
    fun `createOrderProduct uses the quantity it is given for a saved product`() {
        val brand = brandRepository.save(createBrand())
        val product = productRepository.save(createProduct(brand))

        val line = createOrderProduct(product, quantity = 11)

        assertThat(line.quantity).isEqualTo(11)
    }

    /** 단가 1..1,000,000,000원에 수량 1..10개라 품목 금액이 넘치지 않는다. 품목이 만들어지는 것이 곧 생성자 검사다. */
    @Test
    fun `createOrderProduct without a product names 2 to 100 characters and orders 1 to 10 at a legal product price`() {
        val lines = List(SAMPLES) { createOrderProduct() }

        assertThat(lines).allSatisfy { line ->
            assertThat(line.productName).hasSizeBetween(2, 100)
            assertThat(line.unitPrice.amount).isBetween(1, 1_000_000_000)
            assertThat(line.quantity).isBetween(1, 10)
        }
    }

    @Test
    fun `createOrderProduct without a product uses every argument it is given`() {
        val line = createOrderProduct(productId = 7, productName = "운동화", unitPrice = Money(39_000), quantity = 11)

        assertThat(line).isEqualTo(OrderProduct(productId = 7, productName = "운동화", unitPrice = Money(39_000), quantity = 11))
    }

    /**
     * 진짜 생성자로 만들므로 만들어지는 것이 곧 검사다. 상품이 겹치면 [InvalidOrderException], 합계가 넘치면
     * [com.loopers.domain.shared.InvalidMoneyException]이 난다. 품목 1..5개, 품목 금액 10,000,000,000원 이하라 합계는
     * 50,000,000,000원 이하다.
     */
    @Test
    fun `createOrder builds unsaved draft orders of the given user with 1 to 5 lines of distinct products`() {
        val orders = List(SAMPLES) { createOrder(userId = 1) }

        assertThat(orders).allSatisfy { order ->
            assertThat(order.id).isZero()
            assertThat(order.userId).isEqualTo(1)
            assertThat(order.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(order.items).hasSizeBetween(1, 5)
            assertThat(order.items.map { it.productId }).doesNotHaveDuplicates()
            assertThat(order.totalAmount.amount).isBetween(1, 50_000_000_000)
        }
    }

    @Test
    fun `createOrder uses the lines it is given`() {
        val line = createOrderProduct(productId = 7, productName = "운동화", unitPrice = Money(39_000), quantity = 2)

        val order = createOrder(userId = 1, products = listOf(line))

        assertThat(order.items).singleElement().satisfies({ item ->
            assertThat(item.productId).isEqualTo(7)
            assertThat(item.productName).isEqualTo("운동화")
            assertThat(item.unitPrice).isEqualTo(Money(39_000))
            assertThat(item.quantity).isEqualTo(2)
        })
    }

    @Test
    fun `createOrderCreateRequest satisfies the request's own constraints and orders 1 to 10 of each given product`() {
        val requests = List(SAMPLES) { createOrderCreateRequest(productIds = PRODUCT_IDS) }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request ->
            assertThat(request.items.map { it.productId }).containsExactly(3L, 1L, 2L)
            assertThat(request.items).allSatisfy { item -> assertThat(item.quantity).isBetween(1, 10) }
        }
    }

    @Test
    fun `createOrderCreateRequest uses the quantity it is given for every product`() {
        val request = createOrderCreateRequest(productIds = PRODUCT_IDS, quantity = 11)

        assertThat(request.items.map { it.quantity }).containsExactly(11, 11, 11)
    }

    @Test
    fun `createOrderCreateRequest with pairs draws 1 to 10 for a missing quantity within the request's constraints`() {
        val requests = List(SAMPLES) { createOrderCreateRequest(3L to null, 1L to 11) }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request ->
            assertThat(request.items.map { it.productId }).containsExactly(3L, 1L)
            assertThat(request.items.first().quantity).isBetween(1, 10)
            assertThat(request.items.last().quantity).isEqualTo(11)
        }
    }

    @Test
    fun `createOrderCreateRequest uses each product and quantity pair it is given in order`() {
        val request = createOrderCreateRequest(3L to 5, 1L to 11)

        assertThat(request.items).containsExactly(
            OrderCreateRequest.Item(productId = 3, quantity = 5),
            OrderCreateRequest.Item(productId = 1, quantity = 11),
        )
    }
}
