package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * [Product]의 Spring Data JPA 저장소. [ProductRepositoryImpl]이 식별자로 읽고 쓰는 일을 이것에 맡긴다.
 * 삭제된 행을 거르는 조건은 [Product]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 *
 * 상품 목록은 여기 없다. 브랜드 필터와 정렬 기준이 조각마다 달라 [ProductRepositoryImpl]이 QueryDSL로 짠다(설계 5.29).
 * 좋아요 목록은 차례가 하나뿐이라 여기 남는다.
 */
interface ProductJpaRepository : JpaRepository<Product, Long> {
    /**
     * [userId] 사용자가 좋아요를 누른 상품. 차례가 좋아요의 값으로 정해지므로 [Pageable]의 [org.springframework.data.domain.Sort]가
     * 아니라 쿼리가 직접 적는다. 상품이 조회의 root라 [Product]의 `@SQLRestriction`이 붙고 삭제된 상품은 빠진다.
     *
     * 좋아요는 상품을 식별자로만 가리키므로(설계 2) 연관을 건너는 join이 아니라 `on`으로 짝을 맞춘다.
     * 같은 사용자–상품 쌍의 좋아요는 하나뿐이라 상품이 두 번 오르지 않는다.
     */
    @EntityGraph(attributePaths = ["brand"])
    @Query(
        "select p from Product p join com.loopers.domain.like.Like l on l.productId = p.id " +
            "where l.userId = :userId order by l.createdAt desc, l.id desc",
    )
    fun findAllLikedBy(userId: Long, pageable: Pageable): Slice<Product>

    /** `brandId`는 상품의 외래 키라 [com.loopers.domain.brand.Brand]로 가는 조인이 없다. */
    fun existsByBrandId(brandId: Long): Boolean
}
