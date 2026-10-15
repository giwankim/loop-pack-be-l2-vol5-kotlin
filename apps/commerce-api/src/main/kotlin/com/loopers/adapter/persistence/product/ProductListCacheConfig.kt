package com.loopers.adapter.persistence.product

import com.loopers.application.product.ProductListCacheKeyGenerator
import com.loopers.application.product.provided.ProductListInfo
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheWriter
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * 고객 상품 목록의 Redis 캐시(ADR 0020). Boot가 만드는 `RedisCacheManager`를 [RedisCacheManagerBuilderCustomizer]로 고친다.
 *
 * `@EnableCaching`은 기본 order다. `JpaConfig`의 `@EnableTransactionManagement`가 그보다 앞서므로 트랜잭션 advisor가
 * 캐시 advisor를 감싸, 키 생성기의 revision 읽기가 고객 목록의 읽기 전용 트랜잭션 안에서 첫 문장이 된다.
 *
 * 연결 팩터리는 `@Primary`(REPLICA_PREFERRED)다. GET은 replica를 먼저 읽고 SET은 Lettuce가 master로 보낸다.
 * 한 키의 값은 바뀌지 않으므로 replica의 지연은 miss를 늘릴 뿐 옛 값을 돌려주지 않는다.
 */
@Configuration
@EnableCaching
class ProductListCacheConfig {
    companion object {
        /** 옛 revision의 키를 치우는 시간. 신선도의 상한이 아니다(ADR 0020 규칙 5). 지터를 두지 않는다. */
        private val PRODUCT_LIST_TTL: Duration = Duration.ofMinutes(5)
    }

    /**
     * 쓰기를 곧바로 보내는 writer. Lettuce의 연결 팩터리는 reactive를 지원해서 기본이 비동기 쓰기다. 그러면 쓰기 오류가
     * `CacheErrorHandler`에 닿지 않고, 쓰기가 늦게 들어가 "읽고 다시 읽으면 hit"가 정해지지 않는다.
     * 빈으로 두는 것은 늦은 채움을 보는 테스트가 spy하기 위해서다.
     *
     * 쓰기는 만료를 단 `SET … PX` 한 명령이다. Lua와 `MULTI`는 쓰지 않는다. 같은 키의 값은 한 스냅샷의 결과라서
     * 동시에 채워도 같은 값을 쓴다(ADR 0020 규칙 4).
     */
    @Bean
    fun redisCacheWriter(redisConnectionFactory: RedisConnectionFactory): RedisCacheWriter {
        return RedisCacheWriter.create(redisConnectionFactory) { it.immediateWrites() }
    }

    /**
     * `product-list`의 값은 [ProductListInfo]의 Jackson JSON이다. Boot가 만든 Jackson 3 매퍼를 쓰고, 타입이 정해져 있어 값에
     * 타입 정보를 싣지 않는다. null은 캐시하지 않는다.
     *
     * `transactionAware()`는 켜지 않는다. 미룬 put이 `afterCommit`에서 오류 처리기를 거치지 않고 Redis를 부르고, Spring은
     * `afterCommit`의 예외를 호출자에게 던진다. 그러면 Redis가 멈췄을 때 이미 커밋한 목록 요청이 실패한다.
     * 고객 목록의 읽기 전용 트랜잭션에는 되돌릴 쓰기도 없어 미룰 까닭이 없다.
     */
    @Bean
    fun productListCacheCustomizer(
        redisCacheWriter: RedisCacheWriter,
        jsonMapper: JsonMapper,
    ): RedisCacheManagerBuilderCustomizer {
        val serializer = JacksonJsonRedisSerializer(jsonMapper, ProductListInfo::class.java)
        val productList = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(PRODUCT_LIST_TTL)
            .serializeValuesWith(SerializationPair.fromSerializer(serializer))
            .disableCachingNullValues()

        return RedisCacheManagerBuilderCustomizer { builder ->
            builder.cacheWriter(redisCacheWriter)
                .withCacheConfiguration(ProductListCacheKeyGenerator.CACHE_NAME, productList)
        }
    }
}
