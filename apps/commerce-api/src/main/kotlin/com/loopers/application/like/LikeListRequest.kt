package com.loopers.application.like

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 좋아요 목록 입력. 사용자 식별자는 요청자에서 오므로 여기 없고 [LikeService.findLikedProducts]의 파라미터다(설계 5.27).
 * 조각의 크기에 상한을 두는 것은 한 번에 읽는 양을 API가 정하기 위해서다.
 *
 * 제약이 붙는 자리와 까닭은 [com.loopers.application.product.ProductListRequest]와 같고(설계 5.18),
 * 목록 입력을 제약으로 거르는 까닭은 설계 5.22에 있다. 개념마다 Request를 따로 두어 한쪽의 범위가 바뀌어도
 * 다른 쪽이 따라가지 않게 한다(설계 5.17).
 */
data class LikeListRequest(
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
