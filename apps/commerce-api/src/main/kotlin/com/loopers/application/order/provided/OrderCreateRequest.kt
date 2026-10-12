package com.loopers.application.order.provided

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class OrderCreateRequest(
    @Valid
    @Size(min = 1, max = 100, message = "주문 품목은 {min}개 이상 {max}개 이하여야 합니다.")
    val items: List<Item>,
) {
    data class Item(
        @Positive(message = "상품 ID는 1 이상이어야 합니다.")
        val productId: Long,
        @Positive(message = "수량은 1개 이상이어야 합니다.")
        val quantity: Int,
    )
}
