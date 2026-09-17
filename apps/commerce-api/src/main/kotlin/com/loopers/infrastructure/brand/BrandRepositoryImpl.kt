package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * [BrandRepository]의 구현. 일은 모두 [BrandJpaRepository]에 맡기고, `findById`의 `Optional`만 nullable로 바꾼다.
 *
 * [BrandJpaRepository]가 [BrandRepository]를 직접 상속하지 않는 이유: `JpaRepository`와 [BrandRepository]가
 * 둘 다 `findById(Long)`를 선언하는데 반환 타입이 `Optional<Brand>`와 `Brand?`로 달라 한 인터페이스가 둘을 함께 물려받을 수 없다.
 */
@Component
class BrandRepositoryImpl(
    private val brandJpaRepository: BrandJpaRepository,
) : BrandRepository {
    override fun save(brand: Brand): Brand = brandJpaRepository.save(brand)

    override fun findById(id: Long): Brand? = brandJpaRepository.findByIdOrNull(id)

    override fun existsByName(name: String): Boolean = brandJpaRepository.existsByName(name)
}
