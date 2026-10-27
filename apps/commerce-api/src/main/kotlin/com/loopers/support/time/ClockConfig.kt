package com.loopers.support.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The clock application code reads the current instant from (ADR 0032 rule 8). A test that decides the instant puts a clock
 * of its own beside this one as the primary `Clock`.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock {
        return Clock.systemUTC()
    }
}
