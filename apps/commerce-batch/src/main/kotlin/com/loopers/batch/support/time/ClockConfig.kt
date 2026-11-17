package com.loopers.batch.support.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The clock batch code reads the current instant from. A test that decides the instant puts its own beside it as the primary `Clock`. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock {
        return Clock.systemUTC()
    }
}
