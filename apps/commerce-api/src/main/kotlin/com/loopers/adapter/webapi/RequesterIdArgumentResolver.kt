package com.loopers.adapter.webapi

import com.loopers.application.user.provided.UserFinder
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * [RequesterId]가 달린 파라미터에 요청자의 사용자 식별자를 넣는다. 로컬에서 API 게이트웨이를 대신하는 웹 경계다(ADR 0015).
 * 컨트롤러가 돌기 전에 없는 요청자를 거절하므로 application은 받은 `userId`를 믿는다.
 *
 * - 헤더가 없거나 비었으면 요청자가 없는 것이므로 401이다.
 * - 숫자가 아니면 요청자가 없는 것이 아니라 요청이 잘못된 것이므로 400이다. `@RequestHeader`의 타입 변환이 던지던
 *   [MethodArgumentTypeMismatchException]을 그대로 던져 응답도 그때와 같다.
 * - 그 식별자의 사용자가 없으면 401이다. 사용자가 있는지는 사용자 조각의 [UserFinder]에 묻는다.
 *
 * 경로가 가리키는 사용자와 요청자를 견주는 일(403)은 하지 않는다. 경로를 품는 컨트롤러가 받아들인 요청자로 한다(설계 5.30).
 * 인증이 인가보다 앞서므로 없는 사용자는 경로와 무관하게 401이다.
 */
@Component
class RequesterIdArgumentResolver(
    private val userFinder: UserFinder,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return parameter.hasParameterAnnotation(RequesterId::class.java)
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long {
        val header = webRequest.getHeader(UserIdHeader.NAME)
        if (header.isNullOrEmpty()) {
            throw CoreException(ErrorType.UNAUTHORIZED)
        }

        val userId = header.toLongOrNull()
            ?: throw MethodArgumentTypeMismatchException(header, Long::class.javaObjectType, UserIdHeader.NAME, parameter, null)

        if (!userFinder.exists(userId)) {
            throw CoreException(ErrorType.UNAUTHORIZED)
        }

        return userId
    }
}
