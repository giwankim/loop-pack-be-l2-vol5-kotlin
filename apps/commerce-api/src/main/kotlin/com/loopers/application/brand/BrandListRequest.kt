package com.loopers.application.brand

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 브랜드 목록 입력. 조각의 크기에 상한을 두는 것은 한 번에 읽는 양을 API가 정하기 위해서다.
 * 제약이 붙는 자리와 까닭은 [BrandRegisterRequest]와 같고(설계 5.18), 목록 입력을 제약으로 거르는 까닭은 설계 5.22에 있다.
 *
 * 상품 목록의 [com.loopers.application.product.ProductAdminListRequest]와 같은 모양이다. 개념마다 Request를 따로 두어
 * 한쪽의 범위가 바뀌어도 다른 쪽이 따라가지 않게 한다(설계 5.17).
 */
data class BrandListRequest(
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
