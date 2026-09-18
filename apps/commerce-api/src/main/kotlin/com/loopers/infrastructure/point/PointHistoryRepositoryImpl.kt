package com.loopers.infrastructure.point

import com.loopers.domain.point.PointHistory
import com.loopers.domain.point.PointHistoryRepository
import org.springframework.stereotype.Component

/**
 * [PointHistoryRepository]의 구현. 일은 모두 [PointHistoryJpaRepository]에 맡긴다.
 * 두 인터페이스를 하나로 합치지 않는 이유는 [com.loopers.infrastructure.brand.BrandRepositoryImpl]과 같다(카탈로그 설계 5.20).
 */
@Component
class PointHistoryRepositoryImpl(
    private val pointHistoryJpaRepository: PointHistoryJpaRepository,
) : PointHistoryRepository {
    override fun save(history: PointHistory): PointHistory = pointHistoryJpaRepository.save(history)

    override fun findByAccountIdAndChargeKey(accountId: Long, chargeKey: String): PointHistory? =
        pointHistoryJpaRepository.findByAccountIdAndChargeKey(accountId, chargeKey)
}
