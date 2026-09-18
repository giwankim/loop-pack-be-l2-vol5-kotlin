package com.loopers.application.order

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class OrderCreateRequest(
    @field:Valid
    @field:Size(min = 1, max = 100, message = "주문 품목은 {min}개 이상 {max}개 이하여야 합니다.")
    val items: List<Item>,
) {
    data class Item(
        @field:Positive(message = "상품 ID는 1 이상이어야 합니다.")
        val productId: Long,
        @field:Positive(message = "수량은 1개 이상이어야 합니다.")
        val quantity: Int,
    )

    fun normalizedItems(): List<Item> {
        val quantities = sortedMapOf<Long, Int>()
        try {
            items.forEach { item ->
                quantities[item.productId] = Math.addExact(quantities[item.productId] ?: 0, item.quantity)
            }
        } catch (e: ArithmeticException) {
            throw CoreException(ErrorType.INVALID_POINT_ORDER_REQUEST)
        }
        return quantities.map { (productId, quantity) -> Item(productId, quantity) }
    }
}
