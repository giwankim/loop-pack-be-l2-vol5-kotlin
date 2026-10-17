package com.loopers.adapter.persistence.product

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.interceptor.LoggingCacheErrorHandler
import java.util.function.Supplier

private val log = KotlinLogging.logger {}

/**
 * 캐시 오류를 WARN으로 남기고 삼킨다. Redis가 실패해도 고객 목록은 DB에서 답한다. 기본인 `SimpleCacheErrorHandler`는 다시 던진다.
 *
 * - 실패한 읽기는 miss가 되어 메서드가 DB를 읽는다. 읽은 값을 역직렬화하지 못한 것도 읽기 오류로 와서 miss다.
 * - 실패한 쓰기는 DB의 결과를 그대로 돌려준다.
 *
 * 메시지는 Spring의 [LoggingCacheErrorHandler]가 짓는다(`Cache 'product-list' failed to get entry with key '…'`).
 * 거기에 원인의 타입과 메시지를 붙이고 스택 트레이스는 남기지 않는다. Redis가 멈춘 동안에는 miss마다 읽기와 쓰기가 하나씩 실패하므로
 * 스택 트레이스를 남기면 로그가 그것으로 덮인다.
 */
class ProductListCacheErrorHandler : LoggingCacheErrorHandler() {
    override fun logCacheError(messageSupplier: Supplier<String>, exception: RuntimeException) {
        log.warn { "${messageSupplier.get()} : $exception" }
    }
}
