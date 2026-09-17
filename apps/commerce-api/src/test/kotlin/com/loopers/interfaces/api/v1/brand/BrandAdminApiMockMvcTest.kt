package com.loopers.interfaces.api.v1.brand

import com.jayway.jsonpath.JsonPath
import com.loopers.application.brand.BrandRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.support.error.ErrorType
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/**
 * MockMvc는 요청을 테스트 스레드에서 처리하므로 테스트 트랜잭션이 컨트롤러와 서비스까지 감싸고, 테스트마다 롤백으로 정리한다.
 * 실제 톰캣(RANDOM_PORT)을 띄우거나 다른 스레드가 끼어드는 순간 이 롤백은 요청 밖의 데이터를 잡지 못하므로,
 * 그때는 [com.loopers.utils.DatabaseCleanUp]으로 되돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class BrandAdminApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandService: BrandService,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/brands"
        private val ADMIN = user("admin").roles("ADMIN")
        private val CUSTOMER = user("customer").roles("USER")
    }

    @Test
    fun `admin registers a brand with a trimmed name and can fetch it back`() {
        val result = postBrand(name = " 루퍼스 ").andExpect {
            status { isCreated() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.id") { isNumber() }
            jsonPath("$.data.name") { value("루퍼스") }
            jsonPath("$.data.createdAt") { value(notNullValue()) }
            jsonPath("$.data.updatedAt") { value(notNullValue()) }
        }.andReturn()

        val id = JsonPath.read<Number>(result.response.contentAsString, "$.data.id").toLong()
        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.id") { value(id) }
                jsonPath("$.data.name") { value("루퍼스") }
            }
    }

    @Test
    fun `registering a blank name returns 400 and saves nothing`() {
        postBrand(name = "   ").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("이름은 공백일 수 없습니다")) }
        }

        assertThat(countBrands()).isZero()
    }

    @Test
    fun `registering a name taken by a live brand returns 409 and saves nothing`() {
        brandService.register(BrandRegisterRequest("루퍼스"))

        postBrand(name = "루퍼스").andExpect {
            status { isConflict() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Conflict") }
            jsonPath("$.meta.message") { value(ErrorType.BRAND_NAME_DUPLICATED.message) }
        }

        assertThat(countBrands()).isOne()
    }

    @Test
    fun `registering as a customer returns 403 and saves nothing`() {
        postBrand(name = "루퍼스", principal = CUSTOMER).andExpect {
            status { isForbidden() }
        }

        assertThat(countBrands()).isZero()
    }

    @Test
    fun `registering anonymously returns 403 and saves nothing`() {
        postBrand(name = "루퍼스", principal = null).andExpect {
            status { isForbidden() }
        }

        assertThat(countBrands()).isZero()
    }

    @Test
    fun `getting an unknown brand returns 404`() {
        mockMvc.get("$ENDPOINT/999") { with(ADMIN) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Not Found") }
                jsonPath("$.meta.message") { value(ErrorType.BRAND_NOT_FOUND.message) }
            }
    }

    @Test
    fun `getting a brand as a customer returns 403`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        mockMvc.get("$ENDPOINT/${brand.id}") { with(CUSTOMER) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `getting a brand anonymously returns 403`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        mockMvc.get("$ENDPOINT/${brand.id}")
            .andExpect { status { isForbidden() } }
    }

    /** 쓰기 요청이므로 거절 경로에서도 csrf 토큰을 넣는다. [principal]이 null이면 식별 없는 요청이다. */
    private fun postBrand(name: String, principal: RequestPostProcessor? = ADMIN) = mockMvc.post(ENDPOINT) {
        principal?.let { with(it) }
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": "$name"}"""
    }

    /** 삭제되지 않은 브랜드 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countBrands(): Long =
        entityManager
            .createQuery("select count(b) from Brand b", Long::class.java)
            .singleResult
}
