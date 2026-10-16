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
 *
 * 디스크에 아무것도 남기지 않는다(`--save ""`, `--appendonly no`). 이미지의 기본값에는 저장 지점(`3600 1 300 100 60 10000`)이 있고,
 * 저장 지점이 있으면 `FLUSHALL`이 명령 안에서 RDB 파일을 동기로 쓴다. 테스트는 `RedisCleanUp`으로 `FLUSHALL`을 자주 보내므로
 * (성능 측정은 요청마다 한 번), 디스크가 바쁠 때 그 쓰기가 명령 한도(100 ms)를 넘겨 `QueryTimeoutException`이 났다.
 * 한도는 운영 설정이라 늘리지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainersConfig {
    companion object {
        private const val REDIS_PORT = 6379

        private val redisContainer: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.10"))
            .apply {
                withExposedPorts(REDIS_PORT)
                withCommand("redis-server", "--save", "", "--appendonly", "no")
                start()
            }
    }

    @Bean
    fun redisContainerProperties(): DynamicPropertyRegistrar {
        return DynamicPropertyRegistrar { registry ->
            registry.add("datasource.redis.database") { 0 }
            registry.add("datasource.redis.master.host") { redisContainer.host }
            registry.add("datasource.redis.master.port") { redisContainer.firstMappedPort }
            registry.add("datasource.redis.replicas[0].host") { redisContainer.host }
            registry.add("datasource.redis.replicas[0].port") { redisContainer.firstMappedPort }
        }
    }
}
