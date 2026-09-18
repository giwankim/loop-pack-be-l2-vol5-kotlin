package com.loopers.interfaces.api.v1.brand

import com.jayway.jsonpath.JsonPath
import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
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
        private val USER = user("user").roles("USER")
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
        brandService.register(BrandAdminRegisterRequest("루퍼스"))

        postBrand(name = "루퍼스").andExpect {
            status { isConflict() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Conflict") }
            jsonPath("$.meta.message") { value(ErrorType.BRAND_NAME_DUPLICATED.message) }
        }

        assertThat(countBrands()).isOne()
    }

    @Test
    fun `registering as a user returns 403 and saves nothing`() {
        postBrand(name = "루퍼스", principal = USER).andExpect {
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
    fun `getting a brand as a user returns 403`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        mockMvc.get("$ENDPOINT/${brand.id}") { with(USER) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `getting a brand anonymously returns 403`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        mockMvc.get("$ENDPOINT/${brand.id}")
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin lists live brands newest first as a slice`() {
        brandService.register(BrandAdminRegisterRequest("첫째"))
        brandService.register(BrandAdminRegisterRequest("둘째"))

        mockMvc.get(ENDPOINT) {
            with(ADMIN)
            param("page", "0")
            param("size", "1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].name") { value("둘째") }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(1) }
            jsonPath("$.data.hasNext") { value(true) }
        }
    }

    @Test
    fun `listing without paging parameters uses the first page of twenty`() {
        brandService.register(BrandAdminRegisterRequest("루퍼스"))

        mockMvc.get(ENDPOINT) { with(ADMIN) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.page") { value(0) }
                jsonPath("$.data.size") { value(20) }
                jsonPath("$.data.hasNext") { value(false) }
            }
    }

    @Test
    fun `listing a deleted brand leaves it out`() {
        val deleted = brandService.register(BrandAdminRegisterRequest("무신사"))
        brandService.register(BrandAdminRegisterRequest("루퍼스"))
        brandService.delete(deleted.id)

        mockMvc.get(ENDPOINT) { with(ADMIN) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.items.length()") { value(1) }
                jsonPath("$.data.items[0].name") { value("루퍼스") }
            }
    }

    /**
     * 범위는 `BrandAdminListRequest`의 제약이 거르고 메시지는 그 애노테이션에서 온다(설계 5.22).
     * 어긴 값마다 다른 메시지가 나오므로 어느 경계가 걸렸는지까지 확인한다.
     */
    @ParameterizedTest
    @CsvSource(
        "-1, 20, page는 0 이상이어야 합니다",
        "0, 0, size는 1 이상이어야 합니다",
        "0, 101, size는 100 이하여야 합니다",
    )
    fun `listing with a page or size outside the allowed range returns 400`(page: Int, size: Int, message: String) {
        mockMvc.get(ENDPOINT) {
            with(ADMIN)
            param("page", page.toString())
            param("size", size.toString())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString(message)) }
        }
    }

    @Test
    fun `listing as a user returns 403`() {
        mockMvc.get(ENDPOINT) { with(USER) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin renames a brand and reads the new name back`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        putBrand(brand.id, name = " 무신사 ").andExpect {
            status { isOk() }
            jsonPath("$.data.id") { value(brand.id) }
            jsonPath("$.data.name") { value("무신사") }
        }

        mockMvc.get("$ENDPOINT/${brand.id}") { with(ADMIN) }
            .andExpect { jsonPath("$.data.name") { value("무신사") } }
    }

    @Test
    fun `renaming to a name a live brand uses returns 409 and keeps the old name`() {
        brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val renamed = brandService.register(BrandAdminRegisterRequest("무신사"))

        putBrand(renamed.id, name = "루퍼스").andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("Conflict") }
            jsonPath("$.meta.message") { value(ErrorType.BRAND_NAME_DUPLICATED.message) }
        }

        mockMvc.get("$ENDPOINT/${renamed.id}") { with(ADMIN) }
            .andExpect { jsonPath("$.data.name") { value("무신사") } }
    }

    @Test
    fun `renaming to a blank name returns 400 and keeps the old name`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        putBrand(brand.id, name = "   ").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.message") { value(containsString("이름은 공백일 수 없습니다")) }
        }

        mockMvc.get("$ENDPOINT/${brand.id}") { with(ADMIN) }
            .andExpect { jsonPath("$.data.name") { value("루퍼스") } }
    }

    @Test
    fun `renaming to a name over a hundred chars returns 400 and keeps the old name`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        putBrand(brand.id, name = "가".repeat(101)).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.message") { value(containsString("100자 이하여야")) }
        }

        mockMvc.get("$ENDPOINT/${brand.id}") { with(ADMIN) }
            .andExpect { jsonPath("$.data.name") { value("루퍼스") } }
    }

    @Test
    fun `renaming an unknown brand returns 404`() {
        putBrand(999L, name = "루퍼스").andExpect {
            status { isNotFound() }
            jsonPath("$.meta.message") { value(ErrorType.BRAND_NOT_FOUND.message) }
        }
    }

    @Test
    fun `renaming a deleted brand returns 404`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        brandService.delete(brand.id)
        entityManager.flushAndClear()

        putBrand(brand.id, name = "무신사").andExpect { status { isNotFound() } }
    }

    @Test
    fun `renaming as a user returns 403 and keeps the old name`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        putBrand(brand.id, name = "무신사", principal = USER).andExpect { status { isForbidden() } }

        assertThat(brandService.find(brand.id).name).isEqualTo("루퍼스")
    }

    @Test
    fun `admin deletes a brand and it disappears from the detail and the list`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        deleteBrand(brand.id).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data") { doesNotExist() }
        }
        entityManager.flushAndClear()

        mockMvc.get("$ENDPOINT/${brand.id}") { with(ADMIN) }
            .andExpect { status { isNotFound() } }
        mockMvc.get(ENDPOINT) { with(ADMIN) }
            .andExpect { jsonPath("$.data.items.length()") { value(0) } }
        assertThat(countBrands()).isZero()
    }

    @Test
    fun `deleting a brand stamps the row instead of erasing it`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        deleteBrand(brand.id).andExpect { status { isOk() } }
        entityManager.flushAndClear()

        assertAll(
            { assertThat(brandRowExists(brand.id)).isTrue() },
            { assertThat(countStampedBrands(brand.id)).isOne() },
        )
    }

    @Test
    fun `deleting a brand twice returns 404 the second time`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        deleteBrand(brand.id).andExpect { status { isOk() } }
        entityManager.flushAndClear()

        deleteBrand(brand.id).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.message") { value(ErrorType.BRAND_NOT_FOUND.message) }
        }
    }

    @Test
    fun `deleting an unknown brand returns 404`() {
        deleteBrand(999L).andExpect { status { isNotFound() } }
    }

    @Test
    fun `deleting as a user returns 403 and keeps the brand`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))

        deleteBrand(brand.id, principal = USER).andExpect { status { isForbidden() } }

        assertThat(countBrands()).isOne()
    }

    /** 쓰기 요청이므로 거절 경로에서도 csrf 토큰을 넣는다. [principal]이 null이면 식별 없는 요청이다. */
    private fun postBrand(name: String, principal: RequestPostProcessor? = ADMIN) = mockMvc.post(ENDPOINT) {
        principal?.let { with(it) }
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": "$name"}"""
    }

    private fun putBrand(brandId: Long, name: String, principal: RequestPostProcessor? = ADMIN) =
        mockMvc.put("$ENDPOINT/$brandId") {
            principal?.let { with(it) }
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "$name"}"""
        }

    private fun deleteBrand(brandId: Long, principal: RequestPostProcessor? = ADMIN) =
        mockMvc.delete("$ENDPOINT/$brandId") {
            principal?.let { with(it) }
            with(csrf())
        }

    /** 삭제 시각과 상관없이 브랜드 행이 남아 있는지. 물리 삭제와 논리 삭제를 가른다. */
    private fun brandRowExists(brandId: Long): Boolean =
        countRawBrands("select count(*) from brand where id = :id", brandId) == 1L

    /** 삭제 시각이 찍힌 브랜드 행 수. */
    private fun countStampedBrands(brandId: Long): Long =
        countRawBrands("select count(*) from brand where id = :id and deleted_at is not null", brandId)

    /** 엔티티의 SQL 제한이 붙으면 삭제된 행이 보이지 않으므로, 삭제 여부를 직접 묻는 조회는 네이티브여야 한다. */
    private fun countRawBrands(sql: String, brandId: Long): Long =
        (
            entityManager
                .createNativeQuery(sql)
                .setParameter("id", brandId)
                .singleResult as Number
            ).toLong()

    /** 삭제되지 않은 브랜드 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countBrands(): Long =
        entityManager
            .createQuery("select count(b) from Brand b", Long::class.java)
            .singleResult
}
