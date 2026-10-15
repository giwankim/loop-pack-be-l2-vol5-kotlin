package com.loopers.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar

/**
 * Redis 장애 테스트의 설정이다. 이 설정을 가져온 컨텍스트는 JVM마다 하나인 [RedisTestContainersConfig]의 Redis 대신
 * 자기만의 [StoppableRedis]를 쓴다. master와 replica의 주소가 모두 그것을 가리킨다.
 *
 * 장애 테스트 클래스만 `@Import`로 가져오므로 그 클래스들은 컨텍스트가 따로 뜬다. 멈추거나 걸리게 해도 다른 클래스의 Redis는
 * 그대로다. 컨테이너는 이 컨텍스트의 빈이라 컨텍스트가 닫힐 때 이 컨테이너만 지워진다.
 *
 * 두 설정의 [DynamicPropertyRegistrar]는 같은 속성을 쓰고, 나중에 등록된 쪽이 이긴다. 하위 테스트 클래스의 `@Import`는 기반 클래스의
 * 것보다 먼저 처리되므로, 이 설정이 [RedisTestContainersConfig]를 가져와 그 빈을 자기 빈보다 먼저 등록하게 한다.
 * 그래서 기반 클래스가 [RedisTestContainersConfig]를 가져와도, `@Import`의 차례가 어떻든 이 설정의 주소가 남는다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(RedisTestContainersConfig::class)
class StoppableRedisTestContainersConfig {
    @Bean
    fun stoppableRedis(): StoppableRedis {
        return StoppableRedis()
    }

    @Bean
    fun stoppableRedisProperties(stoppableRedis: StoppableRedis): DynamicPropertyRegistrar {
        return DynamicPropertyRegistrar { registry ->
            registry.add("datasource.redis.database") { 0 }
            registry.add("datasource.redis.master.host") { stoppableRedis.host }
            registry.add("datasource.redis.master.port") { stoppableRedis.port }
            registry.add("datasource.redis.replicas[0].host") { stoppableRedis.host }
            registry.add("datasource.redis.replicas[0].port") { stoppableRedis.port }
        }
    }
}
