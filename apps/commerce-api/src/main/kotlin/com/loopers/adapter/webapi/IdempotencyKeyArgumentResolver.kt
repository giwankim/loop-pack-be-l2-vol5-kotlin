package com.loopers.adapter.webapi

import com.loopers.domain.payment.Payment
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * [IdempotencyKey]가 달린 파라미터에 `Idempotency-Key` 헤더의 키를 넣는다. 웹 경계와 결제의 생성이 같은 키 규칙을 검사한다(ADR 0025 규칙 2).
 *
 * - 헤더가 없으면 `@RequestHeader`처럼 [MissingRequestHeaderException]을 던져 400이다.
 * - 1자에서 64자까지의 공백 없는 보이는 ASCII가 아니면 [Payment.checkIdempotencyKey]가 어긴 규칙을 말하는 400을 던진다.
 *   규칙은 결제 하나가 선언하고 여기서는 그것을 부른다. 웹 경계에서 먼저 거절하므로 잘못된 키는 재고를 예약하거나 결제를 찾기 전에 돌아간다.
 */
@Component
class IdempotencyKeyArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return parameter.hasParameterAnnotation(IdempotencyKey::class.java)
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): String {
        val idempotencyKey = webRequest.getHeader(IdempotencyKeyHeader.NAME)
            ?: throw MissingRequestHeaderException(IdempotencyKeyHeader.NAME, parameter)

        Payment.checkIdempotencyKey(idempotencyKey)

        return idempotencyKey
    }
}
