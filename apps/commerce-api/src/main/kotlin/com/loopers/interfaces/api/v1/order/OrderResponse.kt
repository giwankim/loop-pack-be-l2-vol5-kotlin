package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderInfo
import com.loopers.domain.order.OrderStatus
import java.time.Instant

/**
 * 고객이 보는 자기 주문. 주문한 사용자의 식별자는 싣지 않는다. 자기 주문만 볼 수 있어 실을 것이 없다.
 * 품목은 관리자 응답과 같은 [OrderLineItemResponse]를 쓴다(카탈로그 설계 5.7).
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
        fun from(info: OrderInfo): OrderResponse = OrderResponse(
            orderId = info.orderId,
            status = info.status,
            items = info.items.map(OrderLineItemResponse::from),
            totalAmount = info.totalAmount,
            createdAt = info.createdAt,
            paidAmount = info.paidAmount,
            confirmedAt = info.confirmedAt,
        )
    }
}
