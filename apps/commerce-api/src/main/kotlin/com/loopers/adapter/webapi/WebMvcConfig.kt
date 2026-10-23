package com.loopers.adapter.webapi

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 웹 경계의 Spring MVC 설정. [RequesterId] 파라미터를 [RequesterIdArgumentResolver]가, [IdempotencyKey] 파라미터를
 * [IdempotencyKeyArgumentResolver]가 채우게 등록한다.
 */
@Configuration
class WebMvcConfig(
    private val requesterIdArgumentResolver: RequesterIdArgumentResolver,
    private val idempotencyKeyArgumentResolver: IdempotencyKeyArgumentResolver,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(requesterIdArgumentResolver)
        resolvers.add(idempotencyKeyArgumentResolver)
    }
}
