package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * [BrandRepository]의 Spring Data JPA 구현. [save]는 [JpaRepository]에서 그대로 물려받고,
 * 파생 쿼리 이름으로 만들 수 없는 "Live" 조회만 JPQL로 적는다.
 */
interface BrandJpaRepository : JpaRepository<Brand, Long>, BrandRepository {
    @Query("select b from Brand b where b.id = :id and b.deletedAt is null")
    override fun findLiveById(id: Long): Brand?

    @Query("select count(b) > 0 from Brand b where b.name = :name and b.deletedAt is null")
    override fun existsLiveByName(name: String): Boolean
}
