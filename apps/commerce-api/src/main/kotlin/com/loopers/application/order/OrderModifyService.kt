package com.loopers.application.order

import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.point.provided.PointDeductor
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.StockDeductor
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.product.Product
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [OrderCreator]와 [OrderConfirmer]의 구현. 생성이 Request를 받으므로 검증하는 Service다.
 * 확정할 주문은 같은 조각의 [OrderFinder]로 얻고, 주문할 수 있는 상품인지는 [ProductFinder]에 묻는다.
 * 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 믿는다(ADR 0015).
 *
 * 자기 주문만 바꾼다. 확정의 재고는 [StockDeductor]로, 포인트는 [PointDeductor]로 그 조각이 차감한다.
 * 두 포트의 Service도 확정의 트랜잭션에 참여하므로(기본 `REQUIRED`) 재고·잔액·확정 상태가 한 번에 커밋되고,
 * 어느 차감이 실패해도 모두 되돌아간다(ADR 0003).
 * [OrderFinder.find]와 [ProductFinder.findForUpdateOrNull]의 `readOnly`도 이 트랜잭션에 참여할 때는 걸리지 않는다. 그래서 읽어 둔
 * 주문을 확정해도, 잠가 읽어 둔 상품을 [StockDeductor]가 같은 영속성 컨텍스트에서 다시 받아 차감해도 변경이 커밋된다.
 *
 * 주문할 수 있는 상품인지는 Validator로 옮기지 않는다. 생성은 그 상품의 이름·단가를 품목에 담으므로, 이 검사가 곧
 * 유스케이스가 쓸 데이터를 만든다(ADR 0014).
 */
@ValidatedApplicationService
class OrderModifyService(
    private val orderRepository: OrderRepository,
    private val orderFinder: OrderFinder,
    private val productFinder: ProductFinder,
    private val stockDeductor: StockDeductor,
    private val pointDeductor: PointDeductor,
) : OrderCreator,
    OrderConfirmer {
    override fun create(userId: Long, request: OrderCreateRequest): Order {
        val products = request.items.map { item ->
            val product = orderableProduct(item.productId)
            OrderProduct(product.id, product.name, product.price, item.quantity)
        }
        return orderRepository.save(Order(userId, products))
    }

    /**
     * 이미 확정된 본인 주문은 현재 카탈로그·잔액을 읽기 전에 거절한다. 첫 확정의 결과는 GET으로 읽는다(ADR 0005).
     * 품목의 상품 행은 처음 읽을 때 잠그므로, 상품을 바꾸는 다른 쓰기(관리자의 수정·재고 수정·삭제, 브랜드 삭제의 연쇄)와는
     * 한쪽이 커밋할 때까지 기다려 차례로 지나간다(ADR 0018). 같은 주문의 동시 확정은 아직 막지 않는다(설계 18.2).
     */
    override fun confirm(userId: Long, orderId: Long): Order {
        val order = orderFinder.find(userId, orderId)
        order.validateConfirmable()

        // 모든 품목의 판매 가능 여부를 먼저 본 뒤 차감한다. 삭제와 재고 부족이 함께면 품목 차례와 무관하게
        // ORDER_PRODUCT_NOT_AVAILABLE이 앞선다(설계 15).
        order.items.forEach { item -> availableProduct(item.productId) }
        order.items.forEach { item -> stockDeductor.deduct(item.productId, item.quantity) }
        pointDeductor.deduct(userId, order.totalAmount)
        order.confirm()
        return order
    }

    /** 생성의 상품 읽기. 논리 삭제된 상품·브랜드는 주문할 수 없다. 상품을 바꾸지 않으므로 잠그지 않는다. */
    private fun orderableProduct(productId: Long): Product {
        return productFinder.findOrderableOrNull(productId) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
    }

    /**
     * 확정의 첫 상품 읽기. 상품 행만 잠가 읽고, 논리 삭제된 상품은 확정할 수 없다. 잠금을 기다린 뒤에는 그 사이 커밋된 삭제도 본다.
     * 품목이 상품 ID 오름차순이라 잠금도 그 차례로 걸린다. 브랜드 삭제가 상품을 함께 삭제하므로 브랜드는 따로 보지 않는다(ADR 0017, 0018).
     *
     * 트랜잭션이 상품을 처음 읽는 자리라서 잠근다. Hibernate는 이미 읽은 엔티티를 다시 돌려줄 때 처음 읽은 필드 값을 그대로 두므로,
     * [StockDeductor]의 잠금 읽기는 이 잠금을 다시 확인할 뿐이다.
     */
    private fun availableProduct(productId: Long): Product {
        return productFinder.findForUpdateOrNull(productId) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
    }
}
