package com.loopers.application.product.provided

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.adapter.persistence.product.ProductListCacheErrorHandler
import com.loopers.application.product.ProductListCacheKeyGenerator
import com.loopers.support.awaitCommandSucceeds
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.loopers.support.withStatistics
import com.loopers.testcontainers.StoppableRedis
import com.loopers.testcontainers.StoppableRedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import kotlin.time.measureTimedValue

/**
 * Redis가 멈추거나 걸리거나 읽을 수 없는 값을 돌려줘도 고객 목록이 DB에서 답하는지 실제 MySQL·Redis 위에서 확인한다(ADR 0020).
 * 실패한 캐시 읽기는 miss, 실패한 캐시 쓰기는 DB의 결과를 그대로 돌려주며, 둘 다 [ProductListCacheErrorHandler]가 WARN으로 남긴다.
 *
 * Redis를 멈추고 걸리게 하므로 [StoppableRedisTestContainersConfig]를 가져와 이 클래스만의 컨텍스트와 Redis를 쓴다. 기반이
 * 테스트마다 보내는 `FLUSHALL`도 그 Redis로 가고, 컨텍스트가 닫힐 때 지워지는 것도 그 컨테이너뿐이다. JVM마다 하나인 테스트 Redis는
 * 건드리지 않는다. 쓰기가 revision을 올리는 장면이 있어 커밋하는 기반을 쓴다. 시간은 넉넉한 상한으로만 단언한다.
 *
 * Redis가 멈춘 동안의 miss가 성공하는 것은 `transactionAware()`가 꺼져 있어서다. 켜면 캐시 쓰기가 커밋 뒤로 미뤄져 오류 처리기를
 * 거치지 않고, 그 예외가 이미 커밋한 목록 요청을 실패시킨다. 멈춘 Redis의 테스트들이 그것을 지킨다.
 */
