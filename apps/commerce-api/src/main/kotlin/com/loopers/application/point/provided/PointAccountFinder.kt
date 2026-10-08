package com.loopers.application.point.provided

import com.loopers.domain.point.PointAccount

/**
 * 포인트 조각이 내주는 읽기. 고객의 잔액 조회가 부르고, 충전과 차감이 바꿀 계정을 얻는다.
 * 계정은 연관이 없는 애그리거트 하나라 엔티티를 그대로 돌려준다(ADR 0014).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface PointAccountFinder {
    /** 요청자의 계정. 없으면 `POINT_ACCOUNT_MISSING`을 던진다. 받아들인 요청자라면 데이터 불일치다(설계 5.9, 6 끝). */
    fun findByUser(userId: Long): PointAccount
}
