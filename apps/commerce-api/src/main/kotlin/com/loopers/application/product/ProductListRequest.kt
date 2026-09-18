package com.loopers.application.product

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 상품 목록 입력. [brandId]가 없으면 모든 브랜드의 상품을 본다.
 * 조각의 크기에 상한을 두는 것은 한 번에 읽는 양을 API가 정하기 위해서다. 제약이 붙는 자리와 까닭은
 * [ProductRegisterRequest]와 같다(설계 5.18).
 */
data class ProductListRequest(
    val brandId: Long? = null,
    @field:Min(0, message = "page는 0 이상이어야 합니다.")
    val page: Int = DEFAULT_PAGE,
    @field:Min(1, message = "size는 1 이상이어야 합니다.")
    @field:Max(MAX_SIZE.toLong(), message = "size는 {value} 이하여야 합니다.")
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE = 0
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}
