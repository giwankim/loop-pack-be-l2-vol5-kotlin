package com.loopers.application.product

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 관리자 상품 목록 입력. [brandId]가 없으면 모든 브랜드의 상품을 본다.
 * 제약이 붙는 자리와 까닭은 [ProductAdminRegisterRequest]와 같다(설계 5.18).
 *
 * 고객 목록의 [ProductListRequest]와 달리 정렬 기준을 받지 않는다. 관리자 목록은 늦게 등록된 상품이 앞서는
 * 한 가지 차례만 쓰므로, 고객 쪽에 기준이 생겨도 관리자 API가 따라 넓어지지 않도록 입력을 갈랐다(설계 5.24).
 * 역할을 이름에 적는 쪽이 관리자인 것은 고객 쪽이 기본이기 때문이다(CONTEXT.md 고객).
 */
data class ProductAdminListRequest(
    val brandId: Long? = null,
    @field:Min(0, message = "page는 0 이상이어야 합니다.")
    val page: Int = ProductListRequest.DEFAULT_PAGE,
    @field:Min(1, message = "size는 1 이상이어야 합니다.")
    @field:Max(ProductListRequest.MAX_SIZE.toLong(), message = "size는 {value} 이하여야 합니다.")
    val size: Int = ProductListRequest.DEFAULT_SIZE,
)
