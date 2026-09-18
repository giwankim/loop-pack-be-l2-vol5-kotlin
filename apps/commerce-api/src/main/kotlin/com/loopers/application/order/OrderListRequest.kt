package com.loopers.application.order

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 내 주문 목록 입력. 사용자 식별자는 요청자에서 오므로 여기 없고 [OrderService.findAll]의 파라미터다(카탈로그 설계 5.27).
 * 정렬 기준은 고르지 않는다. 주문 목록의 차례는 최신순 하나뿐이다(설계 6).
 * 고르는 차례가 있는 것은 상품 목록뿐이고 그 낱말도 상품의 것이다(CONTEXT.md 상품 목록 정렬).
 *
 * 제약이 붙는 자리와 까닭, 페이지 값의 범위는 [com.loopers.application.like.LikeListRequest]와 같다(카탈로그 설계 5.17, 5.18, 5.22).
 * 개념마다 Request를 따로 두어 한쪽의 범위가 바뀌어도 다른 쪽이 따라가지 않게 한다.
 */
data class OrderListRequest(
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
