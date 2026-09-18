package com.loopers.interfaces.api.v1.product

import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.support.error.ErrorType
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.containsString
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
 * 고객 상품 API. 식별 없이 부를 수 있어야 하므로 요청에 아무 principal도 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 정렬 자체와 동률, 삭제 필터는 [com.loopers.infrastructure.product.ProductRepositoryTest]가 SQL로 이미 고정한다.
 * 여기서는 그 위에서 쿼리 문자열이 실제로 기준까지 이어지는지와 응답 JSON의 모양만 본다(설계 6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class ProductApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandService: BrandService,
    private val productService: ProductService,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val ENDPOINT = "/api/v1/products"
        private const val ADMIN_ENDPOINT = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")
    }

    @Test
    fun `a customer reads a product without any identification`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val productId = registerProduct(brand.id, name = "티셔츠", price = 12_000, stock = 7)

        mockMvc.get("$ENDPOINT/$productId")
            .andExpect {
                status { isOk() }
                jsonPath("$.meta.result") { value("SUCCESS") }
                jsonPath("$.data.id") { value(productId) }
                jsonPath("$.data.name") { value("티셔츠") }
                jsonPath("$.data.price") { value(12_000) }
                jsonPath("$.data.soldOut") { value(false) }
                jsonPath("$.data.brand.id") { value(brand.id) }
                jsonPath("$.data.brand.name") { value("루퍼스") }
                jsonPath("$.data.likeCount") { value(0) }
            }
    }

    /** 고객은 남은 수량과 시각을 보지 않는다. 관리자 응답과 같은 [com.loopers.application.product.ProductInfo]에서 온다. */
    @Test
    fun `the customer detail leaves out the stock count and the timestamps`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val productId = registerProduct(brand.id, stock = 7)

        mockMvc.get("$ENDPOINT/$productId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.stock") { doesNotExist() }
                jsonPath("$.data.createdAt") { doesNotExist() }
                jsonPath("$.data.updatedAt") { doesNotExist() }
            }
    }

    @Test
    fun `reading an unknown product returns 404`() {
        mockMvc.get("$ENDPOINT/999")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Not Found") }
                jsonPath("$.meta.message") { value(ErrorType.PRODUCT_NOT_FOUND.message) }
            }
    }

    @Test
    fun `reading a deleted product returns 404`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val productId = registerProduct(brand.id)
        productService.delete(productId)
        entityManager.flushAndClear()

        mockMvc.get("$ENDPOINT/$productId").andExpect { status { isNotFound() } }
    }

    /**
     * 설계 문서의 대표 흐름이자 #7의 인수 조건. 관리자가 재고를 0으로 맞추면 고객 상세가 품절을 보인다.
     *
     * 두 요청 사이를 비우는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandApiMockMvcTest]와 같다.
     * 비우지 않으면 고객 조회가 방금 재고를 바꾼 객체를 1차 캐시에서 받아, 변경이 DB에 닿았는지와 무관하게 통과한다.
     */
    @Test
    fun `stock an admin set to zero shows up as sold out in the customer detail`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val productId = registerProduct(brand.id, stock = 7)

        mockMvc.get("$ENDPOINT/$productId").andExpect { jsonPath("$.data.soldOut") { value(false) } }

        mockMvc.put("$ADMIN_ENDPOINT/$productId/stock") {
            with(ADMIN)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity": 0}"""
        }.andExpect { status { isOk() } }
        entityManager.flushAndClear()

        mockMvc.get("$ENDPOINT/$productId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.soldOut") { value(true) }
                jsonPath("$.data.stock") { doesNotExist() }
            }
    }

    /**
     * 먼저 등록한 상품을 싸게 두어 기본값이 `latest`일 때와 `price_asc`일 때의 차례가 갈리게 한다.
     * 값이 같으면 두 기준이 같은 차례를 내놓아 기본값이 무엇이든 이 테스트가 지나간다.
     */
    @Test
    fun `a customer lists products latest registered first without asking for a sort`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val firstId = registerProduct(brand.id, name = "티셔츠", price = 3_000)
        val secondId = registerProduct(brand.id, name = "후드티", price = 30_000)
        entityManager.flushAndClear()

        getProducts().andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].id") { value(secondId) }
            jsonPath("$.data.items[1].id") { value(firstId) }
            jsonPath("$.data.items[0].brand.name") { value("루퍼스") }
            jsonPath("$.data.items[0].soldOut") { value(false) }
            jsonPath("$.data.items[0].likeCount") { value(0) }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    /** 싼 상품을 먼저 등록해 두 기준이 서로 다른 차례를 내놓게 한다. 같은 차례라면 기준이 닿았는지 알 수 없다. */
    @Test
    fun `the sort in the query string reaches the list order`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val cheapId = registerProduct(brand.id, name = "양말", price = 3_000)
        val dearId = registerProduct(brand.id, name = "코트", price = 30_000)
        entityManager.flushAndClear()

        getProducts("sort" to "price_asc").andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].id") { value(cheapId) }
            jsonPath("$.data.items[1].id") { value(dearId) }
        }

        getProducts("sort" to "latest").andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].id") { value(dearId) }
            jsonPath("$.data.items[1].id") { value(cheapId) }
        }
    }

    @Test
    fun `listing with a sort no product sort answers to returns 400`() {
        getProducts("sort" to "likes_desc").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(ErrorType.INVALID_SORT.message) }
        }
    }

    @Test
    fun `listing outside the page and size bounds returns 400`() {
        getProducts("page" to "-1").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("page는 0 이상이어야 합니다")) }
        }

        getProducts("size" to "101").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.message") { value(containsString("size는 100 이하여야 합니다")) }
        }
    }

    @Test
    fun `listing under an unknown brand is empty rather than not found`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        registerProduct(brand.id)
        entityManager.flushAndClear()

        getProducts("brandId" to "999").andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.items") { isEmpty() }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `listing keeps only the asked brand's products`() {
        val brand = brandService.register(BrandAdminRegisterRequest("루퍼스"))
        val other = brandService.register(BrandAdminRegisterRequest("나이키"))
        val mineId = registerProduct(brand.id, name = "티셔츠")
        registerProduct(other.id, name = "운동화")
        entityManager.flushAndClear()

        getProducts("brandId" to brand.id.toString()).andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].id") { value(mineId) }
        }
    }

    private fun getProducts(vararg query: Pair<String, String>) =
        mockMvc.get(ENDPOINT) {
            query.forEach { (name, value) -> param(name, value) }
        }

    private fun registerProduct(
        brandId: Long,
        name: String = "티셔츠",
        price: Long = 10_000,
        stock: Int = 1,
    ): Long =
        productService.register(
            ProductAdminRegisterRequest(brandId = brandId, name = name, price = price, stock = stock),
        ).id
}
