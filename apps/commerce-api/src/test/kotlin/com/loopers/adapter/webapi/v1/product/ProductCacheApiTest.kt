package com.loopers.adapter.webapi.v1.product

import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseWebApiAdapterTest
import com.loopers.support.withStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 고객 상품 목록 캐시의 HTTP 경계. 캐시한 결과가 웹 어댑터를 지나도 처음 응답과 같은 JSON인지 본다(ADR 0020).
 * 캐시가 붙은 포트 자체는 [com.loopers.application.product.provided.ProductCacheTest]가 본다.
 */
class ProductCacheApiTest(
    private val objectMapper: ObjectMapper,
) : BaseWebApiAdapterTest() {
    companion object {
        private const val ENDPOINT = "/api/v1/products"
    }

    /**
     * 두 번째 요청은 기본값인 `page=0`을 직접 적는다. 기본값을 채운 요청과 같은 키를 쓰므로 hit이고, SQL은 revision 읽기 하나다.
     * 좋아요를 하나 둬서 캐시한 값이 0이 아닌 좋아요 수도 옮기게 한다.
     * 준비한 행을 요청 앞에서 flush해, 조회 전의 자동 flush가 요청 안에서 `insert`를 보내지 않게 한다.
     */
    @Test
    fun `the JSON of a cached product list is the JSON of the first response`() {
        prepareBrand()
        prepareProduct(brand)
        val liked = prepareProduct(brand)
        prepareLike(product = liked)
        entityManager.flushAndClear()
        val miss = requestGetProducts(page = null)

        entityManager.withStatistics { statistics ->
            val hit = requestGetProducts(page = 0)

            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
            assertThat(miss).hasStatusOk()
            assertThat(hit).hasStatusOk()
            assertThat(hit.json()).isEqualTo(miss.json())
        }
    }

    /** 브랜드 하나의 가격 낮은순. [page]가 null이면 쿼리 문자열에서 뺀다. */
    private fun requestGetProducts(page: Int?): MvcTestResult {
        return mvc.get().uri(ENDPOINT)
            .queryParam("brandId", brand.id.toString())
            .queryParam("sort", "price_asc")
            .apply { page?.let { queryParam("page", it.toString()) } }
            .exchange()
    }

    private fun MvcTestResult.json(): JsonNode {
        return objectMapper.readTree(response.contentAsString)
    }
}
