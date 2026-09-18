package com.loopers.infrastructure.product

import com.loopers.domain.like.QLike.like
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import com.loopers.domain.product.QProduct.product
import com.loopers.domain.shared.PageSlice
import com.querydsl.core.types.OrderSpecifier
import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * [ProductRepository]의 구현. 메서드 이름만으로 끝나는 일과 좋아요 목록은 [ProductJpaRepository]에 맡기고, 상품 목록만 QueryDSL로 짠다.
 * 두 인터페이스를 하나로 합치지 않는 이유는 [com.loopers.infrastructure.brand.BrandRepositoryImpl]과 같다.
 */
@Component
class ProductRepositoryImpl(
    private val productJpaRepository: ProductJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : ProductRepository {
    override fun save(product: Product): Product = productJpaRepository.save(product)

    override fun findById(id: Long): Product? = productJpaRepository.findByIdOrNull(id)

    /**
     * 브랜드 필터도 정렬 기준도 조각마다 달라지므로 목록은 QueryDSL로 짠다(설계 5.32).
     * 정렬 기준이 셋인데 그중 하나만 조인을 요구하므로, 조회 메서드를 기준마다 두면 기준이 늘 때마다 메서드가 는다.
     *
     * 총 개수를 세는 쿼리는 나가지 않는다(설계 5.5). `size + 1`개를 읽어 넘치는 하나로 다음 조각의 존재를 정하고
     * 그 하나는 버린다. [PageSlice]가 약속한 그대로다.
     *
     * 브랜드를 fetch join으로 함께 읽는 까닭은 항목마다 브랜드 이름을 읽기 때문이다. 없으면 조각 크기만큼 조회가 붙는다.
     * `@ManyToOne(optional = false)`이라 inner join이고, [com.loopers.domain.brand.Brand]의 `@SQLRestriction`이
     * 그 조인에도 붙어 삭제된 브랜드의 상품은 목록에서 빠진다(설계 7).
     */
    override fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): PageSlice<Product> {
        val rows = queryFactory
            .selectFrom(product)
            .innerJoin(product.brand).fetchJoin()
            .where(brandId?.let { product.brand.id.eq(it) })
            .orderedBy(sort)
            .offset(page.toLong() * size)
            .limit(size + 1L)
            .fetch()
        return PageSlice(items = rows.take(size), page = page, size = size, hasNext = rows.size > size)
    }

    /** 차례는 쿼리가 적으므로 [PageRequest]에는 조각의 위치와 크기만 싣는다. 정렬을 함께 실으면 그 기준이 쿼리의 것을 덮는다. */
    override fun findAllLikedBy(userId: Long, page: Int, size: Int): PageSlice<Product> =
        productJpaRepository.findAllLikedBy(userId, PageRequest.of(page, size)).toPageSlice()

    override fun existsByBrandId(brandId: Long): Boolean = productJpaRepository.existsByBrandId(brandId)

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
     * 이런 매핑 지식은 infrastructure의 것이고 [ProductSort]는 무엇을 읽는지 모른다.
     */
    private fun JPAQuery<Product>.orderedBy(sort: ProductSort): JPAQuery<Product> = when (sort) {
        ProductSort.LATEST -> orderBy(product.createdAt.desc(), ID_DESC)
        ProductSort.PRICE_ASC -> orderBy(product.price.amount.asc(), ID_DESC)
        ProductSort.LIKES_DESC ->
            leftJoin(like).on(like.productId.eq(product.id))
                .groupBy(product, product.brand)
                .orderBy(like.count().desc(), ID_DESC)
    }

    /** 조각의 위치와 크기는 [PageRequest]가 정한 값 그대로이므로 Spring의 조각에서 읽는다. */
    private fun Slice<Product>.toPageSlice(): PageSlice<Product> =
        PageSlice(items = content, page = number, size = size, hasNext = hasNext())

    companion object {
        /** 나중에 받은 식별자가 앞선다. 모든 정렬 기준이 마지막에 쓰는 동률 규칙이다. */
        private val ID_DESC: OrderSpecifier<Long> = product.id.desc()
    }
}
