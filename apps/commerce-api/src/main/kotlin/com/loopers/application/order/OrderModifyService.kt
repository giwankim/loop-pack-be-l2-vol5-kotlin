package com.loopers.application.order

import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService

/**
 * [OrderCreator]의 구현. 생성이 Request를 받으므로 검증하는 Service다. 담을 상품이 주문할 수 있는 상품인지는 [ProductFinder]에 묻는다.
 * 요청자는 웹 경계가 이미 받아들였으므로 받은 `userId`를 믿는다(ADR 0015).
 *
 * 주문 확정은 PG를 트랜잭션 밖에서 불러야 하므로 이 Service가 아니라 [OrderPaymentSaga]가 맡는다(ADR 0028 규칙 2).
 *
 * 주문할 수 있는 상품인지는 Validator로 옮기지 않는다. 생성은 그 상품의 이름·단가를 품목에 담으므로, 이 검사가 곧
 * 유스케이스가 쓸 데이터를 만든다(ADR 0014).
 */
@ValidatedApplicationService
class OrderModifyService(
    private val orderRepository: OrderRepository,
    private val productFinder: ProductFinder,
) : OrderCreator {
    /**
     * 품목마다 상품을 읽어 그 이름·단가를 담는다. 논리 삭제된 상품은 주문할 수 없다. 상품을 바꾸지 않으므로 잠그지 않는다.
     * 브랜드 삭제가 상품을 함께 삭제하므로 확정처럼 브랜드는 따로 보지 않는다(ADR 0017, 0018).
     */
    override fun create(userId: Long, request: OrderCreateRequest): Order {
        val products = request.items.map { item ->
            val product = productFinder.findOrNull(item.productId) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
            OrderProduct(product.id, product.name, product.price, item.quantity)
        }
        return orderRepository.save(Order(userId, products))
    }
}
