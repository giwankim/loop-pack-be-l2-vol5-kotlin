package com.loopers.application.product.required

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductSort
import com.loopers.domain.shared.PageSlice

/**
 * 상품 목록. 브랜드 필터도 정렬 기준도 조각마다 달라져 QueryDSL로 짜는데(설계 5.32), application은 QueryDSL을 모르므로
 * 구현은 adapter.persistence에 둔다.
 *
 * Spring Data의 저장소가 아닌 순수 포트라 [ProductRepository]와 따로 선언한다. Spring Data의 custom fragment로 붙이면
 * 구현이 fragment 인터페이스의 패키지 아래에 있어야 찾히므로, QueryDSL 코드가 application으로 들어온다.
 */
interface ProductListRepository {
    /**
     * [sort]가 정한 차례로 놓인 한 조각. [brandId]가 있으면 그 브랜드의 상품만 고른다.
     * 어느 기준이든 동률은 id 내림차순으로 깬다.
     */
    fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): PageSlice<Product>
}
