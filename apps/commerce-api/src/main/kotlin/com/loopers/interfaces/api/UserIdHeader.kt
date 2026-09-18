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
}
