package com.loopers.application.point.provided

import com.loopers.domain.point.PointAccount
import jakarta.validation.Valid

/**
 * 고객의 포인트 충전. 잔액만 바꾸며 충전의 기록은 따로 남기지 않는다(ADR 0006).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface PointCharger {
    /**
     * 충전하고 충전 직후의 계정을 돌려준다. 요청마다 새 충전이다. 같은 충전액을 다시 보내면 다시 충전된다(ADR 0005).
     * 계정이 없으면 `POINT_ACCOUNT_MISSING`을 던진다. 받아들인 요청자라면 데이터 불일치다(설계 5.9, 6 끝).
     */
    fun charge(userId: Long, @Valid request: PointChargeRequest): PointAccount
}
