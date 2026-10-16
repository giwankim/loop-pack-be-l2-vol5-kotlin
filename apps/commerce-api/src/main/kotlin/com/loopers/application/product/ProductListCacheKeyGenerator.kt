package com.loopers.application.product

import com.loopers.application.product.provided.ProductListRequest
import com.loopers.application.product.required.ProductListRevisionRepository
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.reflect.Method

/**
 * 고객 목록 캐시의 키. `v1:{revision}:{brandId|all}:{sort}:{page}:{size}`이다(ADR 0020 규칙 2).
 *
 * - `v1`은 캐시하는 값(`ProductListInfo`)의 모양의 판이다. 모양을 바꾸면 올린다. 옛 판의 값은 읽히지 않고 TTL로 사라진다.
 * - revision은 지금 트랜잭션에서 읽는다. 트랜잭션 advisor가 캐시 advisor를 감싸므로(`JpaConfig`의 order) 이 읽기가 고객 목록의
 *   읽기 전용 트랜잭션의 첫 문장이 되고, 뒤따르는 페이지 쿼리와 좋아요 수 조회가 같은 스냅샷을 본다. 그래서 트랜잭션이 없으면 실패한다.
 *   페이지를 먼저 읽고 revision을 나중에 읽으면, 그 사이 커밋한 변경 전의 페이지가 새 revision의 키에 들어간다.
 * - `sort`는 요청의 철자 그대로다. 모르는 철자도 키를 만들지만, 메서드가 `INVALID_SORT`를 던지므로 그 키에는 아무것도 들어가지 않는다.
 * - `page`·`size`는 [ProductListRequest]가 기본값을 채운 값이라 `?page=0`을 보낸 요청과 생략한 요청이 같은 키를 쓴다.
 */
@Component(ProductListCacheKeyGenerator.BEAN_NAME)
class ProductListCacheKeyGenerator(
    private val productListRevisionRepository: ProductListRevisionRepository,
) : KeyGenerator {
    companion object {
        const val BEAN_NAME = "productListCacheKeyGenerator"

        /** 고객 목록의 캐시 이름. Redis에는 `product-list::` 뒤에 키가 붙는다. */
        const val CACHE_NAME = "product-list"

        /** 캐시하는 값의 모양의 판. */
        private const val SHAPE_VERSION = "v1"

        /** 브랜드를 고르지 않은 목록의 자리. */
        private const val ALL_BRANDS = "all"
    }

    override fun generate(target: Any, method: Method, vararg params: Any?): Any {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "고객 목록의 캐시 키는 revision을 읽는 트랜잭션 안에서만 만든다."
        }

        val request = params.single() as ProductListRequest
        val revision = productListRevisionRepository.findRevision()

        return listOf(
            SHAPE_VERSION,
            revision,
            request.brandId ?: ALL_BRANDS,
            request.sort,
            request.page,
            request.size,
        ).joinToString(":")
    }
}
