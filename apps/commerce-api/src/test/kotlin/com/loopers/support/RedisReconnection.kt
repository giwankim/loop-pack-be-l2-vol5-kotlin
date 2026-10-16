package com.loopers.support

import org.awaitility.Awaitility.await
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * 다시 띄운 Redis에 Lettuce가 다시 연결하기를 기다리는 상한. Lettuce는 끊긴 동안 다시 연결하는 간격을 두 배씩 늘리므로,
 * 다시 띄운 뒤 첫 명령은 다음 시도 전이라 실패할 수 있다.
 */
private val RECONNECT_BOUND: Duration = Duration.ofSeconds(30)

/**
 * 이 템플릿의 명령이 성공할 때까지 길어야 [RECONNECT_BOUND]만큼 기다린다. 멈추거나 걸리게 했던 Redis
 * ([com.loopers.testcontainers.StoppableRedis])를 되살린 뒤, 다음 명령이 실패하지 않게 부른다.
 */
fun RedisTemplate<String, String>.awaitCommandSucceeds() {
    await()
        .atMost(RECONNECT_BOUND)
        .ignoreExceptionsInstanceOf(DataAccessException::class.java)
        .untilAsserted { hasKey("ping") }
}
