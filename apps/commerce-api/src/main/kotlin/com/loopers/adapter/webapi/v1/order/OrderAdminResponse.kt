package com.loopers.adapter.webapi.v1.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.math.BigInteger
import java.time.Instant

/**
 * 관리자가 보는 주문. 고객이 보는 [OrderResponse]에 주문한 사용자의 식별자를 더한 모양이다.
 *
 * 역할별 DTO가 같은 [Order]를 읽어 각자 내보낼 필드를 고른다(카탈로그 설계 5.7, ADR 0014). 두 응답이 갈리는 것은
 * [userId] 하나이고, 품목은 역할에 따라 갈리지 않으므로 [OrderLineItemResponse]를 함께 쓴다.
 * 현재 User는 식별자만 가진 실습 데이터이므로 주문한 사용자의 이름·연락처를 새로 만들지 않는다(설계 6).
 *
 * `paidAmount`는 DRAFT에 없으므로 기존 Jackson 정책에 따라 응답에서 생략된다. 주문이 저장한 확정 시각은 고객 응답처럼
 * 싣지 않는다(설계 20).
 */
data class OrderAdminResponse(
    val orderId: Long,
    val userId: Long,
    val status: OrderStatus,
    val items: List<OrderLineItemResponse>,
    val totalAmount: BigInteger,
    val createdAt: Instant,
    val paidAmount: BigInteger?,
) {
    companion object {
        fun from(order: Order): OrderAdminResponse {
            return OrderAdminResponse(
                orderId = order.id,
                userId = order.userId,
                status = order.status,
                items = order.lineItems.map(OrderLineItemResponse::from),
                totalAmount = order.totalAmount.amount,
                createdAt = order.createdAt,
                paidAmount = order.paidAmount?.amount,
            )
        }
    }
}
