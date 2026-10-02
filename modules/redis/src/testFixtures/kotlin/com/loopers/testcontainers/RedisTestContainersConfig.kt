package com.loopers.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 통합 테스트용 Redis 컨테이너다. 컨테이너는 JVM마다 한 번 띄우고, 접속 정보는 이 설정을 가져온 컨텍스트의 `Environment`에만 넣는다.
 *
 * 최상위 `@TestConfiguration`은 컴포넌트 스캔에서 빠지므로, `@SpringBootTest`가 `@Import`로 명시해서 쓴다.
 * 빠뜨려도 컨텍스트는 뜬다. `redis.yml`의 `test` 프로필이 `localhost:6379`를 가리키고 연결은 처음 쓸 때 맺기 때문이다.
 * 그 상태에서 `RedisCleanUp`을 부르면 로컬 Redis를 비운다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainersConfig {
    companion object {
        private const val REDIS_PORT = 6379

        private val redisContainer: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.10"))
            .apply {
                withExposedPorts(REDIS_PORT)
                start()
            }
    }

    @Bean
    fun redisContainerProperties(): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("datasource.redis.database") { 0 }
            registry.add("datasource.redis.master.host") { redisContainer.host }
            registry.add("datasource.redis.master.port") { redisContainer.firstMappedPort }
            registry.add("datasource.redis.replicas[0].host") { redisContainer.host }
            registry.add("datasource.redis.replicas[0].port") { redisContainer.firstMappedPort }
        }
}
