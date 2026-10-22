package com.loopers.support

import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.springframework.context.annotation.Import
import org.wiremock.spring.ConfigureWireMock
import org.wiremock.spring.EnableWireMock
import org.wiremock.spring.WireMockConfigurationCustomizer

/**
 * 테스트 PG를 테스트 JVM 안에 띄운다(ADR 0022, wiremock-spring-boot 탐침 보고서). 별도의 PG 앱은 없다.
 *
 * wiremock-spring-boot가 Spring 테스트 컨텍스트마다 WireMock 서버 하나를 띄우고 그 주소를 `pg.base-url`에 넣어, 어댑터가 그리로 보낸다.
 * 서버는 컨텍스트와 함께 살고 죽는다. PG를 쓰는 테스트는 모두 이 애노테이션을 달아, 같은 설정끼리 컨텍스트와 서버를 나눠 쓴다.
 * 스텁과 요청 기록은 테스트 메서드마다 지워진다(라이브러리 기본값). 서버는 테스트 클래스의 `@InjectWireMock(TestPg.NAME)` 필드로 받아
 * [TestPg]로 감싼다.
 *
 * 생성 스텁이 PG 주문 번호까지 맞추도록 [TestPgOrderIdGeneratorConfig]도 가져와, 결제가 테스트가 정해 둔 번호를 받게 한다(ADR 0029 규칙 4).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(TestPgOrderIdGeneratorConfig::class)
@EnableWireMock(
    ConfigureWireMock(
        name = TestPg.NAME,
        baseUrlProperties = ["pg.base-url"],
        configurationCustomizers = [TestPgConfigurationCustomizer::class],
    ),
)
annotation class EnableTestPg

/**
 * 테스트 PG를 127.0.0.1에만 묶고 h2c를 끈다. WireMock의 기본값은 `0.0.0.0`에 묶고 h2c를 알린다(탐침 보고서 §7의 5).
 * 어댑터의 HttpClient는 HTTP/1.1만 쓴다. 라이브러리가 이름 없는 생성자로 만들므로 이 클래스는 공개 클래스다.
 */
class TestPgConfigurationCustomizer : WireMockConfigurationCustomizer {
    override fun customize(configuration: WireMockConfiguration, options: ConfigureWireMock) {
        configuration.bindAddress("127.0.0.1").http2PlainDisabled(true)
    }
}
