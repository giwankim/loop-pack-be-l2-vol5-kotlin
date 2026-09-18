package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import com.loopers.domain.shared.PageSlice
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * [ProductRepository]의 구현. 일은 모두 [ProductJpaRepository]에 맡기고, `findById`의 `Optional`만 nullable로 바꾼다.
 * 두 인터페이스를 하나로 합치지 않는 이유는 [com.loopers.infrastructure.brand.BrandRepositoryImpl]과 같다.
 */
@Component
class ProductRepositoryImpl(
    private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
    override fun save(product: Product): Product = productJpaRepository.save(product)

    override fun findById(id: Long): Product? = productJpaRepository.findByIdOrNull(id)

    /**
     * Spring Data의 `Slice`가 `size + 1`개를 읽어 다음 조각의 존재를 정한다(설계 5.5). 총 개수를 세는 쿼리는 나가지 않는다.
     * 정렬 기준은 [Sort]로 옮겨 `Pageable`에 실으므로 기준이 늘어도 조회 메서드는 늘지 않는다.
     */
    override fun findAll(brandId: Long?, page: Int, size: Int, sort: ProductSort): PageSlice<Product> {
        val pageable = PageRequest.of(page, size, sort.toSort())
        val found = brandId
            ?.let { productJpaRepository.findAllByBrandId(it, pageable) }
            ?: productJpaRepository.findAllBy(pageable)
        return found.toPageSlice()
    }

    /**
     * 정렬 기준을 실제로 읽을 프로퍼티로 옮긴다. 어느 기준이든 마지막은 id 내림차순이라 동률이 남지 않는다.
     *
     * 가격은 `Money`가 `@Embeddable`이므로 프로퍼티 경로가 `price`가 아니라 `price.amount`다.
     * 이런 매핑 지식은 infrastructure의 것이고 [ProductSort]는 컬럼을 모른다.
     */
    private fun ProductSort.toSort(): Sort = when (this) {
        ProductSort.LATEST -> Sort.by(Sort.Order.desc("createdAt"), ID_DESC)
        ProductSort.PRICE_ASC -> Sort.by(Sort.Order.asc("price.amount"), ID_DESC)
    }

    /** 조각의 위치와 크기는 [PageRequest]가 정한 값 그대로이므로 Spring의 조각에서 읽는다. */
    private fun Slice<Product>.toPageSlice(): PageSlice<Product> =
        PageSlice(items = content, page = number, size = size, hasNext = hasNext())

    companion object {
        /** 나중에 받은 식별자가 앞선다. 모든 정렬 기준이 마지막에 쓰는 동률 규칙이다. */
        private val ID_DESC = Sort.Order.desc("id")
    }
}
