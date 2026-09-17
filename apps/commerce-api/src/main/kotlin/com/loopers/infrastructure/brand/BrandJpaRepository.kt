package com.loopers.infrastructure.brand

import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.Name
import org.springframework.data.jpa.repository.JpaRepository

/**
 * [Brand]의 Spring Data JPA 저장소. [BrandRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다.
 * 삭제된 행을 거르는 조건은 [Brand]의 `@SQLRestriction`이 모든 조회에 붙이므로 여기서는 적지 않는다.
 */
interface BrandJpaRepository : JpaRepository<Brand, Long> {
    fun existsByName(name: Name): Boolean
}
