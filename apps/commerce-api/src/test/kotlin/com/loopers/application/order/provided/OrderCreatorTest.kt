package com.loopers.application.order.provided

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.product.createProduct
import com.loopers.domain.user.User
import com.loopers.support.flushAndClear
import com.loopers.support.stereotype.ApplicationServiceTest
import jakarta.persistence.EntityManager
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [OrderCreator]를 실제 MySQL 위에서 확인한다. 정리와 flush/clear의 까닭은 [com.loopers.application.like.provided.LikerTest]와 같다.
 *
 * 생성의 거절과 품목·합계는 [com.loopers.adapter.webapi.v1.order.OrderApiMockMvcTest]가 HTTP로 이미 붙들어 두므로
 * 여기서는 생성이 요청마다 새 주문이라는 계약(ADR 0005)과, 포트가 Request를 검증한다는 것만 본다.
 * 만든 주문은 같은 조각의 [OrderFinder]로 읽는다.
 */
@ApplicationServiceTest
class OrderCreatorTest(
    private val orderCreator: OrderCreator,
    private val orderFinder: OrderFinder,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
) {
    /** 요청마다 새 주문이다. 같은 품목을 두 번 보내면 확정 전 주문이 둘 남는다(ADR 0005). */
    @Test
    fun `creating the same order twice leaves two drafts`() {
        val owner = userRepository.save(User())
        val product = productRepository.save(createProduct(brandRepository.save(createBrand())))
        entityManager.flushAndClear()
        val request = createOrderCreateRequest(listOf(product.id))

        val first = orderCreator.create(owner.id, request)
        val second = orderCreator.create(owner.id, request)
        entityManager.flushAndClear()

        val listed = orderFinder.findAll(owner.id, OrderListRequest()).content
        assertThat(second.orderId).isNotEqualTo(first.orderId)
        assertThat(listed.map { it.orderId }).containsExactly(second.orderId, first.orderId)
        assertThat(listed.map { it.status }).containsOnly(OrderStatus.DRAFT)
    }

    /**
     * 컨트롤러를 거치지 않는 호출도 같은 품목 규칙을 받는다(카탈로그 설계 5.25). 품목 안의 제약까지 닿는 것은
     * [OrderCreateRequest.items]의 `@Valid`가 이어 주기 때문이다.
     */
    @Test
    fun `creating an order without items or with a nonpositive item is rejected by request validation`() {
        val owner = userRepository.save(User())
        entityManager.flushAndClear()

        assertThat(violationsOf(owner.id, createOrderCreateRequest(emptyList())))
            .containsExactly("주문 품목은 1개 이상 100개 이하여야 합니다.")
        assertThat(violationsOf(owner.id, createOrderCreateRequest(0L to 0)))
            .containsExactlyInAnyOrder("상품 ID는 1 이상이어야 합니다.", "수량은 1개 이상이어야 합니다.")
    }

    private fun violationsOf(userId: Long, request: OrderCreateRequest): List<String> =
        assertThrows<ConstraintViolationException> { orderCreator.create(userId, request) }
            .constraintViolations.map { it.message }
}
