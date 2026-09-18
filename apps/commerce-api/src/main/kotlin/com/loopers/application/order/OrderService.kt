package com.loopers.application.order

import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.shared.IdempotencyKey
import com.loopers.domain.shared.PageSlice
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class OrderService(
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val brandRepository: BrandRepository,
) {
    /**
     * 생성 키의 형식은 [IdempotencyKey] 하나다. HTTP에서는 [com.loopers.interfaces.api.IdempotencyKeyHeader]가 먼저 거르고,
     * Controller를 거치지 않는 호출도 같은 규칙을 받도록 제약을 여기에도 둔다(카탈로그 설계 5.25, 설계 12.4).
     */
    @Transactional
    fun create(
        userId: Long,
        @Pattern(regexp = IdempotencyKey.PATTERN, message = "주문 생성 키는 ${IdempotencyKey.RULE}이어야 합니다.")
        creationKey: String,
        @Valid request: OrderCreateRequest,
    ): OrderInfo {
        checkUserExists(userId)
        val items = request.normalizedItems()
        orderRepository.findByUserIdAndCreationKey(userId, creationKey)?.let { order ->
            if (order.items.map { OrderCreateRequest.Item(it.productId, it.quantity) } != items) {
                throw CoreException(ErrorType.IDEMPOTENCY_KEY_CONFLICT)
            }
            // 생성의 성공 결과는 현재 주문 상태와 무관하다(ADR 0004).
            return OrderInfo.from(order).copy(status = OrderStatus.DRAFT, paidAmount = null, confirmedAt = null)
        }
        val products = items.map { item ->
            val product = productRepository.findById(item.productId)
                ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
            brandRepository.findById(product.brand.id) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
            OrderProduct(product.id, product.name, product.price, item.quantity)
        }
        return OrderInfo.from(orderRepository.save(Order(userId, creationKey, products)))
    }

    @Transactional(readOnly = true)
    fun find(userId: Long, orderId: Long): OrderInfo {
        checkUserExists(userId)
        val order = orderRepository.findByIdAndUserId(orderId, userId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        return OrderInfo.from(order)
    }

    /**
     * 요청자가 만든 주문 한 조각. 늦게 만든 주문이 앞선다. 받는 사용자 식별자는 요청자 하나뿐이라
     * 남의 목록을 내줄 길이 없다(카탈로그 설계 5.30).
     *
     * 저장된 스냅샷만 싣는다. 상품을 다시 읽어 이름·단가를 채우지 않으므로 이름이 바뀌거나 삭제된 상품의 주문도
     * 만들 때의 값 그대로다(ADR 0002, 설계 9 조회).
     *
     * 옮기는 일을 [PageSlice.map]에 맡겨 품목을 읽는 것이 이 읽기 트랜잭션 안에서 끝나게 한다.
     * `open-in-view`가 꺼져 있어 interfaces에서는 품목을 읽을 수 없다.
     */
    @Transactional(readOnly = true)
    fun findAll(userId: Long, @Valid request: OrderListRequest): PageSlice<OrderInfo> {
        checkUserExists(userId)
        return orderRepository.findAllByUserId(userId = userId, page = request.page, size = request.size)
            .map(OrderInfo::from)
    }

    private fun checkUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) throw CoreException(ErrorType.UNAUTHORIZED)
    }
}
