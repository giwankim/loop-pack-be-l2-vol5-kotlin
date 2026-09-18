package com.loopers.domain.point

/**
 * 포인트 계정 저장 약속. 계정은 사용자마다 하나라 사용자로 찾는다. 없는 계정을 만들어 주지 않는다(설계 5.9).
 */
interface PointAccountRepository {
    fun save(account: PointAccount): PointAccount

    fun findByUserId(userId: Long): PointAccount?
}
