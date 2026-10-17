package com.loopers.config.redis

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * [commandTimeout]은 명령 하나의 응답을, [connectTimeout]은 연결을 기다리는 한도다. 기본값은 `redis.yml`에 있다.
 * 두 연결 팩터리가 같이 읽는다.
 */
@ConfigurationProperties(value = "datasource.redis")
data class RedisProperties(
    val database: Int,
    val master: RedisNodeInfo,
    val replicas: List<RedisNodeInfo>,
    val commandTimeout: Duration,
    val connectTimeout: Duration,
)
