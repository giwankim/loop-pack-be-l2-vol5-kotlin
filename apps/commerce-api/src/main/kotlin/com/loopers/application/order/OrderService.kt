package com.loopers.application.order

import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
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
    @Transactional
    fun create(userId: Long, creationKey: String?, @Valid request: OrderCreateRequest): OrderInfo {
        checkUserExists(userId)
        if (creationKey == null || !CREATION_KEY.matches(creationKey)) throw CoreException(ErrorType.INVALID_IDEMPOTENCY_KEY)
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

    private fun checkUserExists(userId: Long) {
        if (!userRepository.existsById(userId)) throw CoreException(ErrorType.UNAUTHORIZED)
    }

    companion object {
        private val CREATION_KEY = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
