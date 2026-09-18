package com.loopers.infrastructure.point

import com.loopers.domain.point.PointHistory
import org.springframework.data.jpa.repository.JpaRepository

/** [PointHistory]의 Spring Data JPA 저장소. [PointHistoryRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다. */
interface PointHistoryJpaRepository : JpaRepository<PointHistory, Long> {
    /** 이름 규칙이 `account.id`로 건넌다. 키 비교의 대소문자 구분은 열의 collation이 정한다. */
    fun findByAccountIdAndChargeKey(accountId: Long, chargeKey: String): PointHistory?
}
