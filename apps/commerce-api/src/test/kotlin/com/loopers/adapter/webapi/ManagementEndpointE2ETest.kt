package com.loopers.adapter.webapi

import com.loopers.support.test.BaseRealPortWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The management server's endpoints as a scraper sees them: real HTTP to the injected management port (spec 266, stories 25
 * and 26). `monitoring.yml` exposes health and prometheus only.
 */
class ManagementEndpointE2ETest : BaseRealPortWebApiAdapterTest() {
    @Test
    fun `health is UP and the Prometheus text holds metrics that already exist`() {
        val health = managementGet("/actuator/health")
        val prometheus = managementGet("/actuator/prometheus")

        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")
        assertThat(prometheus.statusCode()).isEqualTo(200)
        assertThat(prometheus.body())
            .contains("jvm_memory_used_bytes{")
            .contains("resilience4j_circuitbreaker_state{")
    }

    @Test
    fun `env and heapdump are not served on the management port`() {
        val env = managementGet("/actuator/env")
        val heapdump = managementGet("/actuator/heapdump")

        assertThat(env.statusCode()).isEqualTo(404)
        assertThat(heapdump.statusCode()).isEqualTo(404)
    }
}
