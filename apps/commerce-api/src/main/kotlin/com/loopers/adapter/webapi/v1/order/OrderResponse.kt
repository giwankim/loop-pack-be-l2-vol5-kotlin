package com.loopers.adapter.webapi.v1.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.time.Instant

/**
 * 고객이 보는 자기 주문. 주문한 사용자의 식별자는 싣지 않는다. 자기 주문만 볼 수 있어 실을 것이 없다.
 * 품목은 관리자 응답과 같은 [OrderLineItemResponse]를 쓴다(카탈로그 설계 5.7).
 * 주문 조각이 품목까지 읽어 둔 [Order]에서 바로 옮긴다(ADR 0014).
 */
data class OrderResponse(
    val orderId: Long,
    val status: OrderStatus,
    val items: List<OrderLineItemResponse>,
    val totalAmount: Long,
    val createdAt: Instant,
    val paidAmount: Long?,
    val confirmedAt: Instant?,
) {
    companion object {
        fun from(order: Order): OrderResponse {
            return OrderResponse(
                orderId = order.id,
                status = order.status,
                items = order.items.map(OrderLineItemResponse::from),
                totalAmount = order.totalAmount.amount,
                createdAt = order.createdAt,
                paidAmount = order.paidAmount?.amount,
                confirmedAt = order.confirmedAt,
            )
        }
    }
}
