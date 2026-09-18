package com.loopers.interfaces.api

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 요청자 식별. API 게이트웨이가 `X-USER-ID` 헤더에 실어 준 사용자 식별자를 읽는다(CONTEXT.md 요청자).
 *
 * 헤더가 없으면 요청자가 없는 것이므로 401이다. 헤더의 존재는 HTTP만 아는 사실이라 여기서 거절하고,
 * 그 식별자가 가리키는 사용자가 있는지는 application이 본다. Controller를 거치지 않는 호출도 그 검사는 받아야 하기 때문이다(설계 5.27).
 *
 * 컨트롤러는 헤더를 `required = false`로 받아 [require]에 넘긴다. Spring이 빠진 헤더를 스스로 거절하게 두면
 * 그 예외는 이 헤더만의 것이 아니라 401로 옮길 자리가 없다.
 */
object UserIdHeader {
    const val NAME = "X-USER-ID"

    fun require(userId: Long?): Long = userId ?: throw CoreException(ErrorType.UNAUTHORIZED)

    /**
     * 요청자를 읽고, 경로가 가리키는 사용자가 요청자 자신인지 본다. 다르면 403이다.
     * 요청자는 자기 좋아요·포인트·주문만 다룰 수 있다(CONTEXT.md 요청자).
     *
     * 경로의 사용자와 헤더의 요청자는 둘 다 HTTP가 실어 준 값이라 그 비교도 여기서 한다. application에는
     * 요청자 하나만 넘어가므로 남의 것을 다룰 길이 애초에 없다(설계 5.30).
     *
     * 헤더가 없는 것은 견주어 볼 요청자가 없는 것이므로 경로와 무관하게 401이다.
     */
    fun requireSelf(userId: Long?, pathUserId: Long): Long {
        val requesterId = require(userId)
        if (requesterId != pathUserId) {
            throw CoreException(ErrorType.FORBIDDEN)
        }
        return requesterId
    }
}
