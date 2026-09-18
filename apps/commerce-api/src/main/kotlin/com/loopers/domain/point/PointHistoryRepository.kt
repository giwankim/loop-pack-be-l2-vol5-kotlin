package com.loopers.domain.point

/**
 * 포인트 이력 저장 약속. 이력은 남긴 뒤 바꾸지 않으므로 저장과 충전 키 조회뿐이다.
 * 충전 키 조회는 대소문자를 구분한다. 그 비교는 열의 collation이 정한다(설계 12.2).
 */
interface PointHistoryRepository {
    fun save(history: PointHistory): PointHistory

    /** 한 계정의 성공한 충전을 충전 키로 찾는다. 재요청이 첫 결과를 다시 만드는 자리다(ADR 0004). */
    fun findByAccountIdAndChargeKey(accountId: Long, chargeKey: String): PointHistory?
}
