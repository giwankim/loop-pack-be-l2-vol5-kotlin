package com.loopers.adapter.webapi.v1.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.math.BigInteger
import java.time.Instant

/**
 * 고객이 보는 자기 주문. 주문한 사용자의 식별자는 싣지 않는다. 자기 주문만 볼 수 있어 실을 것이 없다.
 * 주문이 저장한 확정 시각도 싣지 않는다. 과제가 묻지 않았고 응답에서 읽는 쪽도 없다(설계 20).
 * 품목은 관리자 응답과 같은 [OrderLineItemResponse]를 쓴다(카탈로그 설계 5.7).
 * 주문 조각이 품목까지 읽어 둔 [Order]에서 바로 옮긴다(ADR 0014).
 */
data class OrderResponse(
    val orderId: Long,
    val status: OrderStatus,
    val items: List<OrderLineItemResponse>,
    val totalAmount: BigInteger,
    val createdAt: Instant,
    val paidAmount: BigInteger?,
) {
    companion object {
        fun from(order: Order): OrderResponse {
            return OrderResponse(
                orderId = order.id,
                status = order.status,
                items = order.lineItems.map(OrderLineItemResponse::from),
                totalAmount = order.totalAmount.amount,
                createdAt = order.createdAt,
                paidAmount = order.paidAmount?.amount,
            )
        }
    }
}
