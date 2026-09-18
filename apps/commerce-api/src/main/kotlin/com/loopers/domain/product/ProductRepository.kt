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
}
