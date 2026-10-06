package com.loopers.application.point.provided

/**
 * 포인트 조각이 내주는 읽기. 고객의 잔액 조회가 부른다.
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 그 사용자가 없으면 `UNAUTHORIZED`를 던진다(카탈로그 설계 5.27).
 */
interface PointAccountFinder {
    /** 요청자의 현재 잔액. 사용자는 있는데 계정이 없으면 `POINT_ACCOUNT_MISSING`을 던진다(설계 5.9, 6 끝). */
    fun findBalance(userId: Long): PointAccountInfo
}
