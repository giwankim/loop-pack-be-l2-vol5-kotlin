package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * [ProductRepository]의 Spring Data JPA 구현. [save]는 [JpaRepository]에서 그대로 물려받는다.
 * 삭제된 행을 거르는 조건은 [Product]의 `@SQLRestriction`이 모든 쿼리에 붙이므로 여기서는 적지 않는다.
 */
interface ProductJpaRepository : JpaRepository<Product, Long>, ProductRepository {
    @Query("select p from Product p where p.id = :id")
    override fun find(id: Long): Product?
}
