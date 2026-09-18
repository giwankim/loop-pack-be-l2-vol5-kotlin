package com.loopers.interfaces.api

import com.loopers.domain.shared.IdempotencyKey
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 충전·주문 생성의 `Idempotency-Key` 헤더. 같은 사용자의 같은 작업에서 요청을 구별하는 키다(설계 5.8).
 *
 * 헤더가 없거나 형식을 어기면 400 `INVALID_IDEMPOTENCY_KEY`다. 헤더의 존재는 HTTP만 아는 사실이라 여기서 거절하고,
 * 형식은 [IdempotencyKey] 하나이며 application의 Request 제약이 한 번 더 본다(카탈로그 설계 5.25). 컨트롤러가 `required = false`로 받아 [require]에
 * 넘기는 까닭은 [UserIdHeader]와 같다. Spring이 스스로 거절하게 두면 범용 400이라 이 헤더의 code를 실을 자리가 없다.
 *
 * 값은 그대로 쓴다. 공백을 떼거나 대소문자를 바꾸지 않는다. `Charge-A`와 `charge-a`는 다른 키다.
 */
object IdempotencyKeyHeader {
    const val NAME = "Idempotency-Key"

    private val PATTERN = Regex(IdempotencyKey.PATTERN)

    fun require(key: String?): String {
        if (key == null || !PATTERN.matches(key)) {
            throw CoreException(ErrorType.INVALID_IDEMPOTENCY_KEY)
        }
        return key
    }
}
