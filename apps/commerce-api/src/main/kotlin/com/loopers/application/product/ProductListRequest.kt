package com.loopers.application.product

import com.loopers.domain.product.ProductSort
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * 상품 목록 입력. [brandId]가 없으면 모든 브랜드의 상품을 본다.
 * 조각의 크기에 상한을 두는 것은 한 번에 읽는 양을 API가 정하기 위해서다. 제약이 붙는 자리와 까닭은
 * [ProductRegisterRequest]와 같다(설계 5.18).
 *
 * 고객 쪽이 기본이라 수식어가 없고 관리자 쪽만 [ProductAdminListRequest]로 갈린다(CONTEXT.md 고객).
 * 페이지 값의 범위는 역할에 따라 달라지지 않으므로 두 입력이 이 companion의 상수를 함께 쓴다.
 *
 * [sort]에는 제약 애노테이션이 없다. `page`·`size`는 숫자의 범위라 Bean Validation이 그대로 말할 수 있지만
 * 정렬 기준은 [ProductSort]가 아는 낱말이라, 제약으로 옮기려면 철자를 정규식에 한 벌 더 적거나
 * [ProductSort.from]만 부르는 검사기를 따로 만들어야 한다. 어느 쪽이든 철자를 아는 곳이 둘이 된다.
 * 그래서 [sort]는 [ProductService]가 기준으로 옮기면서 거른다(설계 5.24).
 */
data class ProductListRequest(
    val brandId: Long? = null,
    @field:Min(0, message = "page는 0 이상이어야 합니다.")
    val page: Int = DEFAULT_PAGE,
    @field:Min(1, message = "size는 1 이상이어야 합니다.")
    @field:Max(MAX_SIZE.toLong(), message = "size는 {value} 이하여야 합니다.")
    val size: Int = DEFAULT_SIZE,
    val sort: String = DEFAULT_SORT,
) {
    companion object {
        const val DEFAULT_PAGE = 0
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        /** 아무것도 고르지 않으면 늦게 등록된 상품이 앞선다. */
        val DEFAULT_SORT = ProductSort.LATEST.apiValue
    }
}
