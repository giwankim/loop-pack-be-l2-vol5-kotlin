package com.loopers.adapter.persistence.coupon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * How the slot capacity binds at startup. It needs no Redis, so it starts only the properties binding, the way the application's
 * `@ConfigurationPropertiesScan` registers it.
 */
class CouponIssuePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)

    @Test
    fun `a capacity of 1 starts the context with that capacity`() {
        contextRunner.withPropertyValues("coupon-issue.slot-capacity=1").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(CouponIssueProperties::class.java).slotCapacity).isEqualTo(1)
        }
    }

    @Test
    fun `a capacity of 0 fails the context at startup`() {
        contextRunner.withPropertyValues("coupon-issue.slot-capacity=0").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("coupon-issue.slot-capacity must be 1 or more")
        }
    }

    @Test
    fun `a negative capacity fails the context at startup`() {
        contextRunner.withPropertyValues("coupon-issue.slot-capacity=-1").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("coupon-issue.slot-capacity must be 1 or more")
        }
    }

    @EnableConfigurationProperties(CouponIssueProperties::class)
    class PropertiesConfig
}
