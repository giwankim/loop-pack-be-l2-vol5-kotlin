package com.loopers

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["spring.batch.job.enabled=false"])
class CommerceBatchApplicationTest {
    @Test
    fun contextLoads() {}
}
