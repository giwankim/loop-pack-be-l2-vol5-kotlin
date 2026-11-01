package com.loopers.support

import com.loopers.adapter.integration.pg.LocalPgGateway
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * PG 조회의 서킷 브레이커 `pg-lookup`에 테스트 시계를 넣는다(ADR 0030 규칙 8). `@EnableTestPg`가 가져오므로 PG를 쓰는 테스트의 컨텍스트
 * 설정이 하나로 남고, 컨텍스트 캐시가 갈라지지 않는다.
 *
 * Resilience4j의 Boot 모듈은 `resilience4j.circuitbreaker.*` 설정으로 브레이커를 만들 때 이 customizer를 얹는다. 설정에는 시계를 적을 자리가
 * 없다. 테스트는 [TestClock]을 받아 옮겨 OPEN의 60 s를 잠 없이 넘는다. 옮긴 시계를 테스트마다 되돌리는 것은 테스트 기반 클래스가 맡는다.
 * Resilience4j의 자동 설정을 들이지 않는 게이트웨이 테스트의 컨텍스트에서는 이 빈들을 읽는 곳이 없어 아무것도 하지 않는다.
 *
 * The same [TestClock] is the context's primary `Clock`, so the order's confirmation time comes from it too (ADR 0032 rule 8).
 * One clock drives both: a test that moves it for the breaker also moves the instant an order is confirmed at. The main
 * configuration's system clock stays in the context as a second `Clock` bean, and `@Primary` makes every `Clock` injection
 * take this one instead.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestPgLookupBreakerConfig {
    @Bean
    @Primary
    fun testClock(): TestClock {
        return TestClock()
    }

    @Bean
    fun pgLookupClockCustomizer(testClock: TestClock): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer.of(LocalPgGateway.PG_LOOKUP) { it.clock(testClock) }
    }
}
