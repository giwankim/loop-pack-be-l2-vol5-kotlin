package com.loopers.application.order

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrder
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.order.createOrderProduct
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.statistics
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

/**
 * [OrderService]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은
 * [com.loopers.application.like.LikeServiceTest]와 같다.
 *
 * 생성과 상세는 [com.loopers.adapter.webapi.v1.order.OrderApiMockMvcTest]가 HTTP로 이미 붙들어 두므로
 * 여기서는 조회와, 생성이 요청마다 새 주문이라는 계약만 본다(ADR 0005).
 * 조각의 차례와 `hasNext`는 [com.loopers.adapter.persistence.order.OrderRepositoryTest]가 SQL로 고정한다.
 *
 * 내 목록(#15)과 관리자 조회(#16)가 한 저장소 조회를 쓰므로 둘을 한 클래스에서 본다. 갈리는 것은 요청자 확인과
 * 거를 사용자의 유무이고, 그 차이가 조회 횟수에도 드러난다(설계 16.2).
 */
@SpringBootTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
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
        val brand = brandRepository.save(createBrand())
        val shirt = productRepository.save(createProduct(brand, name = "티셔츠", price = Money(1_000)))
        val socks = productRepository.save(createProduct(brand, name = "양말", price = Money(2_000)))
        // 품목을 상품 ID의 거꾸로 넣는다. 그대로 실리면 차례를 확인한 것이 아니다.
        orderRepository.save(
            createOrder(owner.id, listOf(createOrderProduct(socks, quantity = 1), createOrderProduct(shirt, quantity = 2))),
        )
        entityManager.flushAndClear()

        val slice = orderService.findAll(owner.id, OrderListRequest())

        val listed = slice.items.single()
        assertThat(slice.page).isEqualTo(OrderListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(OrderListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext).isFalse()
        assertThat(listed.status).isEqualTo(OrderStatus.DRAFT)
        assertThat(listed.totalAmount).isEqualTo(4_000L)
        assertThat(listed.paidAmount).isNull()
        assertThat(listed.confirmedAt).isNull()
        assertThat(listed.createdAt).isNotNull()
        assertThat(listed.items.map { it.productId }).containsExactly(shirt.id, socks.id)
        assertThat(listed.items.map { it.productName }).containsExactly("티셔츠", "양말")
        assertThat(listed.items.map { it.unitPrice }).containsExactly(1_000L, 2_000L)
        assertThat(listed.items.map { it.quantity }).containsExactly(2, 1)
        assertThat(listed.items.map { it.lineAmount }).containsExactly(2_000L, 2_000L)
    }

    /** 요청마다 새 주문이다. 같은 품목을 두 번 보내면 확정 전 주문이 둘 남는다(ADR 0005). */
    @Test
    fun `creating the same order twice leaves two drafts`() {
        val owner = userRepository.save(User())
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        entityManager.flushAndClear()
        val request = createOrderCreateRequest(listOf(product.id))

        val first = orderService.create(owner.id, request)
        val second = orderService.create(owner.id, request)
        entityManager.flushAndClear()

        val listed = orderService.findAll(owner.id, OrderListRequest()).items
        assertThat(second.orderId).isNotEqualTo(first.orderId)
        assertThat(listed.map { it.orderId }).containsExactly(second.orderId, first.orderId)
        assertThat(listed.map { it.status }).containsOnly(OrderStatus.DRAFT)
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

        assertThat(violationsOf(owner.id, OrderListRequest(page = -1))).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(violationsOf(owner.id, OrderListRequest(size = 0))).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(violationsOf(owner.id, OrderListRequest(size = OrderListRequest.MAX_SIZE + 1)))
            .containsExactly("size는 ${OrderListRequest.MAX_SIZE} 이하여야 합니다.")
    }

    /**
     * 가득 찬 조각도 조회는 셋이다. 요청자 확인 하나, 주문 루트의 조각 하나, 품목을 모아 읽는 것 하나.
     * 주문마다 품목을 읽으면 조각 크기만큼 늘어난다(설계 9 조회, 14.1).
     *
     * 크기를 상한까지 채우는 까닭은 품목 조회가 하나로 끝나는 근거를 경계에서 확인하려는 것이다. #15는 그 근거가
     * `jpa.yml`의 `default_batch_fetch_size`와 이 상한이 같다는 것이었고, #16이 품목을 명시적으로 읽게 되어
     * 이제는 전역 설정과 무관하게 하나다(설계 16.1). 상한을 채운 이 경우가 그것을 확인한다.
     *
     * 통계를 실행 중에 켜고 끄는 까닭은 [com.loopers.application.like.LikeServiceTest]와 같다.
     */
    @Test
    fun `a slice filled to the maximum size still reads its items in one query`() {
        val owner = userRepository.save(User())
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        val size = OrderListRequest.MAX_SIZE
        List(size) { orderRepository.save(createOrder(owner.id, listOf(createOrderProduct(product)))) }
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = orderService.findAll(owner.id, OrderListRequest(size = size))

            assertThat(slice.items).hasSize(size)
            assertThat(slice.items.flatMap { it.items }).hasSize(size)
            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    /** 관리자 목록은 거를 사용자가 없으면 모든 사용자의 주문을 보고, 주문한 사용자의 식별자를 함께 싣는다. */
    @Test
    fun `the admin list gives the orders of every user latest first with the ordering user id`() {
        val mine = userRepository.save(User()).id
        val theirs = userRepository.save(User()).id
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        val first = orderRepository.save(createOrder(mine, listOf(createOrderProduct(product)))).id
        val second = orderRepository.save(createOrder(theirs, listOf(createOrderProduct(product)))).id
        entityManager.flushAndClear()

        val slice = orderService.findAll(OrderAdminListRequest())

        assertThat(slice.items.map { it.orderId }).containsExactly(second, first)
        assertThat(slice.items.map { it.userId }).containsExactly(theirs, mine)
        assertThat(slice.page).isZero()
        assertThat(slice.size).isEqualTo(OrderListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext).isFalse()
    }

    /**
     * 관리자 목록은 요청자를 확인하지 않으므로 조회가 둘이다. 주문 루트의 조각 하나와 품목을 모아 읽는 것 하나다.
     * 총 개수를 세지 않는다는 약속도 이 수에 걸려 있다(카탈로그 설계 5.5).
     */
    @Test
    fun `the admin list reads the page of orders and all of their items in two queries`() {
        val owner = userRepository.save(User()).id
        val brand = brandRepository.save(createBrand())
        val products = List(3) { productRepository.save(createProduct(brand)) }
        orderRepository.save(createOrder(owner, products.map { createOrderProduct(it, quantity = 1) }))
        orderRepository.save(createOrder(owner, listOf(createOrderProduct(products.first(), quantity = 2))))
        entityManager.flushAndClear()
        val statistics = entityManager.statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        try {
            val slice = orderService.findAll(OrderAdminListRequest())

            assertThat(slice.items.map { it.items.size }).containsExactly(1, 3)
            assertThat(slice.items.flatMap { it.items }.map { it.quantity }).containsExactly(2, 1, 1, 1)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        } finally {
            statistics.isStatisticsEnabled = false
        }
    }

    /** 컨트롤러를 거치지 않는 호출도 같은 페이지 규칙을 받는다(카탈로그 설계 5.25). */
    @Test
    fun `the admin list rejects a page and a size outside the bounds and accepts the maximum`() {
        assertThat(violationsOf(OrderAdminListRequest(page = -1))).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(violationsOf(OrderAdminListRequest(size = 0))).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(violationsOf(OrderAdminListRequest(size = OrderListRequest.MAX_SIZE + 1)))
            .containsExactly("size는 ${OrderListRequest.MAX_SIZE} 이하여야 합니다.")
        // 상한은 포함이다. 거절하는 쪽만 보면 @Max를 좁혀도 아무 테스트가 말하지 않는다.
        assertThat(orderService.findAll(OrderAdminListRequest(size = OrderListRequest.MAX_SIZE)).size)
            .isEqualTo(OrderListRequest.MAX_SIZE)
    }

    @Test
    fun `the admin detail gives another user's order with the ordering user id and the stored snapshot`() {
        val owner = userRepository.save(User()).id
        val brand = brandRepository.save(createBrand())
        val shirt = productRepository.save(createProduct(brand, price = Money(1_000)))
        val pants = productRepository.save(createProduct(brand, price = Money(2_000)))
        val saved = orderRepository.save(
            createOrder(owner, listOf(createOrderProduct(pants, quantity = 1), createOrderProduct(shirt, quantity = 2))),
        )
        entityManager.flushAndClear()

        val info = orderService.findForAdmin(saved.id)

        assertThat(info.orderId).isEqualTo(saved.id)
        assertThat(info.userId).isEqualTo(owner)
        assertThat(info.status).isEqualTo(OrderStatus.DRAFT)
        assertThat(info.totalAmount).isEqualTo(4_000L)
        assertThat(info.items.map { it.productId }).containsExactly(shirt.id, pants.id)
        assertThat(info.items.map { it.lineAmount }).containsExactly(2_000L, 2_000L)
        assertThat(info.paidAmount).isNull()
        assertThat(info.confirmedAt).isNull()
    }

    /** 관리자 상세는 요청자를 받지 않으므로, 없는 주문만이 거절 사유다. */
    @Test
    fun `the admin detail rejects an order that does not exist`() {
        val exception = assertThrows<CoreException> { orderService.findForAdmin(Long.MAX_VALUE) }

        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_NOT_FOUND)
    }

    private fun violationsOf(userId: Long, request: OrderListRequest): List<String> =
        assertThrows<ConstraintViolationException> { orderService.findAll(userId, request) }
            .constraintViolations.map { it.message }

    private fun violationsOf(request: OrderAdminListRequest): List<String> =
        assertThrows<ConstraintViolationException> { orderService.findAll(request) }
            .constraintViolations.map { it.message }
}
