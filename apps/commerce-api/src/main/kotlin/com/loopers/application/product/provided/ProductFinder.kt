package com.loopers.application.product.provided

import com.loopers.application.brand.required.ActiveProductChecker
import com.loopers.domain.shared.PageSlice
import jakarta.validation.Valid

/**
 * 상품 조각이 내주는 읽기. 고객과 관리자의 상품 조회가 부른다. 삭제된 상품은 없는 상품이므로 어느 읽기에도 나오지 않는다.
 * 항목마다 브랜드 이름을 연관에서 건너 읽고 좋아요 수를 따로 세므로 엔티티가 아니라 [ProductInfo]를 돌려준다(설계 5.7).
 *
 * 브랜드가 선언한 [ActiveProductChecker]에도 답한다. 상품이 브랜드의 물음을 따르므로 브랜드는 상품을 모른다.
 */
interface ProductFinder : ActiveProductChecker {
    /** [id]가 가리키는 상품이 없거나 삭제됐으면 `PRODUCT_NOT_FOUND`를 던진다. */
    fun find(id: Long): ProductInfo

    /** 관리자 목록. 늦게 등록된 상품이 앞서는 한 조각이고 정렬 기준을 고르지 않는다. 총 개수는 세지 않는다(설계 5.5). */
    fun findAll(@Valid request: ProductAdminListRequest): PageSlice<ProductInfo>

    /** 고객 목록. 고객이 고른 차례로 놓인 한 조각. 모르는 정렬 기준이면 `INVALID_SORT`를 던진다(설계 5.24). */
    fun findAll(@Valid request: ProductListRequest): PageSlice<ProductInfo>
}
