package com.loopers.adapter.persistence.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.shared.PageSlice
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * [BrandRepository]의 구현. 일은 모두 [BrandJpaRepository]에 맡기고, Spring Data의 조각만 domain의 [PageSlice]로 옮긴다.
 * 두 인터페이스를 하나로 합치지 않는다(설계 5.20).
 */
@Component
class BrandRepositoryImpl(
    private val brandJpaRepository: BrandJpaRepository,
) : BrandRepository {
    override fun save(brand: Brand): Brand = brandJpaRepository.save(brand)

    override fun findById(id: Long): Brand? = brandJpaRepository.findById(id)

    override fun findAll(page: Int, size: Int): PageSlice<Brand> =
        brandJpaRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size))
            .let { PageSlice(items = it.content, page = page, size = size, hasNext = it.hasNext()) }

    override fun existsByName(name: String): Boolean = brandJpaRepository.existsByName(name)

    override fun existsByNameAndIdNot(name: String, id: Long): Boolean =
        brandJpaRepository.existsByNameAndIdNot(name, id)
}
