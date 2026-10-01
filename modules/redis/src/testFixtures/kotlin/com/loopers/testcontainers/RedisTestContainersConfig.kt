package com.loopers.testcontainers

import org.springframework.context.annotation.Configuration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

@Configuration
class RedisTestContainersConfig {
    companion object {
        private const val REDIS_PORT = 6379

        private val redisContainer: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:latest"))
            .apply {
                withExposedPorts(REDIS_PORT)
                start()
            }
    }

    init {
        System.setProperty("datasource.redis.database", "0")
        System.setProperty("datasource.redis.master.host", redisContainer.host)
        System.setProperty("datasource.redis.master.port", redisContainer.firstMappedPort.toString())
        System.setProperty("datasource.redis.replicas[0].host", redisContainer.host)
        System.setProperty("datasource.redis.replicas[0].port", redisContainer.firstMappedPort.toString())
    }
}
