package com.loopers.config.security

import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain

/**
 * 관리자 경계를 흉내 내는 통합 테스트 전용 설정(과제 원문의 이름은 `AdminBoundaryConfig`, 원문은 main에 둔다).
 *
 * 관리자 API 경로(`/api-admin/` 아래 전체)에만 적용되며 ADMIN 역할을 요구하고, 인증이 없으면 403으로 거절한다.
 * MockMvc 테스트(`user().roles("ADMIN")`)를 위한 것이며 운영 인증 수단이 아니다. 운영 코드에는 Spring Security가 없다.
 * 고객 API(`/api/` 아래)는 이 체인 밖에 있다.
 *
 * 최상위 `@TestConfiguration`은 컴포넌트 스캔에서 빠지므로, 관리자 API를 부르는 테스트가 `@Import`로 명시해서 쓴다.
 * Spring Security가 테스트 클래스패스에 있으므로, 이 빈이 없는 컨텍스트에는 Boot 기본 체인(모든 경로에 인증 요구)이 들어간다.
 */
@TestConfiguration
class AdminSecurityConfig {
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
