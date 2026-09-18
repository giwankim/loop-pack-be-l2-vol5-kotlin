package com.loopers.application.product

import jakarta.validation.constraints.Min

/**
 * 재고 변경 입력. 남은 수량을 빼거나 더하는 것이 아니라 최종 수량으로 맞춘다.
 * 0은 품절로 맞추는 정상 요청이고 음수만 거절된다. 제약이 붙는 자리와 까닭은 [ProductAdminRegisterRequest]와 같다(설계 5.18).
 */
data class ProductAdminStockUpdateRequest(
    @field:Min(0, message = "재고는 0 이상이어야 합니다.")
    val quantity: Int,
)
