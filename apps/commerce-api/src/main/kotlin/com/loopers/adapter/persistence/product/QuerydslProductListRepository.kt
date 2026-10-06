package com.loopers.adapter.persistence.product

import com.loopers.adapter.persistence.shared.fetchSlice
import com.loopers.application.product.required.ProductListRepository
import com.loopers.domain.like.QLike.like
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductSort
import com.loopers.domain.product.QProduct.product
import com.loopers.domain.shared.PageSlice
import com.querydsl.core.types.OrderSpecifier
import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Component

/**
 * [ProductListRepository]의 QueryDSL 구현. 상품 하나와 좋아요 목록은 Spring Data의
 * [com.loopers.application.product.required.ProductRepository]가 맡고, 여기는 상품 목록만 짠다.
 *
 * 이름에 `ProductRepositoryImpl`을 쓰지 않는다. Spring Data는 그 이름의 빈을 패키지와 상관없이 `ProductRepository`의
 * custom 구현으로 말없이 붙인다.
 */
@Component
class QuerydslProductListRepository(
    private val queryFactory: JPAQueryFactory,
) : ProductListRepository {
    /**
     * 브랜드 필터도 정렬 기준도 조각마다 달라지므로 목록은 QueryDSL로 짠다(설계 5.32).
     * 정렬 기준이 셋인데 그중 하나만 조인을 요구하므로, 조회 메서드를 기준마다 두면 기준이 늘 때마다 메서드가 는다.
     *
     * 총 개수를 세는 쿼리는 나가지 않는다(설계 5.5). 조각을 만드는 규칙은 [fetchSlice] 하나에 있다.
     *
     * 브랜드를 fetch join으로 함께 읽는 까닭은 항목마다 브랜드 이름을 읽기 때문이다. 없으면 조각 크기만큼 조회가 붙는다.
     * `@ManyToOne(optional = false)`이라 inner join이고, [com.loopers.domain.brand.Brand]의 `@SQLRestriction`이
     * 그 조인에도 붙어 삭제된 브랜드의 상품은 목록에서 빠진다(설계 7).
     */
    override fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): PageSlice<Product> =
        queryFactory
            .selectFrom(product)
            .innerJoin(product.brand).fetchJoin()
            .where(brandId?.let { product.brand.id.eq(it) })
            .orderedBy(sort)
            .fetchSlice(page, size)

    /**
     * 정렬 기준이 요구하는 차례를 쿼리에 붙인다. 어느 기준이든 마지막은 id 내림차순이라 동률이 남지 않는다.
     *
     * 좋아요 많은순만 조인과 `group by`가 함께 붙는다. 정렬 키가 상품의 컬럼이 아니라 관계를 세어 나오는 값이라서다.
     * `left join`이라 좋아요가 하나도 없는 상품도 0으로 남아 목록의 끝에 온다. 다른 두 기준은 이 조인을 치르지 않는다.
     *
     * 세어 나온 값은 정렬에만 쓰고 돌려주지 않는다. 항목의 `likeCount`는 [com.loopers.domain.like.LikeRepository]가
     * 따로 센다(설계 5.28의 C). 그래서 이 저장소의 반환은 기준이 무엇이든 [Product]다.
     *
     * 가격은 `Money`가 `@Embeddable`이므로 경로가 `price`가 아니라 `price.amount`다.
     * 이런 매핑 지식은 adapter.persistence의 것이고 [ProductSort]는 무엇을 읽는지 모른다.
     */
    private fun JPAQuery<Product>.orderedBy(sort: ProductSort): JPAQuery<Product> = when (sort) {
        ProductSort.LATEST -> orderBy(product.createdAt.desc(), ID_DESC)

        ProductSort.PRICE_ASC -> orderBy(product.price.amount.asc(), ID_DESC)

        ProductSort.LIKES_DESC ->
            leftJoin(like).on(like.productId.eq(product.id))
                .groupBy(product, product.brand)
                .orderBy(like.count().desc(), ID_DESC)
    }

    companion object {
        /** 나중에 받은 식별자가 앞선다. 모든 정렬 기준이 마지막에 쓰는 동률 규칙이다. */
        private val ID_DESC: OrderSpecifier<Long> = product.id.desc()
    }
}
