package com.loopers.domain.product

import com.loopers.domain.shared.PageSlice

/**
 * 상품 저장 약속. 삭제된 상품은 없는 상품이므로 조회는 존재만 묻고, 삭제된 행은 없다고 답한다.
 */
interface ProductRepository {
    fun save(product: Product): Product

    fun findById(id: Long): Product?

    /**
     * [sort]가 정한 차례로 놓인 한 조각. [brandId]가 있으면 그 브랜드의 상품만 고른다.
     * 어느 기준이든 동률은 id 내림차순으로 깬다.
     */
    fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): PageSlice<Product>

    /**
     * [userId] 사용자가 좋아요를 누른 상품 한 조각. 최근에 누른 상품이 앞서고, 누른 시각이 같으면 나중에 누른 쪽이 앞선다.
     * 차례를 정하는 값이 상품이 아니라 관계에 있으므로 [sort]를 받는 [findAll]과 달리 기준을 고르지 않는다.
     *
     * 삭제된 상품은 없는 상품이므로 조각에 오르지 않는다. 걸러내는 일을 조회가 하기 때문에 조각의 크기와
     * `hasNext`도 남은 상품만 센다. 좋아요 행은 그대로 있다(ADR 0001).
     */
    fun findAllLikedBy(userId: Long, page: Int, size: Int): PageSlice<Product>

    /** [brandId] 브랜드에 상품이 하나라도 남아 있는지. 브랜드 삭제 조건이 묻는다. */
    fun existsByBrandId(brandId: Long): Boolean
}
