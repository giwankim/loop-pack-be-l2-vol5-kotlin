package com.loopers.application.product.required

import com.loopers.domain.product.Product
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Lock
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

    /** `CrudRepository.saveAll`과 이름·매개변수가 같아 `SimpleJpaRepository`로 간다. 항목마다 [save]를 부른다(설계 5.37). */
    fun saveAll(products: Iterable<Product>): List<Product>

    /**
     * `CrudRepository.findById`와 이름·매개변수가 같아 `EntityManager.find`로 간다.
     * 없으면 null이다. 반환을 non-null로 적으면 없을 때 예외를 던진다(설계 5.20).
     */
    fun findById(id: Long): Product?

    /**
     * [id] 상품을 `FOR UPDATE`로 잠가 읽는다. 없거나 삭제됐으면 null이다(ADR 0018).
     * `find`와 `By` 사이는 Spring Data가 설명으로 보므로 `@Query` 없이 `id`로 찾는 파생 조회다. 상품 표만 읽고 `brand`는 LAZY라
     * 브랜드 행은 잠그지 않는다. 잠금 읽기는 기다린 뒤 가장 최근에 커밋된 행을 읽으므로, 그 사이 커밋된 삭제도 `@SQLRestriction`이 걸러 낸다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): Product?

    /**
     * [id] 상품을 브랜드와 함께 읽는다. 상품이 없거나 삭제됐거나 브랜드가 삭제됐으면 null이다.
     *
     * 브랜드를 inner join으로 건너므로 [com.loopers.domain.brand.Brand]의 `@SQLRestriction`이 그 조인에도 붙어
     * 삭제된 브랜드의 상품은 행이 남아 있어도 나오지 않는다. 상품 목록이 삭제된 브랜드를 거르는 방법과 같다(설계 7).
     */
    @Query("select p from Product p join fetch p.brand where p.id = :id")
    fun findByIdWithActiveBrand(id: Long): Product?

    /**
     * [userId] 사용자가 좋아요를 누른 상품 한 조각. 최근에 누른 상품이 앞서고, 누른 시각이 같으면 나중에 누른 쪽이 앞선다.
     * 차례를 정하는 값이 상품이 아니라 관계에 있으므로 정렬 기준을 받는 [ProductListRepository.findAll]과 달리 기준을 고르지 않는다.
     *
     * 삭제된 상품은 없는 상품이므로 조각에 오르지 않는다. 상품이 조회의 root라 [Product]의 `@SQLRestriction`이 붙고,
     * 걸러내는 일을 조회가 하기 때문에 조각의 크기와 `hasNext`도 남은 상품만 센다. 좋아요 행은 그대로 있다(ADR 0001).
     *
     * 차례가 좋아요의 값으로 정해지므로 [Pageable]의 [org.springframework.data.domain.Sort]가 아니라 쿼리가 직접 적는다.
     * [pageable]에는 조각의 위치와 크기만 싣는다. Spring Data의 [Slice]이므로 `size + 1`개를 조회해 `hasNext`를 정하고
     * 총 개수는 세지 않는다(설계 5.5).
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

    /**
     * [brandId] 브랜드의 삭제되지 않은 상품을 id 오름차순으로 `FOR UPDATE`로 잠가 읽는다. 브랜드 삭제의 연쇄가 부른다(ADR 0017).
     *
     * 잠금은 `ORDER BY`가 아니라 훑는 인덱스의 차례로 걸린다. `brand_id` 인덱스의 항목이 `(brand_id, id)`이므로 id 차례이고,
     * `OrderById`는 그 뜻을 적고 결과의 차례를 고정한다(ADR 0018). `brandId`는 상품의 외래 키라 브랜드로 가는 조인이 없어
     * 상품 행만 잠근다. `find`와 `By` 사이는 Spring Data가 설명으로 보므로 `@Query`가 필요 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByBrandIdOrderById(brandId: Long): List<Product>
}
