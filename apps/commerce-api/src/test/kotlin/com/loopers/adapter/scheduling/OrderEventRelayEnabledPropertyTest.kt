package com.loopers.adapter.scheduling

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.mock.env.MockEnvironment

/**
 * What the real `application.yml` says the property is under each profile, read the way the application reads it, without starting a
 * context. `@ConditionalOnProperty` wants the literal `true`, so anything else leaves the relay off.
 */
class OrderEventRelayEnabledPropertyTest {
    @Test
    fun `the relay is on under local and off under test and the deployed profiles`() {
        assertThat(relayEnabledUnder("local")).isEqualTo("true")
        assertThat(relayEnabledUnder("test")).isNotEqualTo("true")
        assertThat(relayEnabledUnder("dev")).isNotEqualTo("true")
        assertThat(relayEnabledUnder("qa")).isNotEqualTo("true")
        assertThat(relayEnabledUnder("prd")).isNotEqualTo("true")
    }

    private fun relayEnabledUnder(profile: String): String? {
        val environment = MockEnvironment()
            .withProperty("BOOTSTRAP_SERVERS", "unused:9092")
            .withProperty("spring.profiles.active", profile)

        ConfigDataEnvironmentPostProcessor.applyTo(environment)

        return environment.getProperty("order-events.relay.enabled")
    }
}
