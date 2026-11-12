package com.loopers.adapter.persistence.coupon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

/**
 * How the slot capacity and the instance name bind at startup. It needs no Redis, so it starts only the properties binding, the way the application's
 * `@ConfigurationPropertiesScan` registers it.
 *
 * The `HOSTNAME` scenes read the real `application.yml` under `prd` instead, in a `MockEnvironment`, which carries no OS environment
 * variable unless the scene gives it one.
 */
class CouponIssuePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfig::class.java)
        .withPropertyValues("coupon-issue.instance-id=commerce-api-1")

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

    @Test
    fun `an instance name starts the context with that name`() {
        contextRunner.withPropertyValues("coupon-issue.slot-capacity=1", "coupon-issue.instance-id=api-7f9c").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(CouponIssueProperties::class.java).instanceId).isEqualTo("api-7f9c")
        }
    }

    @Test
    fun `a blank instance name fails the context at startup`() {
        contextRunner.withPropertyValues("coupon-issue.slot-capacity=1", "coupon-issue.instance-id= ").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("coupon-issue.instance-id must not be blank")
        }
    }

    @Test
    fun `an instance name containing a colon fails the context at startup`() {
        contextRunner.withPropertyValues("coupon-issue.slot-capacity=1", "coupon-issue.instance-id=api:1").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("coupon-issue.instance-id must not contain ':'")
        }
    }

    @Test
    fun `a deployed profile names the instance after HOSTNAME`() {
        val properties = bindUnderPrd(MockEnvironment().withProperty("HOSTNAME", "api-7f9c"))

        assertThat(properties.instanceId).isEqualTo("api-7f9c")
    }

    @Test
    fun `a deployed profile without HOSTNAME fails to bind the instance name`() {
        val environment = MockEnvironment()

        assertThatThrownBy { bindUnderPrd(environment) }
            .rootCause()
            .hasMessageContaining("coupon-issue.instance-id must not be blank")
    }

    private fun bindUnderPrd(environment: MockEnvironment): CouponIssueProperties {
        environment.setProperty("spring.profiles.active", "prd")
        ConfigDataEnvironmentPostProcessor.applyTo(environment)

        return Binder.get(environment).bindOrCreate("coupon-issue", CouponIssueProperties::class.java)
    }

    @EnableConfigurationProperties(CouponIssueProperties::class)
    class PropertiesConfig
}
