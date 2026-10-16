package com.loopers.application.product.provided

import com.loopers.application.product.ProductListCacheKeyGenerator
import com.loopers.support.Pause
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.data.redis.cache.RedisCacheWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 과제의 늦은 채움 장면. 앞선 조회가 관리자의 변경이 커밋된 뒤에야 옛 결과를 캐시에 넣어도, 커밋 뒤에 시작한 조회는 그 변경을 본다(ADR 0020).
 * 늦은 put은 옛 revision의 키에 들어가고, 커밋 뒤의 조회는 올라간 revision의 키를 읽기 때문이다.
 *
 * 순서는 잠이 아니라 [Pause]로 정한다. 캐시 writer를 spy해 첫 put이 [Pause]에서 멈추게 하고, 멈춘 동안 테스트가 수정을 커밋한 뒤 푼다.
 * 그래서 옛 결과의 put은 언제나 커밋 뒤에 끝난다. writer는 AOP 프록시가 아니므로 spy에 그대로 stub한다.
 * 멈춘 조회는 읽기 전용 트랜잭션을 연 채지만 revision을 잠그지 않고 읽으므로, 수정이 revision 행을 올리며 기다리지 않는다.
 * 늦은 put이 옛 키에 들어갔는지는 응답에 보이지 않으므로 캐시를 그 키로 직접 읽는다.
 */
class ProductCacheLateRefillTest(
    private val productFinder: ProductFinder,
    private val cacheManager: CacheManager,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var redisCacheWriter: RedisCacheWriter

    private val pause = Pause()

    /**
     * 멈춘 조회는 수정 전의 revision에서 읽어 옛 가격을 돌려주고, 수정이 커밋한 뒤 그 결과를 수정 전 revision의 키에 넣는다.
     * 풀기 전에는 그 키가 비어 있으므로 옛 결과는 커밋 뒤에 들어갔다. 그래도 새 조회는 올라간 revision의 키를 읽어 새 가격을 본다.
     */
    @Test
    fun `a list read that stores its old result after an admin price update commits does not hide the new price`() {
        prepareProduct(price = 5_000)
        val request = ProductListRequest()
        val oldKey = currentListCacheKey(request)
        pauseFirstPut()

        val late = inAnotherThread { productFinder.findAll(request) }
        pause.awaitHeld()
        updateProduct(price = 1_000)
        assertThat(cachedAt(oldKey)).isNull()
        pause.release()

        val lateResult = late.await()
        assertThat(lateResult.items.map { it.id to it.price }).containsExactly(product.id to 5_000L)
        assertThat(cachedAt(oldKey)).isEqualTo(lateResult)
        val reread = productFinder.findAll(request)
        assertThat(reread.items.map { it.id to it.price }).containsExactly(product.id to 1_000L)
    }

    /** 캐시의 첫 put이 Redis로 보내기 전에 멈춘다. 뒤의 put은 멈추지 않는다. 수정은 캐시에 넣지 않고 revision만 올린다. */
    private fun pauseFirstPut() {
        val first = AtomicBoolean(true)
        every { redisCacheWriter.put(any(), any(), any(), any()) } answers {
            if (first.getAndSet(false)) {
                pause.hold()
            }
            callOriginal()
        }
    }

    /** 고객 목록 캐시에서 [key]의 값을 읽는다. */
    private fun cachedAt(key: Any): ProductListInfo? {
        val cache = checkNotNull(cacheManager.getCache(ProductListCacheKeyGenerator.CACHE_NAME))

        return cache.get(key, ProductListInfo::class.java)
    }
}
