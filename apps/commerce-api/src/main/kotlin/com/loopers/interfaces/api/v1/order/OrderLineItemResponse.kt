package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderInfo

/**
 * 주문 품목 하나. 고객 응답과 관리자 응답이 함께 쓴다.
 *
 * 역할별 DTO가 각자 내보낼 필드를 고르는 것은 주문 쪽도 같지만(카탈로그 설계 5.7), 품목이 고르는 필드는
 * 역할에 따라 갈리지 않는다. 관리자 응답이 더 싣는 것은 [OrderAdminResponse.userId] 하나다.
 * 한쪽 역할만 다른 품목을 내보내게 되면 그때 이 타입을 가른다.
 */
data class OrderLineItemResponse(
    val productId: Long,
    val productName: String,
    val unitPrice: Long,
    val quantity: Int,
    val lineAmount: Long,
) {
    companion object {
        fun from(item: OrderInfo.Item): OrderLineItemResponse = OrderLineItemResponse(
            productId = item.productId,
            productName = item.productName,
            unitPrice = item.unitPrice,
            quantity = item.quantity,
            lineAmount = item.lineAmount,
        )
    }
}
