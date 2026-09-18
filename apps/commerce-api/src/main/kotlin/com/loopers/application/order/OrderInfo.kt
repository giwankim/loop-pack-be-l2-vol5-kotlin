package com.loopers.application.order

import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderStatus
import java.time.Instant

data class OrderInfo(
    val orderId: Long,
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
