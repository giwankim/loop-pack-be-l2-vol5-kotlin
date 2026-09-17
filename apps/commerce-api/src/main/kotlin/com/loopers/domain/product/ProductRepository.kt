package com.loopers.domain.product

/**
 * 상품 저장 약속. 삭제된 상품은 없는 상품이므로 조회는 존재만 묻고, 삭제된 행은 없다고 답한다.
 */
interface ProductRepository {
    fun save(product: Product): Product

    fun find(id: Long): Product?
}
