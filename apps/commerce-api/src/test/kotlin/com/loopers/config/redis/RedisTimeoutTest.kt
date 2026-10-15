package com.loopers.config.redis

import com.loopers.support.test.BaseApplicationServiceTest
import com.loopers.testcontainers.StoppableRedis
import com.loopers.testcontainers.StoppableRedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import kotlin.time.measureTime

/**
 * Redis가 멈추거나 걸려도 명령이 시간 한도 안에 실패하고, 돌아오면 같은 컨텍스트에서 다시 성공하는지 본다(`redis.yml`의
 * `command-timeout`·`connect-timeout`). 실제 Redis를 멈추고 걸리게 하므로 [StoppableRedisTestContainersConfig]를 가져와
 * 이 클래스만의 컨텍스트와 Redis를 쓴다. JVM마다 하나인 테스트 Redis는 건드리지 않는다.
 *
 * 포트는 없지만 컨텍스트와 정리를 쓰려고 [BaseApplicationServiceTest]를 상속한다. 시간은 넉넉한 상한으로만 단언한다.
 * 정해 둔 한도(100 ms)와 같은지 보지 않는다.
 */
@Import(StoppableRedisTestContainersConfig::class)
class RedisTimeoutTest(
    private val redisTemplate: RedisTemplate<String, String>,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
    private val stoppableRedis: StoppableRedis,
) : BaseApplicationServiceTest() {
    companion object {
        /** 명령 하나가 실패하기까지 기다려 주는 상한. 한도 100 ms보다 넉넉하고 Lettuce 기본값 60 s보다 훨씬 짧다. */
        private val FAILURE_BOUND: Duration = Duration.ofSeconds(1)

        /**
         * 다시 띄운 Redis에 Lettuce가 다시 연결하기를 기다리는 상한. Lettuce는 끊긴 동안 다시 연결하는 간격을 두 배씩 늘리므로,
         * 다시 띄운 뒤 첫 명령은 다음 시도 전이라 실패할 수 있다.
         */
        private val RECONNECT_BOUND: Duration = Duration.ofSeconds(30)
    }

    /** 컨텍스트를 나눠 쓰는 다음 테스트를 위해 Redis를 되살리고, Lettuce가 다시 연결할 때까지 기다린다. */
    @AfterEach
    fun restoreRedis() {
        stoppableRedis.restore()
        awaitCommandSucceeds()
    }

    @Test
    fun `a command to a paused Redis fails within the bound instead of hanging`() {
        redisTemplate.opsForValue().set("key", "value")
        stoppableRedis.pause()

        val elapsed = measureTime {
            assertThrows<DataAccessException> { redisTemplate.opsForValue().get("key") }
        }

        assertThat(elapsed.inWholeMilliseconds).isLessThan(FAILURE_BOUND.toMillis())
    }

    @Test
    fun `a command through the master template to a paused Redis fails within the bound`() {
        masterRedisTemplate.opsForValue().set("key", "value")
        stoppableRedis.pause()

        val elapsed = measureTime {
            assertThrows<DataAccessException> { masterRedisTemplate.opsForValue().get("key") }
        }

        assertThat(elapsed.inWholeMilliseconds).isLessThan(FAILURE_BOUND.toMillis())
    }

    @Test
    fun `a command to a stopped Redis fails within the bound`() {
        redisTemplate.opsForValue().set("key", "value")
        stoppableRedis.stop()

        val elapsed = measureTime {
            assertThrows<DataAccessException> { redisTemplate.opsForValue().get("key") }
        }

        assertThat(elapsed.inWholeMilliseconds).isLessThan(FAILURE_BOUND.toMillis())
    }

    @Test
    fun `the next command after unpausing succeeds on the same context`() {
        redisTemplate.opsForValue().set("key", "value")
        stoppableRedis.pause()
        assertThrows<DataAccessException> { redisTemplate.opsForValue().get("key") }

        stoppableRedis.unpause()

        assertThat(redisTemplate.opsForValue().get("key")).isEqualTo("value")
    }

    /**
     * 컨텍스트의 연결 팩터리는 처음 받은 주소만 안다. 그러므로 다시 띄운 뒤 같은 컨텍스트로 보낸 명령이 성공하면 Redis가 같은 주소에
     * 돌아온 것이다. 다시 연결하는 간격 때문에 다시 띄운 직후의 명령은 실패할 수 있어 [RECONNECT_BOUND]까지 기다린다.
     */
    @Test
    fun `a command after starting Redis again succeeds on the same context at the same address`() {
        redisTemplate.opsForValue().set("key", "value")
        stoppableRedis.stop()
        assertThrows<DataAccessException> { redisTemplate.opsForValue().get("key") }

        stoppableRedis.start()

        awaitCommandSucceeds()
        redisTemplate.opsForValue().set("key", "again")
        assertThat(redisTemplate.opsForValue().get("key")).isEqualTo("again")
    }

    private fun awaitCommandSucceeds() {
        await()
            .atMost(RECONNECT_BOUND)
            .ignoreExceptionsInstanceOf(DataAccessException::class.java)
            .untilAsserted { redisTemplate.opsForValue().get("key") }
    }
}
