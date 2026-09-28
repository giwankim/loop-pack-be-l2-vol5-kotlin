package com.loopers.infrastructure.point

import com.loopers.domain.point.PointHistory
import org.springframework.data.repository.Repository

/** [PointHistory]의 Spring Data JPA 저장소. [PointHistoryRepositoryImpl]이 이것에 맡겨 domain의 저장 약속을 지킨다. */
interface PointHistoryJpaRepository : Repository<PointHistory, Long> {
    fun save(history: PointHistory): PointHistory
}
