package com.loopers.support

import org.hibernate.cfg.AvailableSettings
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * [SqlCaptureInspector]를 Hibernate의 `StatementInspector`로 설치한다. [captureSql] 밖에서는 아무것도 적지 않고 SQL도 바꾸지 않는다.
 *
 * `BaseApplicationServiceTest`가 Testcontainers 설정 곁에서 가져온다. 모든 하위 클래스의 `@Import`가 같으므로 컨텍스트가 늘지 않는다(ADR 0012).
 * 같은 컨텍스트를 나눠 쓰는 `CommerceApiContextTest`도 같은 설정을 가져온다. 빠지면 캐시 키가 갈려 컨텍스트가 하나 더 뜬다.
 */
@TestConfiguration(proxyBeanMethods = false)
class SqlCaptureConfig {
    @Bean
    fun sqlCaptureHibernateProperties(): HibernatePropertiesCustomizer {
        return HibernatePropertiesCustomizer { properties ->
            properties[AvailableSettings.STATEMENT_INSPECTOR] = SqlCaptureInspector()
        }
    }
}
