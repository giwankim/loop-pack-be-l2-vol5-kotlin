package com.loopers.domain.brand

/**
 * 브랜드 저장 약속. 삭제된 브랜드는 없는 브랜드이므로 조회는 존재만 묻고, 삭제된 행은 없다고 답한다.
 */
interface BrandRepository {
    fun save(brand: Brand): Brand

    fun findById(id: Long): Brand?

    fun existsByName(name: String): Boolean
}
