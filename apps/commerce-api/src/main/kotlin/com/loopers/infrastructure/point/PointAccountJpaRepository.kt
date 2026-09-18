package com.loopers.infrastructure.point

import com.loopers.domain.point.PointAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** [PointAccount]의 Spring Data JPA 저장소. [PointAccountRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다. */
interface PointAccountJpaRepository : JpaRepository<PointAccount, Long> {
    /** `userId`는 엔티티의 파생 프로퍼티라 이름 규칙이 닿지 않으므로 연관을 건너는 조건을 직접 적는다. */
    @Query("select a from PointAccount a where a.user.id = :userId")
    fun findByUserId(userId: Long): PointAccount?
}
