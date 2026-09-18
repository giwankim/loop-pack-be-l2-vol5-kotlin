package com.loopers.infrastructure.point

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import org.springframework.stereotype.Component

/**
 * [PointAccountRepository]의 구현. 일은 모두 [PointAccountJpaRepository]에 맡긴다.
 * 두 인터페이스를 하나로 합치지 않는 이유는 [com.loopers.infrastructure.brand.BrandRepositoryImpl]과 같다(카탈로그 설계 5.20).
 */
@Component
class PointAccountRepositoryImpl(
    private val pointAccountJpaRepository: PointAccountJpaRepository,
) : PointAccountRepository {
    override fun save(account: PointAccount): PointAccount = pointAccountJpaRepository.save(account)

    override fun findByUserId(userId: Long): PointAccount? = pointAccountJpaRepository.findByUserId(userId)
}
