package com.loopers.adapter.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.mock.env.MockEnvironment

/**
 * What the real `application.yml` says the gauge's properties are under each profile, read the way the application reads them,
 * without starting a context. The gauge no longer reads them (spec 281): they belong to the scheduled count and go with it.
 */
class UnresolvedPaymentGaugeEnabledPropertyTest {
    @Test
    fun `the gauge is on under local and the deployed profiles and off under test`() {
        assertThat(propertyUnder("local", "payment.unresolved-gauge.enabled")).isEqualTo("true")
        assertThat(propertyUnder("dev", "payment.unresolved-gauge.enabled")).isEqualTo("true")
        assertThat(propertyUnder("qa", "payment.unresolved-gauge.enabled")).isEqualTo("true")
        assertThat(propertyUnder("prd", "payment.unresolved-gauge.enabled")).isEqualTo("true")
        assertThat(propertyUnder("test", "payment.unresolved-gauge.enabled")).isEqualTo("false")
    }

    @Test
    fun `the gauge refreshes every 10 seconds under every deployed profile`() {
        assertThat(propertyUnder("dev", "payment.unresolved-gauge.refresh-interval")).isEqualTo("10s")
        assertThat(propertyUnder("qa", "payment.unresolved-gauge.refresh-interval")).isEqualTo("10s")
        assertThat(propertyUnder("prd", "payment.unresolved-gauge.refresh-interval")).isEqualTo("10s")
    }

    private fun propertyUnder(profile: String, name: String): String? {
        val environment = MockEnvironment()
            .withProperty("BOOTSTRAP_SERVERS", "unused:9092")
            .withProperty("spring.profiles.active", profile)

        ConfigDataEnvironmentPostProcessor.applyTo(environment)

        return environment.getProperty(name)
    }
}
