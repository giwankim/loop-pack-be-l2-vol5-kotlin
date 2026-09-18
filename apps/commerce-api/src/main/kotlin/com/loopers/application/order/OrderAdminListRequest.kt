package com.loopers.application.order

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 관리자 주문 목록 입력. [userId]가 없으면 모든 사용자의 주문을 본다. 정렬 기준은 받지 않는다(설계 6).
 * 제약이 붙는 자리와 까닭은 [OrderCreateRequest]와 같다(카탈로그 설계 5.18).
 *
 * 페이지 값의 범위는 [OrderListRequest]의 companion을 읽는다. 역할에 따라 달라지지 않는 값이므로 한 기능 안에서는
 * 수식어가 없는 쪽이 갖고, 수식어가 붙은 쪽이 읽는다(카탈로그 설계 5.24의 마지막 줄).
 * 카탈로그에서 [com.loopers.application.product.ProductAdminListRequest]가
 * [com.loopers.application.product.ProductListRequest]의 companion을 읽는 모양 그대로다.
 * 주문 기능 밖의 목록과는 상수를 나눠 갖는다. 개념마다 Request를 따로 두어 한쪽의 범위가 바뀌어도 다른 쪽이
 * 따라가지 않게 하기 때문이다(카탈로그 설계 5.17).
 */
data class OrderAdminListRequest(
    val userId: Long? = null,
    @field:Min(0, message = "page는 0 이상이어야 합니다.")
    val page: Int = OrderListRequest.DEFAULT_PAGE,
    @field:Min(1, message = "size는 1 이상이어야 합니다.")
    @field:Max(OrderListRequest.MAX_SIZE.toLong(), message = "size는 {value} 이하여야 합니다.")
    val size: Int = OrderListRequest.DEFAULT_SIZE,
)
