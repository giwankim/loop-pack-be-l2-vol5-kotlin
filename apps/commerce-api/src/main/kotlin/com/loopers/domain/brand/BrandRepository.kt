package com.loopers.domain.brand

/**
 * 브랜드 저장 약속. "Live"가 붙은 조회는 삭제되지 않은 브랜드만 대상으로 한다(ADR 0001).
 */
interface BrandRepository {
    fun save(brand: Brand): Brand

    fun findLiveById(id: Long): Brand?

    fun existsLiveByName(name: String): Boolean
}
