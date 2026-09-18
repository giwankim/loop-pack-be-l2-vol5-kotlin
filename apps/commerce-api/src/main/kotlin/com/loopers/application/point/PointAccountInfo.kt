package com.loopers.application.point

import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointHistory

/**
 * 포인트 계정 응답 모델. 요청자 자신의 잔액만 보이므로 사용자 식별자는 싣지 않는다.
 * 충전 응답의 [balance]는 그 충전 직후의 잔액이고, 조회 응답의 [balance]는 현재 잔액이다(설계 6).
 */
data class PointAccountInfo(
    val balance: Long,
) {
    companion object {
        fun from(account: PointAccount): PointAccountInfo = PointAccountInfo(balance = account.balance.amount)

        /** 성공한 충전의 첫 응답을 그 이력에서 다시 만든다. 뒤에 잔액이 바뀌어도 이 값은 그대로다(ADR 0004). */
        fun replayOf(history: PointHistory): PointAccountInfo = PointAccountInfo(balance = history.balanceAfter.amount)
    }
}
