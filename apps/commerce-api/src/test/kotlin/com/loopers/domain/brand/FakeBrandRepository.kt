package com.loopers.domain.brand

import com.loopers.domain.FakePersistence

/** [BrandRepository]의 메모리 구현. application 테스트에서 저장소 대신 쓴다. */
class FakeBrandRepository : BrandRepository {
    private val brands = mutableMapOf<Long, Brand>()
    private val persistence = FakePersistence()

    override fun save(brand: Brand): Brand {
        persistence.persist(brand)
        brands[brand.id] = brand
        return brand
    }

    override fun findLiveById(id: Long): Brand? = brands[id]?.takeIf { it.deletedAt == null }

    override fun existsLiveByName(name: String): Boolean = brands.values.any { it.deletedAt == null && it.name == name }
}
