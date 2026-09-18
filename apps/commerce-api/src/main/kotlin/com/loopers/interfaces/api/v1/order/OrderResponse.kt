package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderInfo
import com.loopers.domain.order.OrderStatus
import java.time.Instant

data class OrderResponse(
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
        fun from(info: OrderInfo): OrderResponse = OrderResponse(
            orderId = info.orderId,
            status = info.status,
            items = info.items.map { Item(it.productId, it.productName, it.unitPrice, it.quantity, it.lineAmount) },
            totalAmount = info.totalAmount,
            createdAt = info.createdAt,
            paidAmount = info.paidAmount,
            confirmedAt = info.confirmedAt,
        )
    }
}
