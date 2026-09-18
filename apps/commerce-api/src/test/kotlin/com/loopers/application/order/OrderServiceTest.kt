package com.loopers.application.order

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import com.loopers.utils.statistics
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/**
 * [OrderService]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은
 * [com.loopers.application.like.LikeServiceTest]와 같다.
 *
 * 생성과 상세는 [com.loopers.interfaces.api.v1.order.OrderApiMockMvcTest]가 HTTP로 이미 붙들어 두므로
 * 여기서는 목록만 본다. 조각의 차례와 `hasNext`는 [com.loopers.infrastructure.order.OrderRepositoryTest]가 SQL로 고정한다.
 */
@SpringBootTest
@Transactional
class OrderServiceTest(
    private val orderService: OrderService,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    /**
     * 입력이 조각까지 이어지는지와, 트랜잭션 안에서만 읽을 수 있는 품목이 항목에 실리는지를 본다.
     * `open-in-view`가 꺼져 있으므로 품목을 옮기는 일은 이 읽기 트랜잭션 안에서 끝나야 한다(설계 9 조회).
     */
    @Test
    fun `the order list carries the default page and size into the slice and fills the stored items`() {
        val owner = userRepository.save(User())
        val shirt = registerProduct("티셔츠", 1_000)
        val socks = registerProduct("양말", 2_000)
        // 품목을 상품 ID의 거꾸로 넣는다. 그대로 실리면 차례를 확인한 것이 아니다.
        orderRepository.save(order(owner.id, "only", listOf(socks to 1, shirt to 2)))
        entityManager.flushAndClear()

        val slice = orderService.findAll(owner.id, OrderListRequest())

        val listed = slice.items.single()
        assertAll(
            { assertThat(slice.page).isEqualTo(OrderListRequest.DEFAULT_PAGE) },
            { assertThat(slice.size).isEqualTo(OrderListRequest.DEFAULT_SIZE) },
            { assertThat(slice.hasNext).isFalse() },
            { assertThat(listed.status).isEqualTo(OrderStatus.DRAFT) },
            { assertThat(listed.totalAmount).isEqualTo(4_000L) },
            { assertThat(listed.paidAmount).isNull() },
            { assertThat(listed.confirmedAt).isNull() },
            { assertThat(listed.createdAt).isNotNull() },
            { assertThat(listed.items.map { it.productId }).containsExactly(shirt.id, socks.id) },
            { assertThat(listed.items.map { it.productName }).containsExactly("티셔츠", "양말") },
            { assertThat(listed.items.map { it.unitPrice }).containsExactly(1_000L, 2_000L) },
            { assertThat(listed.items.map { it.quantity }).containsExactly(2, 1) },
            { assertThat(listed.items.map { it.lineAmount }).containsExactly(2_000L, 2_000L) },
        )
    }

    /** 요청자가 없으면 목록도 볼 수 없다. 생성·상세와 같은 검사다. */
    @Test
    fun `listing orders as an unknown user throws UNAUTHORIZED`() {
        val exception = assertThrows<CoreException> { orderService.findAll(999L, OrderListRequest()) }

        assertThat(exception.errorType).isEqualTo(ErrorType.UNAUTHORIZED)
    }

    @Test
    fun `listing orders outside the page and size bounds is rejected by request validation`() {
        val owner = userRepository.save(User())
        entityManager.flushAndClear()

        assertAll(
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        orderService.findAll(owner.id, OrderListRequest(page = -1))
                    }.constraintViolations.map { it.message },
                ).containsExactly("page는 0 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        orderService.findAll(owner.id, OrderListRequest(size = 0))
                    }.constraintViolations.map { it.message },
                ).containsExactly("size는 1 이상이어야 합니다.")
            },
            {
                assertThat(
                    assertThrows<ConstraintViolationException> {
                        orderService.findAll(owner.id, OrderListRequest(size = 101))
                    }.constraintViolations.map { it.message },
                ).containsExactly("size는 100 이하여야 합니다.")
            },
        )
    }

    /**
     * 가득 찬 조각도 조회는 셋이다. 요청자 확인 하나, 주문 루트의 조각 하나, 품목을 모아 읽는 것 하나.
     * 주문마다 품목을 읽으면 조각 크기만큼 늘어난다(설계 9 조회, 14.1).
     *
     * 크기를 [OrderListRequest.MAX_SIZE]로 채우는 까닭은, 품목 조회가 하나로 끝나는 근거가
     * `jpa.yml`의 `default_batch_fetch_size`와 이 상한이 같다는 것이기 때문이다. 두 값은 Gradle 모듈이 다르고
     * 한쪽은 YAML이라 서로를 모른다. 기본 크기로만 확인하면 그 경계를 넘겨보지 않은 채 약속만 적어 두는 셈이다.
     *
     * 통계를 실행 중에 켜고 끄는 까닭은 [com.loopers.application.like.LikeServiceTest]와 같다.
     */
    @Test
    fun `a slice filled to the maximum size still reads its items in one query`() {
        val owner = userRepository.save(User())
        val product = registerProduct()
        val size = OrderListRequest.MAX_SIZE
        List(size) { orderRepository.save(order(owner.id, "order-$it", listOf(product to 1))) }
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = orderService.findAll(owner.id, OrderListRequest(size = size))

            assertAll(
                { assertThat(slice.items).hasSize(size) },
                { assertThat(slice.items.flatMap { it.items }).hasSize(size) },
                { assertThat(statistics.prepareStatementCount).isEqualTo(3L) },
            )
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    private fun order(userId: Long, creationKey: String, lines: List<Pair<Product, Int>>): Order {
        val products = lines.map { (product, quantity) ->
            OrderProduct(product.id, product.name, product.price, quantity)
        }
        return Order(userId, creationKey, products)
    }

    private fun registerProduct(name: String = "티셔츠", price: Long = 1_000): Product {
        val brand = brandRepository.save(Brand("루퍼스"))
        return productRepository.save(Product(brand = brand, name = name, price = Money(price), stock = Stock(1)))
    }
}
