package com.loopers.adapter.webapi.v1.product

import com.loopers.application.product.required.ProductListRevisionRepository
import com.loopers.support.test.BaseWebApiAdapterTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * DB가 실패하면 고객 상품 목록은 캐시가 차 있어도 기존 오류 계약대로 실패한다(ADR 0020). 키가 revision을 품으므로 revision을
 * 읽지 못하면 키도 없고, 캐시를 읽기 전에 실패한다. 그 오류는 캐시의 오류 처리기를 거치지 않는다.
 *
 * 공유 MySQL을 멈추지 않는다. 다른 클래스가 모두 그것을 쓰기 때문이다. 대신 revision 저장소를 spy해 읽기가 던지게 한다.
 * Spring Data 저장소는 JDK 프록시이므로 stub은 spy에 바로 건다. spy 필드가 컨텍스트를 하나 더 띄우므로 이 장면만
 * [ProductCacheApiTest]에서 떼어 낸다.
 */
class ProductCacheDatabaseFailureApiTest : BaseWebApiAdapterTest() {
    companion object {
        private const val ENDPOINT = "/api/v1/products"
    }

    @MockkSpyBean
    private lateinit var productListRevisionRepository: ProductListRevisionRepository

    /** 첫 요청이 페이지를 캐시에 채워 두고, 그 뒤에 revision 읽기를 실패시킨다. */
    @Test
    fun `a failing revision read answers 500 INTERNAL_ERROR even when the page is cached`() {
        prepareProduct()
        assertThat(requestGetProducts()).hasStatusOk()
        every { productListRevisionRepository.findRevision() } throws DataAccessResourceFailureException("revision을 읽다 실패했다")

        val body = assertThat(requestGetProducts()).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR).bodyJson()

        body.extractingPath("$.meta.errorCode").isEqualTo("Internal Server Error")
    }

    private fun requestGetProducts(): MvcTestResult {
        return mvc.get().uri(ENDPOINT).exchange()
    }
}
