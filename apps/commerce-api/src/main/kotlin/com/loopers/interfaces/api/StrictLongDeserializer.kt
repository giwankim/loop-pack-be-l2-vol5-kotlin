package com.loopers.interfaces.api

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.exc.InputCoercionException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 정수 표기의 JSON 숫자만 `Long`으로 받는다. `1000`은 받고 `"1000"`, `1000.0`, `1e3`, `true`, 배열·객체는 거절한다.
 * 정수라도 `Long` 범위를 넘으면 거절한다(설계 5.10).
 *
 * 전역 ObjectMapper는 숫자 문자열과 소수 표기를 정수로 바꿔 주고 카탈로그는 그 계약을 그대로 쓴다. 이 정책은
 * 포인트·주문의 HTTP 입력 DTO가 필드에 `@JsonDeserialize`로 붙여 그 경계에서만 적용한다.
 *
 * 거절은 [CoreException]으로 던진다. Jackson이 매핑 예외로 감싸고 Spring이 다시 감싸지만
 * [ApiControllerAdvice]가 근본 원인을 찾아 그 [ErrorType]으로 답한다. null 토큰은 여기로 오지 않고 null이 되며,
 * 빠진 값과 함께 DTO가 거절한다.
 */
class StrictLongDeserializer : JsonDeserializer<Long>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Long {
        if (p.currentToken != JsonToken.VALUE_NUMBER_INT) {
            throw CoreException(ErrorType.INVALID_POINT_ORDER_REQUEST)
        }
        return try {
            p.longValue
        } catch (e: InputCoercionException) {
            throw CoreException(ErrorType.INVALID_POINT_ORDER_REQUEST)
        }
    }
}
