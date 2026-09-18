package com.loopers.domain.order

import com.loopers.domain.shared.Money

/** 생성 시 서버가 읽은 상품 정보와 합산한 구매 수량. Product 연관이 아니다. */
data class OrderProduct(
    val productId: Long,
    val productName: String,
    val unitPrice: Money,
    val quantity: Int,
) {
    init {
        if (productId <= 0 || quantity <= 0 || unitPrice.amount <= 0 || productName.isBlank() || productName.length > 100) {
            throw InvalidOrderException("주문 품목에는 상품 식별자, 이름과 양수 단가·수량이 필요합니다.")
        }
    }
}
