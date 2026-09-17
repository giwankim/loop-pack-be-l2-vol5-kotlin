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
}
