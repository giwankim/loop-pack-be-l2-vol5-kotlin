package com.loopers.adapter.webapi.v1.brand

import com.loopers.application.brand.BrandService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.createBrandAdminRegisterRequest
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MockMvcTester
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
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
@Transactional
class BrandApiMockMvcTest(
    private val mvc: MockMvcTester,
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
        val brand = brandService.register(createBrandAdminRegisterRequest(name = "루퍼스"))

        val body = assertThat(mvc.get().uri("$ENDPOINT/${brand.id}")).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.id").isEqualToLong(brand.id)
        body.extractingPath("$.data.name").isEqualTo("루퍼스")
        body.doesNotHavePath("$.data.createdAt")
        body.doesNotHavePath("$.data.updatedAt")
    }

    @Test
    fun `reading an unknown brand returns 404`() {
        val body = assertThat(mvc.get().uri("$ENDPOINT/999")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NOT_FOUND.message)
    }

    @Test
    fun `reading a deleted brand returns 404`() {
        val brand = brandService.register(createBrandAdminRegisterRequest())
        brandService.delete(brand.id)
        entityManager.flushAndClear()

        assertThat(mvc.get().uri("$ENDPOINT/${brand.id}")).hasStatus(HttpStatus.NOT_FOUND)
    }

    /**
     * 관리자가 바꾼 이름이 고객 상세에 보인다(#3의 인수 조건).
     *
     * 두 요청 사이를 비우는 까닭: 같은 트랜잭션이라 1차 캐시에 방금 이름을 바꾼 브랜드가 그대로 남아 있다.
     * 비우지 않으면 고객 조회가 SQL 대신 그 객체를 받아, 수정이 DB에 닿았는지와 무관하게 통과한다.
     */
    @Test
    fun `a name an admin changed shows up in the customer detail`() {
        val brand = brandService.register(createBrandAdminRegisterRequest())

        assertThat(
            mvc.put().uri("$ADMIN_ENDPOINT/${brand.id}")
                .with(ADMIN)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "무신사"}"""),
        ).hasStatusOk()
        entityManager.flushAndClear()

        val body = assertThat(mvc.get().uri("$ENDPOINT/${brand.id}")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.name").isEqualTo("무신사")
    }
}
