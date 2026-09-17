package com.loopers.domain.brand

import com.loopers.domain.shared.Slice

/**
 * 브랜드 저장 약속. 삭제된 브랜드는 없는 브랜드이므로 조회는 존재만 묻고, 삭제된 행은 없다고 답한다.
 */
interface BrandRepository {
    fun save(brand: Brand): Brand

    fun findById(id: Long): Brand?

    /** 삭제되지 않은 브랜드를 최신 등록순(등록 시각 내림차순, 동률은 id 내림차순)으로 한 조각 읽는다. */
    fun findAll(page: Int, size: Int): Slice<Brand>

    fun existsByName(name: String): Boolean

    /** [id]가 아닌 다른 삭제되지 않은 브랜드가 [name]을 쓰고 있는지. 이름 수정이 자기 이름과 겹치는 것을 중복으로 보지 않게 한다. */
    fun existsByNameAndIdNot(name: String, id: Long): Boolean
}
