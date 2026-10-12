package com.loopers.application.point.provided

import com.loopers.domain.point.PointAccount

/**
 * 포인트 조각이 내주는 읽기. 고객의 잔액 조회가 [findByUser]로 부르고, 충전과 차감은 [findForUpdate]로 바꿀 계정을 잠가 얻는다.
 * 계정은 연관이 없는 애그리거트 하나라 엔티티를 그대로 돌려준다(ADR 0014).
 *
 * [userId]는 요청자, 곧 `X-USER-ID` 헤더가 실어 준 사용자 식별자다. 웹 경계가 이미 받아들인 요청자라 다시 확인하지 않는다(ADR 0015).
 */
interface PointAccountFinder {
    /** 요청자의 계정. 없으면 `POINT_ACCOUNT_MISSING`을 던진다. 받아들인 요청자라면 데이터 불일치다(설계 5.9, 6 끝). 잠그지 않는다. */
    fun findByUser(userId: Long): PointAccount

    /**
     * 요청자의 계정을 잠가 읽는다. 없으면 [findByUser]처럼 `POINT_ACCOUNT_MISSING`을 던진다.
     * 충전과 확정의 결제가 계정을 처음 읽을 때 부른다. 같은 계정을 바꾸는 쪽은 이 잠금에서 줄을 서고,
     * 기다린 쪽은 앞선 쪽이 커밋한 잔액을 읽으므로 그 변경을 덮어쓰지 않는다(ADR 0019).
     *
     * 호출자의 쓰기 트랜잭션 안에서만 부른다. 잠금은 그 트랜잭션이 끝날 때 풀린다.
     */
    fun findForUpdate(userId: Long): PointAccount
}
