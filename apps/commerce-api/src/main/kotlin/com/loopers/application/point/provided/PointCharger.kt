package com.loopers.application.point.provided

import jakarta.validation.Valid

/**
 * 고객의 포인트 충전. 잔액만 바꾸며 충전의 기록은 따로 남기지 않는다(ADR 0006).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 그 사용자가 없으면 `UNAUTHORIZED`를 던진다(카탈로그 설계 5.27).
 */
interface PointCharger {
    /**
     * 충전하고 충전 직후의 잔액을 돌려준다. 요청마다 새 충전이다. 같은 충전액을 다시 보내면 다시 충전된다(ADR 0005).
     * 사용자는 있는데 계정이 없으면 `POINT_ACCOUNT_MISSING`을 던진다(설계 5.9, 6 끝).
     */
    fun charge(userId: Long, @Valid request: PointChargeRequest): PointAccountInfo
}
