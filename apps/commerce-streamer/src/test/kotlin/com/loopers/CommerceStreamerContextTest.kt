package com.loopers

import com.loopers.testcontainers.KafkaTestContainersConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** The same `@Import` as `BaseApplicationServiceTest`, so the two share one context. */
@SpringBootTest
@Import(
    MySqlTestContainersConfig::class,
    KafkaTestContainersConfig::class,
)
class CommerceStreamerContextTest {
    @Test
    fun `the application context starts`() {}
}
