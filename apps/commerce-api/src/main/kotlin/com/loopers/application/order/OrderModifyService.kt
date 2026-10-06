package com.loopers.application.order

import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.point.required.PointAccountRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.user.provided.UserFinder
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.product.Product
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [OrderCreator]와 [OrderConfirmer]의 구현. 생성이 Request를 받으므로 검증하는 Service다.
 * 요청자가 있는지는 [UserFinder]에, 주문할 수 있는 상품인지는 [ProductFinder]에 묻는다.
 *
 * 확정의 차감만은 아직 다른 애그리거트를 직접 바꾼다. 재고는 [ProductFinder]가 준 상품에서, 포인트는
 * [PointAccountRepository]로 읽은 계정에서 차감한다. 셋 모두 확정의 트랜잭션 안이다(ADR 0003).
 * [ProductFinder.findOrderable]의 `readOnly`는 이미 열린 이 트랜잭션에 참여할 때는 걸리지 않으므로, 받은 상품의 변경도
 * 확정과 함께 커밋된다.
 */
@ValidatedApplicationService
class OrderModifyService(
    private val orderRepository: OrderRepository,
    private val userFinder: UserFinder,
    private val productFinder: ProductFinder,
    private val pointAccountRepository: PointAccountRepository,
) : OrderCreator,
    OrderConfirmer {
    override fun create(userId: Long, request: OrderCreateRequest): OrderInfo {
        userFinder.checkExists(userId)
        val products = request.items.map { item ->
            val product = availableProduct(item.productId)
            OrderProduct(product.id, product.name, product.price, item.quantity)
        }
        return OrderInfo.from(orderRepository.save(Order(userId, products)))
    }

    /**
     * 이미 확정된 본인 주문은 현재 카탈로그·잔액을 읽기 전에 거절한다. 첫 확정의 결과는 GET으로 읽는다(ADR 0005).
     * 단일 요청의 원자성과 순차 재요청만 보장하며 동시 요청의 경합은 이번 범위 밖이다.
     */
    override fun confirm(userId: Long, orderId: Long): OrderInfo {
        userFinder.checkExists(userId)
        val order = orderRepository.findByIdAndUserId(orderId, userId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        order.validateConfirmable()

        // 모든 품목의 판매 가능 여부를 먼저 본 뒤 차감한다. 삭제와 재고 부족이 함께면 품목 차례와 무관하게
        // ORDER_PRODUCT_NOT_AVAILABLE이 앞선다(설계 15).
        val products = order.items.map { item -> item to availableProduct(item.productId) }
        products.forEach { (item, product) -> product.deductStock(item.quantity) }
        val account = pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
        account.pay(order.totalAmount)
        order.confirm()
        return OrderInfo.from(order)
    }

    /** 논리 삭제된 상품·브랜드는 주문할 수도 확정할 수도 없다. 생성과 확정이 같은 판단을 쓴다. */
    private fun availableProduct(productId: Long): Product =
        productFinder.findOrderable(productId) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
}
