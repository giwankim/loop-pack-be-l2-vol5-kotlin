package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.shared.Slice
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.data.domain.Slice as SpringSlice

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
     * 등록 시각이 같은 상품은 나중에 받은 식별자가 앞선다.
     */
    override fun findAll(brandId: Long?, page: Int, size: Int): Slice<Product> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
        val found = brandId
            ?.let { productJpaRepository.findAllByBrandId(it, pageable) }
            ?: productJpaRepository.findAllBy(pageable)
        return found.toSlice(page, size)
    }

    private fun SpringSlice<Product>.toSlice(page: Int, size: Int): Slice<Product> =
        Slice(items = content, page = page, size = size, hasNext = hasNext())
}
