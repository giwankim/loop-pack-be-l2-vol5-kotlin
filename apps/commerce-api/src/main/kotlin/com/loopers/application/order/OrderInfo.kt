package com.loopers.application.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.time.Instant

/**
 * 저장된 주문 하나. [userId]는 주문한 사용자의 식별자이며 관리자 조회만 응답에 싣는다.
 * 고객은 자기 주문만 볼 수 있어 응답에 실을 것이 없다([com.loopers.interfaces.api.v1.order.OrderResponse]).
 */
data class OrderInfo(
    val orderId: Long,
    val userId: Long,
    val status: OrderStatus,
    val items: List<Item>,
    val totalAmount: Long,
    val createdAt: Instant,
    val paidAmount: Long?,
    val confirmedAt: Instant?,
) {
    data class Item(val productId: Long, val productName: String, val unitPrice: Long, val quantity: Int, val lineAmount: Long)

    companion object {
        fun from(order: Order): OrderInfo = OrderInfo(
            orderId = order.id,
            userId = order.userId,
            status = order.status,
            items = order.items.map {
                Item(
                it.productId,
                it.productName,
                it.unitPrice.amount,
                it.quantity,
                it.lineAmount.amount,
            )
            },
            totalAmount = order.totalAmount.amount,
            createdAt = order.createdAt,
            paidAmount = order.paidAmount?.amount,
            confirmedAt = order.confirmedAt,
        )
    }
}
