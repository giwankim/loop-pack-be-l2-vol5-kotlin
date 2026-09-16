package com.loopers.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain

/**
 * 과제가 제공하는 관리자 경계 테스트 지원 설정.
 *
 * 관리자 API 경로(`/api-admin/` 아래 전체)에만 적용되며 ADMIN 역할을 요구하고, 인증이 없으면 403으로 거절한다.
 * 로컬 실습과 MockMvc 테스트(`user().roles("ADMIN")`)를 위한 것이며 운영 인증 수단이 아니다.
 * 고객 API(`/api/` 아래)는 이 체인 밖에 있다.
 */
@Configuration
class AdminBoundaryConfig {
    @Bean
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/api-admin/**")
            authorizeHttpRequests {
                authorize(anyRequest, hasRole("ADMIN"))
            }
            exceptionHandling {
                authenticationEntryPoint = AuthenticationEntryPoint { _, response, _ ->
                    response.sendError(HttpStatus.FORBIDDEN.value())
                }
            }
        }
        return http.build()
    }
}
