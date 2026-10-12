package com.loopers.config.jackson

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class JacksonConfigTest {
    private val mapper: JsonMapper = JsonMapper.builder()
        .also { JacksonConfig().jacksonCustomizer().customize(it) }
        .build()

    data class Payload(
        val name: String?,
        val tags: Map<String, String?>,
    )

    @Test
    fun `null properties are left out`() {
        val json = mapper.writeValueAsString(Payload(name = null, tags = emptyMap()))

        assertThat(json).isEqualTo("""{"tags":{}}""")
    }

    @Test
    fun `null map values are left out`() {
        val json = mapper.writeValueAsString(Payload(name = "x", tags = mapOf("kept" to "v", "dropped" to null)))

        assertThat(json).isEqualTo("""{"name":"x","tags":{"kept":"v"}}""")
    }
}
