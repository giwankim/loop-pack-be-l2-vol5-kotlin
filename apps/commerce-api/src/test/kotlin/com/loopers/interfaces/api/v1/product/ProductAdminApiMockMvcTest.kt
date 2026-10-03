package com.loopers.interfaces.api.v1.product

import com.jayway.jsonpath.JsonPath
import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.support.error.ErrorType
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

/**
 * 롤백으로 정리되는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandAdminApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
@Transactional
class ProductAdminApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val brandRepository: BrandRepository,
    private val productService: ProductService,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")
        private val USER = user("user").roles("USER")
    }

    @Test
    fun `admin registers a product under an active brand and reads the name back as sent`() {
        val brand = brandRepository.save(createBrand())

        val result = postProduct(brandId = brand.id, name = " 티셔츠 ", price = 12_000, stock = 7).andExpect {
            status { isCreated() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.id") { isNumber() }
            jsonPath("$.data.brandId") { value(brand.id) }
            jsonPath("$.data.name") { value(" 티셔츠 ") }
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
                jsonPath("$.data.name") { value(" 티셔츠 ") }
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
        val brand = brandRepository.save(createBrand())

        postProduct(brandId = brand.id, price = 1_000_000_001).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("상품 가격")) }
        }

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a blank name returns 400 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        postProduct(brandId = brand.id, name = "   ").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("상품 이름은 공백일 수 없습니다")) }
        }

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a negative stock returns 400 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        postProduct(brandId = brand.id, stock = -1).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("재고는 0 이상이어야 합니다")) }
        }

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering as a user returns 403 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        postProduct(brandId = brand.id, principal = USER).andExpect {
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

    @Test
    fun `admin updates the name as sent and the price and the brand stays`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        putProduct(id, body = """{"name": " 후드티 ", "price": 25000}""").andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.id") { value(id) }
            jsonPath("$.data.brandId") { value(brand.id) }
            jsonPath("$.data.name") { value(" 후드티 ") }
            jsonPath("$.data.price") { value(25_000) }
        }

        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }
            .andExpect { jsonPath("$.data.name") { value(" 후드티 ") } }
    }

    @Test
    fun `a brandId in the update body is ignored and the product keeps its brand`() {
        val brand = brandRepository.save(createBrand())
        val other = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        // 수정 입력에 brandId가 없으므로 본문에 실어도 바인딩되지 않는다. Boot가 모르는 필드를 버리므로 거절도 아니다.
        putProduct(id, body = """{"name": "후드티", "price": 25000, "brandId": ${other.id}}""").andExpect {
            status { isOk() }
            jsonPath("$.data.brandId") { value(brand.id) }
            jsonPath("$.data.name") { value("후드티") }
        }
    }

    @Test
    fun `an update rejected for its price returns 400 and a re-read shows the stored values`() {
        val brand = brandRepository.save(createBrand())
        val id =
            productService.register(createProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000)).id

        putProduct(id, body = """{"name": "후드티", "price": 0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("상품 가격")) }
        }

        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }.andExpect {
            jsonPath("$.data.name") { value("티셔츠") }
            jsonPath("$.data.price") { value(12_000) }
        }
    }

    @Test
    fun `an update rejected for its name returns 400 and a re-read shows the stored values`() {
        val brand = brandRepository.save(createBrand())
        val id =
            productService.register(createProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000)).id

        putProduct(id, body = """{"name": "  ", "price": 25000}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("상품 이름")) }
        }

        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }.andExpect {
            jsonPath("$.data.name") { value("티셔츠") }
            jsonPath("$.data.price") { value(12_000) }
        }
    }

    @Test
    fun `admin sets the stock to a final quantity, zero included`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        putStock(id, quantity = 0).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.id") { value(id) }
            jsonPath("$.data.stock") { value(0) }
        }
    }

    @Test
    fun `a negative stock returns 400 and a re-read shows the stored stock`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id, stock = 7)).id

        putStock(id, quantity = -1).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("재고는 0 이상이어야 합니다")) }
        }

        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }
            .andExpect { jsonPath("$.data.stock") { value(7) } }
    }

    @Test
    fun `admin deletes a product and it stops existing for every admin call`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        deleteProduct(id).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data") { doesNotExist() }
        }

        // 운영에서는 요청마다 영속성 컨텍스트가 새로 열리지만 이 테스트는 한 트랜잭션을 나눠 쓴다.
        // `@SQLRestriction`은 SQL에만 붙으므로, 비우지 않으면 뒤따르는 조회가 1차 캐시에 남은 삭제된 상품을 그대로 받는다.
        entityManager.flushAndClear()

        mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }.andExpect { status { isNotFound() } }
        putProduct(id, body = """{"name": "후드티", "price": 25000}""").andExpect { status { isNotFound() } }
        putStock(id).andExpect { status { isNotFound() } }
        deleteProduct(id).andExpect { status { isNotFound() } }
        getProducts("brandId" to brand.id.toString())
            .andExpect { jsonPath("$.data.items") { isEmpty() } }
    }

    @Test
    fun `updating, setting the stock of, and deleting an unknown product all return 404`() {
        putProduct(999L, body = """{"name": "후드티", "price": 25000}""").andExpect {
            status { isNotFound() }
            jsonPath("$.meta.message") { value(ErrorType.PRODUCT_NOT_FOUND.message) }
        }
        putStock(999L).andExpect { status { isNotFound() } }
        deleteProduct(999L).andExpect { status { isNotFound() } }
    }

    @Test
    fun `admin lists the products of one brand as a latest-first slice`() {
        val brand = brandRepository.save(createBrand())
        val other = brandRepository.save(createBrand())
        val first = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id
        productService.register(createProductAdminRegisterRequest(brandId = other.id))
        val second = productService.register(createProductAdminRegisterRequest(brandId = brand.id, name = "후드티")).id

        getProducts("brandId" to brand.id.toString(), "page" to "0", "size" to "1").andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].id") { value(second) }
            jsonPath("$.data.items[0].name") { value("후드티") }
            jsonPath("$.data.items[0].brandId") { value(brand.id) }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(1) }
            jsonPath("$.data.hasNext") { value(true) }
        }

        getProducts("brandId" to brand.id.toString(), "page" to "1", "size" to "1").andExpect {
            jsonPath("$.data.items[0].id") { value(first) }
            jsonPath("$.data.hasNext") { value(false) }
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
    fun `listing without paging parameters falls back to the first slice of twenty`() {
        val brand = brandRepository.save(createBrand())
        productService.register(createProductAdminRegisterRequest(brandId = brand.id))

        getProducts("brandId" to brand.id.toString()).andExpect {
            status { isOk() }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `writing as a user returns 403`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        putProduct(id, body = """{"name": "후드티", "price": 25000}""", principal = USER)
            .andExpect { status { isForbidden() } }
        putStock(id, principal = USER).andExpect { status { isForbidden() } }
        deleteProduct(id, principal = USER).andExpect { status { isForbidden() } }
    }

    /**
     * 본문은 Jackson 기본대로 읽어 숫자 문자열과 소수 표기의 정수를 받는다. 포인트·주문도 같다(포인트·주문 설계 5.10).
     * 이 테스트는 그 계약이 조용히 바뀌지 않게 붙들어 둔다.
     */
    @Test
    fun `registering still accepts a numeric string and a decimal notation for price and stock`() {
        val brand = brandRepository.save(createBrand())

        mockMvc.post(ENDPOINT) {
            with(ADMIN)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"brandId": ${brand.id}, "name": "티셔츠", "price": "12000", "stock": 7.0}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.price") { value(12_000) }
            jsonPath("$.data.stock") { value(7) }
        }
    }

    private fun getProducts(vararg query: Pair<String, String>, principal: RequestPostProcessor? = ADMIN) =
        mockMvc.get(ENDPOINT) {
            principal?.let { with(it) }
            query.forEach { (name, value) -> param(name, value) }
        }

    private fun putProduct(productId: Long, body: String, principal: RequestPostProcessor? = ADMIN) =
        mockMvc.put("$ENDPOINT/$productId") {
            principal?.let { with(it) }
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    /** [quantity]가 null이면 fixture가 뽑은 수량을 싣는다. */
    private fun putStock(productId: Long, quantity: Int? = null, principal: RequestPostProcessor? = ADMIN): ResultActionsDsl {
        val request = createProductAdminStockUpdateRequest(quantity = quantity)
        return mockMvc.put("$ENDPOINT/$productId/stock") {
            principal?.let { with(it) }
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity": ${request.quantity}}"""
        }
    }

    private fun deleteProduct(productId: Long, principal: RequestPostProcessor? = ADMIN) =
        mockMvc.delete("$ENDPOINT/$productId") {
            principal?.let { with(it) }
            with(csrf())
        }

    /**
     * 쓰기 요청이므로 거절 경로에서도 csrf 토큰을 넣는다. [principal]이 null이면 식별 없는 요청이다.
     * 넘기지 않은 값은 fixture가 뽑는다. 본문의 필드 이름과 JSON 타입은 여기 손으로 적은 그대로다.
     */
    private fun postProduct(
        brandId: Long,
        name: String? = null,
        price: Long? = null,
        stock: Int? = null,
        principal: RequestPostProcessor? = ADMIN,
    ): ResultActionsDsl {
        val request = createProductAdminRegisterRequest(brandId = brandId, name = name, price = price, stock = stock)
        val body = """{"brandId": $brandId, "name": "${request.name}", "price": ${request.price}, "stock": ${request.stock}}"""
        return mockMvc.post(ENDPOINT) {
            principal?.let { with(it) }
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
    }

    /** 삭제되지 않은 상품 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countProducts(): Long =
        entityManager
            .createQuery("select count(p) from Product p", Long::class.java)
            .singleResult
}
