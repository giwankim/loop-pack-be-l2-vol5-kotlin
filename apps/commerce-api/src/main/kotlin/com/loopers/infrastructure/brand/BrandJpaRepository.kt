package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * [BrandRepository]의 Spring Data JPA 구현. [save]는 [JpaRepository]에서 그대로 물려받는다.
 * 삭제된 행을 거르는 조건은 [Brand]의 `@SQLRestriction`이 모든 쿼리에 붙이므로 여기서는 적지 않는다.
 * [find]는 이름에 `By`가 없어 파생 쿼리로 만들 수 없으므로 JPQL로 적는다.
 */
interface BrandJpaRepository : JpaRepository<Brand, Long>, BrandRepository {
    @Query("select b from Brand b where b.id = :id")
    override fun find(id: Long): Brand?

    override fun existsByName(name: String): Boolean
}