@Import(StoppableRedisTestContainersConfig::class)
class ProductCacheRedisFailureTest(
    private val productFinder: ProductFinder,
    private val redisTemplate: RedisTemplate<String, String>,
    private val stoppableRedis: StoppableRedis,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** 멈추거나 걸린 Redis 앞에서 목록 하나를 답하기까지의 상한. 읽기와 쓰기가 저마다 한도(100 ms)를 기다리고 DB를 읽어도 넉넉하다. */
        private val ANSWER_BOUND: Duration = Duration.ofSeconds(2)
    }

    /** 오류 처리기가 남긴 로그. 테스트마다 붙이고 뗀다. */
    private val cacheErrorLogs = ListAppender<ILoggingEvent>()

    private val cacheErrorLogger = LoggerFactory.getLogger(ProductListCacheErrorHandler::class.java) as Logger

    /**
     * 연결의 첫 인사도 명령 한도(100 ms)에 묶인다. 새로 뜬 컨텍스트의 첫 연결은 그 안에 끝나지 못할 수 있어, 테스트가 Redis에
     * 직접 값을 두기 전에 연결이 서기를 기다린다. 로그는 그 뒤에 붙여 테스트가 남긴 것만 읽는다.
     */
    @BeforeEach
    fun captureCacheErrorLogs() {
        redisTemplate.awaitCommandSucceeds()
        cacheErrorLogs.start()
        cacheErrorLogger.addAppender(cacheErrorLogs)
    }

    /** 컨텍스트를 나눠 쓰는 다음 테스트를 위해 Redis를 되살리고, Lettuce가 다시 연결할 때까지 기다린다. 기반의 정리보다 먼저 돈다. */
    @AfterEach
    fun restoreRedis() {
        cacheErrorLogger.detachAppender(cacheErrorLogs)
        stoppableRedis.restore()
        redisTemplate.awaitCommandSucceeds()
    }

    /**
     * 캐시를 채운 뒤 Redis를 멈춘다. 다시 읽은 목록은 miss의 세 문장으로 DB에서 답하고, 실패한 읽기와 쓰기가 WARN 하나씩을 남긴다.
     * 처음 읽기는 캐시를 채우면서 이 컨텍스트의 첫 쿼리를 미리 데워 둔다.
     */
    @Test
    fun `with Redis stopped the list answers from the database within the bound and logs the failed get and put`() {
        prepareProduct()
        val cached = productFinder.findAll(ProductListRequest())
        stoppableRedis.stop()

        entityManager.withStatistics { statistics ->
            val (list, elapsed) = measureTimedValue { productFinder.findAll(ProductListRequest()) }

            assertThat(list).isEqualTo(cached)
            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
            assertThat(elapsed.inWholeMilliseconds).isLessThan(ANSWER_BOUND.toMillis())
        }
        assertFailedGetAndPutLogged()
    }

    /** 걸린 Redis는 연결이 열린 채 답하지 않는다. 읽기와 쓰기가 저마다 한도에서 끊기고 목록은 DB에서 답한다. */
    @Test
    fun `with Redis paused the list answers from the database within the bound and logs the failed get and put`() {
        prepareProduct()
        val cached = productFinder.findAll(ProductListRequest())
        stoppableRedis.pause()

        entityManager.withStatistics { statistics ->
            val (list, elapsed) = measureTimedValue { productFinder.findAll(ProductListRequest()) }

            assertThat(list).isEqualTo(cached)
            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
            assertThat(elapsed.inWholeMilliseconds).isLessThan(ANSWER_BOUND.toMillis())
        }
        assertFailedGetAndPutLogged()
    }

    /**
     * 과제의 "커밋 뒤의 무효화 실패" 장면. 쓰기는 Redis에 아무것도 보내지 않으므로(ADR 0020 규칙 4) Redis가 멈춰도 커밋하고,
     * 실패할 무효화 호출이 없다. 그래서 쓰기는 캐시 오류를 남기지 않는다.
     * 멈춘 동안과 다시 띄운 뒤의 목록이 모두 새 가격이다. 다시 띄운 Redis에는 멈추기 전에 채운 옛 revision의 값이 남아 있지만,
     * 커밋한 쓰기가 revision을 올렸으므로 아무도 그 키를 읽지 않는다. 남은 값은 응답에 보이지 않으므로 Redis에 직접 묻는다.
     */
    @Test
    fun `a product update committed while Redis is stopped shows in the list during and after the outage`() {
        prepareProduct(price = 8_000)
        val staleKey = redisKeyOf(ProductListRequest())
        productFinder.findAll(ProductListRequest())
        stoppableRedis.stop()

        updateProduct(price = 1_000)

        assertThat(cacheErrorLogs.list).isEmpty()
        val duringOutage = productFinder.findAll(ProductListRequest())
        stoppableRedis.start()
        redisTemplate.awaitCommandSucceeds()
        val afterRestart = productFinder.findAll(ProductListRequest())
        assertThat(duringOutage.items.single().price).isEqualTo(1_000L)
        assertThat(afterRestart.items.single().price).isEqualTo(1_000L)
        assertThat(redisTemplate.hasKey(staleKey)).isTrue()
    }

    /** 다시 띄운 뒤 첫 읽기는 miss라서 세 문장으로 캐시를 채우고, 다음 읽기는 revision 하나만 읽는 hit다. */
    @Test
    fun `after Redis starts again the first read fills the cache and the next read is a hit`() {
        prepareProduct()
        stoppableRedis.stop()
        productFinder.findAll(ProductListRequest())

        stoppableRedis.start()
        redisTemplate.awaitCommandSucceeds()

        entityManager.withStatistics { statistics ->
            productFinder.findAll(ProductListRequest())

            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
        }
        entityManager.withStatistics { statistics ->
            productFinder.findAll(ProductListRequest())

            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
        }
    }

    /** 지금의 키에 역직렬화할 수 없는 값을 둔다. 읽기 오류라서 miss가 되고, 목록은 DB에서 답하며 WARN을 남긴다. */
    @Test
    fun `an unreadable cached value is a miss that answers from the database and logs a warning`() {
        prepareProduct()
        redisTemplate.opsForValue().set(redisKeyOf(ProductListRequest()), "{not json")

        entityManager.withStatistics { statistics ->
            val list = productFinder.findAll(ProductListRequest())

            assertThat(list.items.map { it.id }).containsExactly(product.id)
            assertThat(statistics.prepareStatementCount).isEqualTo(3L)
        }
        assertThat(cacheErrorLogs.list).singleElement().satisfies({
            assertThat(it.level).isEqualTo(Level.WARN)
            assertThat(it.formattedMessage).contains("failed to get")
        })
    }

    /** 지금 커밋된 revision으로 [request]의 목록이 쓰는 Redis 키. 캐시 이름 뒤에 캐시 키가 붙는다. */
    private fun redisKeyOf(request: ProductListRequest): String {
        return "${ProductListCacheKeyGenerator.CACHE_NAME}::${currentListCacheKey(request)}"
    }

    private fun assertFailedGetAndPutLogged() {
        assertThat(cacheErrorLogs.list).allSatisfy { assertThat(it.level).isEqualTo(Level.WARN) }
        assertThat(cacheErrorLogs.list.map { it.formattedMessage }).satisfiesExactly(
            { assertThat(it).contains("failed to get") },
            { assertThat(it).contains("failed to put") },
        )
    }
}
