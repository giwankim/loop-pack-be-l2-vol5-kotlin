package com.loopers.application.order

import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.point.provided.PointDeductor
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.StockDeductor
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
 * 자기 주문만 바꾼다. 확정의 재고는 [StockDeductor]로, 포인트는 [PointDeductor]로 그 조각이 차감한다.
 * 두 포트의 Service도 확정의 트랜잭션에 참여하므로(기본 `REQUIRED`) 재고·잔액·확정 상태가 한 번에 커밋되고,
 * 어느 차감이 실패해도 모두 되돌아간다(ADR 0003).
 * [ProductFinder.findOrderable]의 `readOnly`도 이 트랜잭션에 참여할 때는 걸리지 않는다. 그래서 그것이 읽어 둔 상품을
 * [StockDeductor]가 같은 영속성 컨텍스트에서 다시 받아 차감해도 변경이 커밋된다.
 */
@ValidatedApplicationService
class OrderModifyService(
    private val orderRepository: OrderRepository,
    private val userFinder: UserFinder,
    private val productFinder: ProductFinder,
    private val stockDeductor: StockDeductor,
    private val pointDeductor: PointDeductor,
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
        order.items.forEach { item -> availableProduct(item.productId) }
        order.items.forEach { item -> stockDeductor.deduct(item.productId, item.quantity) }
        pointDeductor.deduct(userId, order.totalAmount)
        order.confirm()
        return OrderInfo.from(order)
    }

    /** 논리 삭제된 상품·브랜드는 주문할 수도 확정할 수도 없다. 생성과 확정이 같은 판단을 쓴다. */
    private fun availableProduct(productId: Long): Product =
        productFinder.findOrderable(productId) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
}
