package com.loopers.domain.product

import jakarta.persistence.Embeddable

@Embeddable
data class Stock(
    val quantity: Int,
) {
    init {
        if (quantity < 0) {
            throw InvalidStockException("재고는 0 이상이어야 합니다.")
        }
    }

    /** 남은 수량이 0이면 참. */
    fun isEmpty(): Boolean = quantity == 0

    /** 양수 수량만 차감하며 부족하면 원래 값은 그대로다. */
    fun deduct(quantity: Int): Stock {
        if (quantity <= 0) throw InvalidStockException("차감 수량은 1개 이상이어야 합니다.")
        if (quantity > this.quantity) throw InsufficientStockException()
        return Stock(this.quantity - quantity)
    }
}
