package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

/**
 * 고객 브랜드 API. 식별 없이 부를 수 있어야 하므로 요청에 아무 principal도 싣지 않는다.
 *
 * [AdminSecurityConfig]를 가져오는 까닭은 관리자 경계가 필요해서만이 아니다. Spring Security가 테스트 클래스패스에 있어서
 * `SecurityFilterChain` 빈이 하나도 없는 컨텍스트에는 Boot 기본 체인이 들어가 모든 경로에 인증을 요구한다(설계 5.10).
 * 이 빈이 있으면 체인은 관리자 경로 하나뿐이고, 고객 경로(`/api/` 아래)는 어느 체인에도 걸리지 않아 그대로 지나간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class BrandApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandService: BrandService,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val ENDPOINT = "/api/v1/brands"
        private const val ADMIN_ENDPOINT = "/api-admin/v1/brands"
        private val ADMIN = user("admin").roles("ADMIN")
    }

    @Test
    fun `a customer reads a brand without any identification`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        mockMvc.get("$ENDPOINT/${brand.id}")
            .andExpect {
                status { isOk() }
                jsonPath("$.meta.result") { value("SUCCESS") }
                jsonPath("$.data.id") { value(brand.id) }
                jsonPath("$.data.name") { value("루퍼스") }
                jsonPath("$.data.createdAt") { doesNotExist() }
                jsonPath("$.data.updatedAt") { doesNotExist() }
            }
    }

    @Test
    fun `reading an unknown brand returns 404`() {
        mockMvc.get("$ENDPOINT/999")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Not Found") }
                jsonPath("$.meta.message") { value(ErrorType.BRAND_NOT_FOUND.message) }
            }
    }

    @Test
    fun `reading a deleted brand returns 404`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))
        brandService.delete(brand.id)
        startNextRequest()

        mockMvc.get("$ENDPOINT/${brand.id}")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `a name an admin changed shows up in the customer detail`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        mockMvc.put("$ADMIN_ENDPOINT/${brand.id}") {
            with(ADMIN)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "무신사"}"""
        }.andExpect { status { isOk() } }

        mockMvc.get("$ENDPOINT/${brand.id}")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.name") { value("무신사") }
            }
    }

    /**
     * 다음 요청이 새 영속성 컨텍스트에서 시작한 것처럼 만든다. 운영에서는 요청마다 컨텍스트가 새로 열리지만
     * MockMvc는 테스트 트랜잭션 안에서 돌아 앞선 요청이 남긴 엔티티가 1차 캐시에 그대로 있다.
     * 그러면 ID 조회가 SQL을 보내지 않아 [com.loopers.domain.brand.Brand]의 삭제 필터가 붙을 자리가 없다.
     */
    private fun startNextRequest() = entityManager.flushAndClear()
}
