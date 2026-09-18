package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderInfo
import com.loopers.domain.order.OrderStatus
import java.time.Instant

/**
 * 관리자가 보는 주문. 고객이 보는 [OrderResponse]에 주문한 사용자의 식별자를 더한 모양이다.
 *
 * 역할별 DTO가 같은 [OrderInfo]를 읽어 각자 내보낼 필드를 고른다(카탈로그 설계 5.7). 두 응답이 갈리는 것은
 * [userId] 하나이고, 품목은 역할에 따라 갈리지 않으므로 [OrderLineItemResponse]를 함께 쓴다.
 * 현재 User는 식별자만 가진 실습 데이터이므로 주문한 사용자의 이름·연락처를 새로 만들지 않는다(설계 6).
 *
 * `paidAmount`·`confirmedAt`은 DRAFT에 없으므로 기존 Jackson 정책에 따라 응답에서 생략된다.
 */
data class OrderAdminResponse(
    val orderId: Long,
    val userId: Long,
    val status: OrderStatus,
    val items: List<OrderLineItemResponse>,
    val totalAmount: Long,
    val createdAt: Instant,
    val paidAmount: Long?,
    val confirmedAt: Instant?,
) {
    companion object {
        fun from(info: OrderInfo): OrderAdminResponse = OrderAdminResponse(
            orderId = info.orderId,
            userId = info.userId,
            status = info.status,
            items = info.items.map(OrderLineItemResponse::from),
            totalAmount = info.totalAmount,
            createdAt = info.createdAt,
            paidAmount = info.paidAmount,
            confirmedAt = info.confirmedAt,
        )
    }
}
