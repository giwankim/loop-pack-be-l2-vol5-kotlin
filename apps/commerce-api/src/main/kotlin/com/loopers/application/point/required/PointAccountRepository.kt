package com.loopers.application.point.required

import com.loopers.domain.point.PointAccount
import org.springframework.data.repository.Repository

/**
 * 포인트 계정 저장소. Spring Data가 구현을 만든다. 계정은 사용자마다 하나라 사용자로 찾는다.
 * 없는 계정을 만들어 주지 않는다(설계 5.9).
 */
interface PointAccountRepository : Repository<PointAccount, Long> {
    fun save(account: PointAccount): PointAccount

    fun findByUserId(userId: Long): PointAccount?
}
