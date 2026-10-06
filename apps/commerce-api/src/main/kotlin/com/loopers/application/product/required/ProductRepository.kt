package com.loopers.application.product.required

import com.loopers.application.shared.toPageSlice
import com.loopers.domain.product.Product
import com.loopers.domain.shared.PageSlice
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * 상품 저장소. Spring Data가 구현을 만든다. 삭제된 상품은 없는 상품이므로 조회는 존재만 묻고, 삭제된 행은 없다고 답한다.
 * 삭제된 행을 거르는 조건은 [Product]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 *
 * 상품 목록은 여기 없다. 브랜드 필터와 정렬 기준이 조각마다 달라 QueryDSL로 짜고, application은 QueryDSL을 모르므로
 * [ProductListRepository]가 따로 맡는다(설계 5.32). 좋아요 목록은 차례가 하나뿐이라 여기 남는다.
 */
interface ProductRepository : Repository<Product, Long> {
    fun save(product: Product): Product

    /**
     * `CrudRepository.findById`와 이름·매개변수가 같아 `EntityManager.find`로 간다.
     * 없으면 null이다. 반환을 non-null로 적으면 없을 때 예외를 던진다(설계 5.20).
     */
    fun findById(id: Long): Product?

    /**
     * [userId] 사용자가 좋아요를 누른 상품 한 조각. 최근에 누른 상품이 앞서고, 누른 시각이 같으면 나중에 누른 쪽이 앞선다.
     * 차례를 정하는 값이 상품이 아니라 관계에 있으므로 정렬 기준을 받는 [ProductListRepository.findAll]과 달리 기준을 고르지 않는다.
     *
     * 삭제된 상품은 없는 상품이므로 조각에 오르지 않는다. 걸러내는 일을 조회가 하기 때문에 조각의 크기와
     * `hasNext`도 남은 상품만 센다. 좋아요 행은 그대로 있다(ADR 0001).
     *
     * 본문이 있는 메서드는 JVM default method가 되고, Spring Data는 그것을 쿼리로 만들지 않고 본문을 실행한다.
     * 차례는 쿼리가 적으므로 [PageRequest]에는 조각의 위치와 크기만 싣는다. 정렬을 함께 실으면 그 기준이 쿼리의 것을 덮는다.
     */
    fun findAllLikedBy(userId: Long, page: Int, size: Int): PageSlice<Product> =
        findSliceLikedBy(userId, PageRequest.of(page, size)).toPageSlice()

    /**
     * [findAllLikedBy]가 맡기는 조회. 포트가 Spring Data의 조각을 내주지 않도록 바깥에서는 [findAllLikedBy]를 부른다.
     *
     * 차례가 좋아요의 값으로 정해지므로 [Pageable]의 [org.springframework.data.domain.Sort]가 아니라 쿼리가 직접 적는다.
     * 상품이 조회의 root라 [Product]의 `@SQLRestriction`이 붙고 삭제된 상품은 빠진다.
     *
     * 좋아요는 상품을 식별자로만 가리키므로(설계 2) 연관을 건너는 join이 아니라 `on`으로 짝을 맞춘다.
     * 같은 사용자–상품 쌍의 좋아요는 하나뿐이라 상품이 두 번 오르지 않는다.
     */
    @EntityGraph(attributePaths = ["brand"])
    @Query(
        "select p from Product p join com.loopers.domain.like.Like l on l.productId = p.id " +
            "where l.userId = :userId order by l.createdAt desc, l.id desc",
    )
    fun findSliceLikedBy(userId: Long, pageable: Pageable): Slice<Product>

    /**
     * [brandId] 브랜드에 상품이 하나라도 남아 있는지. 브랜드 삭제 조건이 묻는다.
     * `brandId`는 상품의 외래 키라 [com.loopers.domain.brand.Brand]로 가는 조인이 없다.
     */
    fun existsByBrandId(brandId: Long): Boolean
}
