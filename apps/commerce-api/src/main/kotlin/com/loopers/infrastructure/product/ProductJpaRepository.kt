package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import org.springframework.data.jpa.repository.JpaRepository

/**
 * [Product]의 Spring Data JPA 저장소. [ProductRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다.
 * 삭제된 행을 거르는 조건은 [Product]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 */
interface ProductJpaRepository : JpaRepository<Product, Long>
