package com.loopers.adapter.webapi

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 웹 경계의 Spring MVC 설정. [RequesterId] 파라미터를 [RequesterIdArgumentResolver]가 채우게 등록한다. */
@Configuration
class WebMvcConfig(
    private val requesterIdArgumentResolver: RequesterIdArgumentResolver,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(requesterIdArgumentResolver)
    }
}
