package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

/**
 * [Product]의 Spring Data JPA 저장소. [ProductRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다.
 * 삭제된 행을 거르는 조건은 [Product]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 */
interface ProductJpaRepository : JpaRepository<Product, Long> {
    /** 조건 없는 조각 조회. `findAll(Pageable)`은 총 개수를 세는 `Page`를 돌려주므로 쓰지 않는다. */
    @EntityGraph(attributePaths = ["brand"])
    fun findAllBy(pageable: Pageable): Slice<Product>

    @EntityGraph(attributePaths = ["brand"])
    fun findAllByBrandId(brandId: Long, pageable: Pageable): Slice<Product>

    /** `brandId`는 상품의 외래 키라 [com.loopers.domain.brand.Brand]로 가는 조인이 없다. */
    fun existsByBrandId(brandId: Long): Boolean
}
