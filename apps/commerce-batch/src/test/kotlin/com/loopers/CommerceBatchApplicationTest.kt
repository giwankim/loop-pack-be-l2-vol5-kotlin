package com.loopers

import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(properties = ["spring.batch.job.enabled=false"])
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class CommerceBatchApplicationTest {
    @Test
    fun contextLoads() {}
}
