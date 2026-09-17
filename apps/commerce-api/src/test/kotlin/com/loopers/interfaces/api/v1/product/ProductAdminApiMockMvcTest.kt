package com.loopers.interfaces.api.v1.product

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
 * 롤백으로 정리되는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandAdminApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class ProductAdminApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandService: BrandService,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")
        private val CUSTOMER = user("customer").roles("USER")
    }

    @Test
    fun `admin registers a product under a live brand and can fetch it back`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        val result = postProduct(brandId = brand.id, price = 12_000, stock = 7).andExpect {
            status { isCreated() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.id") { isNumber() }
            jsonPath("$.data.brandId") { value(brand.id) }
            jsonPath("$.data.name") { value("티셔츠") }
            jsonPath("$.data.price") { value(12_000) }
            jsonPath("$.data.stock") { value(7) }
            jsonPath("$.data.createdAt") { value(notNullValue()) }
            jsonPath("$.data.updatedAt") { value(notNullValue()) }
        }.andReturn()

        val id = JsonPath.read<Number>(result.response.contentAsString, "$.data.id").toLong()
        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.id") { value(id) }
                jsonPath("$.data.brandId") { value(brand.id) }
                jsonPath("$.data.name") { value("티셔츠") }
                jsonPath("$.data.price") { value(12_000) }
                jsonPath("$.data.stock") { value(7) }
            }
    }

    @Test
    fun `registering under an unknown brand returns 404 and saves nothing`() {
        postProduct(brandId = 999L).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Not Found") }
            jsonPath("$.meta.message") { value(ErrorType.BRAND_NOT_FOUND.message) }
        }

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a price above 1_000_000_000 won returns 400 and saves nothing`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        postProduct(brandId = brand.id, price = 1_000_000_001).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("상품 가격")) }
        }

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering as a customer returns 403 and saves nothing`() {
        val brand = brandService.register(BrandRegisterRequest("루퍼스"))

        postProduct(brandId = brand.id, principal = CUSTOMER).andExpect {
            status { isForbidden() }
        }

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `getting an unknown product returns 404`() {
        mockMvc.get("$ENDPOINT/999") { with(ADMIN) }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Not Found") }
                jsonPath("$.meta.message") { value(ErrorType.PRODUCT_NOT_FOUND.message) }
            }
    }

    /** 쓰기 요청이므로 거절 경로에서도 csrf 토큰을 넣는다. [principal]이 null이면 식별 없는 요청이다. */
    private fun postProduct(
        brandId: Long,
        price: Long = 12_000,
        stock: Int = 7,
        principal: RequestPostProcessor? = ADMIN,
    ) = mockMvc.post(ENDPOINT) {
        principal?.let { with(it) }
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = """{"brandId": $brandId, "name": " 티셔츠 ", "price": $price, "stock": $stock}"""
    }

    /** 삭제되지 않은 상품 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countProducts(): Long =
        entityManager
            .createQuery("select count(p) from Product p", Long::class.java)
            .singleResult
}
