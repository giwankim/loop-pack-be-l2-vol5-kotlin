package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.product.createProductAdminRegisterRequest
import com.loopers.domain.product.createProductAdminStockUpdateRequest
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
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.annotation.Transactional

/**
 * 롤백으로 정리되는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandAdminApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
@Transactional
class ProductAdminApiMockMvcTest(
    private val mvc: MockMvcTester,
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

        val body = assertThat(
            postProduct(brandId = brand.id, name = " 티셔츠 ", price = 12_000, stock = 7),
        ).hasStatus(HttpStatus.CREATED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        val id = body.extractingPath("$.data.id").asNumber().actual().toLong()
        body.extractingPath("$.data.brandId").isEqualToLong(brand.id)
        body.extractingPath("$.data.name").isEqualTo(" 티셔츠 ")
        body.extractingPath("$.data.price").isEqualTo(12_000)
        body.extractingPath("$.data.stock").isEqualTo(7)
        body.extractingPath("$.data.createdAt").isNotNull()
        body.extractingPath("$.data.updatedAt").isNotNull()

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).hasStatusOk().bodyJson()
        detail.extractingPath("$.data.id").isEqualToLong(id)
        detail.extractingPath("$.data.brandId").isEqualToLong(brand.id)
        detail.extractingPath("$.data.name").isEqualTo(" 티셔츠 ")
        detail.extractingPath("$.data.price").isEqualTo(12_000)
        detail.extractingPath("$.data.stock").isEqualTo(7)
    }

    @Test
    fun `registering under an unknown brand returns 404 and saves nothing`() {
        val body = assertThat(postProduct(brandId = 999L)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.BRAND_NOT_FOUND.message)

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a price above 1_000_000_000 won returns 400 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        val body = assertThat(postProduct(brandId = brand.id, price = 1_000_000_001)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("상품 가격")

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a blank name returns 400 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        val body = assertThat(postProduct(brandId = brand.id, name = "   ")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("상품 이름은 공백일 수 없습니다")

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering a negative stock returns 400 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        val body = assertThat(postProduct(brandId = brand.id, stock = -1)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("재고는 0 이상이어야 합니다")

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `registering as a user returns 403 and saves nothing`() {
        val brand = brandRepository.save(createBrand())

        assertThat(postProduct(brandId = brand.id, principal = USER)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(countProducts()).isZero()
    }

    @Test
    fun `getting an unknown product returns 404`() {
        val body = assertThat(mvc.get().uri("$ENDPOINT/999").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Not Found")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.PRODUCT_NOT_FOUND.message)
    }

    @Test
    fun `admin updates the name as sent and the price and the brand stays`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        val body = assertThat(putProduct(id, json = """{"name": " 후드티 ", "price": 25000}""")).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.id").isEqualToLong(id)
        body.extractingPath("$.data.brandId").isEqualToLong(brand.id)
        body.extractingPath("$.data.name").isEqualTo(" 후드티 ")
        body.extractingPath("$.data.price").isEqualTo(25_000)

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo(" 후드티 ")
    }

    @Test
    fun `a brandId in the update body is ignored and the product keeps its brand`() {
        val brand = brandRepository.save(createBrand())
        val other = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        // 수정 입력에 brandId가 없으므로 본문에 실어도 바인딩되지 않는다. Boot가 모르는 필드를 버리므로 거절도 아니다.
        val body = assertThat(
            putProduct(id, json = """{"name": "후드티", "price": 25000, "brandId": ${other.id}}"""),
        ).hasStatusOk().bodyJson()
        body.extractingPath("$.data.brandId").isEqualToLong(brand.id)
        body.extractingPath("$.data.name").isEqualTo("후드티")
    }

    @Test
    fun `an update rejected for its price returns 400 and a re-read shows the stored values`() {
        val brand = brandRepository.save(createBrand())
        val id =
            productService.register(createProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000)).id

        val body = assertThat(
            putProduct(id, json = """{"name": "후드티", "price": 0}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("상품 가격")

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo("티셔츠")
        detail.extractingPath("$.data.price").isEqualTo(12_000)
    }

    @Test
    fun `an update rejected for its name returns 400 and a re-read shows the stored values`() {
        val brand = brandRepository.save(createBrand())
        val id =
            productService.register(createProductAdminRegisterRequest(brandId = brand.id, name = "티셔츠", price = 12_000)).id

        val body = assertThat(
            putProduct(id, json = """{"name": "  ", "price": 25000}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("상품 이름")

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.name").isEqualTo("티셔츠")
        detail.extractingPath("$.data.price").isEqualTo(12_000)
    }

    @Test
    fun `admin sets the stock to a final quantity, zero included`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        val body = assertThat(putStock(id, quantity = 0)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.id").isEqualToLong(id)
        body.extractingPath("$.data.stock").isEqualTo(0)
    }

    @Test
    fun `a negative stock returns 400 and a re-read shows the stored stock`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id, stock = 7)).id

        val body = assertThat(putStock(id, quantity = -1)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("재고는 0 이상이어야 합니다")

        val detail = assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).bodyJson()
        detail.extractingPath("$.data.stock").isEqualTo(7)
    }

    @Test
    fun `admin deletes a product and it stops existing for every admin call`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        val body = assertThat(deleteProduct(id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.doesNotHavePath("$.data")

        // 운영에서는 요청마다 영속성 컨텍스트가 새로 열리지만 이 테스트는 한 트랜잭션을 나눠 쓴다.
        // `@SQLRestriction`은 SQL에만 붙으므로, 비우지 않으면 뒤따르는 조회가 1차 캐시에 남은 삭제된 상품을 그대로 받는다.
        entityManager.flushAndClear()

        assertThat(mvc.get().uri("$ENDPOINT/$id").with(ADMIN)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(putProduct(id, json = """{"name": "후드티", "price": 25000}""")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(putStock(id)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(deleteProduct(id)).hasStatus(HttpStatus.NOT_FOUND)
        val list = assertThat(getProducts("brandId" to brand.id.toString())).bodyJson()
        list.extractingPath("$.data.items").asArray().isEmpty()
    }

    @Test
    fun `updating, setting the stock of, and deleting an unknown product all return 404`() {
        val body = assertThat(
            putProduct(999L, json = """{"name": "후드티", "price": 25000}"""),
        ).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.PRODUCT_NOT_FOUND.message)
        assertThat(putStock(999L)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(deleteProduct(999L)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `admin lists the products of one brand as a latest-first slice`() {
        val brand = brandRepository.save(createBrand())
        val other = brandRepository.save(createBrand())
        val first = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id
        productService.register(createProductAdminRegisterRequest(brandId = other.id))
        val second = productService.register(createProductAdminRegisterRequest(brandId = brand.id, name = "후드티")).id

        val body = assertThat(
            getProducts("brandId" to brand.id.toString(), "page" to "0", "size" to "1"),
        ).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].id").isEqualToLong(second)
        body.extractingPath("$.data.items[0].name").isEqualTo("후드티")
        body.extractingPath("$.data.items[0].brandId").isEqualToLong(brand.id)
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(1)
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val list = assertThat(getProducts("brandId" to brand.id.toString(), "page" to "1", "size" to "1")).bodyJson()
        list.extractingPath("$.data.items[0].id").isEqualToLong(first)
        list.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing outside the page and size bounds returns 400`() {
        val body = assertThat(getProducts("page" to "-1")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("page는 0 이상이어야 합니다")

        val error = assertThat(getProducts("size" to "101")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").asString().contains("size는 100 이하여야 합니다")
    }

    @Test
    fun `listing without paging parameters falls back to the first slice of twenty`() {
        val brand = brandRepository.save(createBrand())
        productService.register(createProductAdminRegisterRequest(brandId = brand.id))

        val body = assertThat(getProducts("brandId" to brand.id.toString())).hasStatusOk().bodyJson()
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `writing as a user returns 403`() {
        val brand = brandRepository.save(createBrand())
        val id = productService.register(createProductAdminRegisterRequest(brandId = brand.id)).id

        assertThat(putProduct(id, json = """{"name": "후드티", "price": 25000}""", principal = USER)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(putStock(id, principal = USER)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(deleteProduct(id, principal = USER)).hasStatus(HttpStatus.FORBIDDEN)
    }

    /**
     * 본문은 Jackson 기본대로 읽어 숫자 문자열과 소수 표기의 정수를 받는다. 포인트·주문도 같다(포인트·주문 설계 5.10).
     * 이 테스트는 그 계약이 조용히 바뀌지 않게 붙들어 둔다.
     */
    @Test
    fun `registering still accepts a numeric string and a decimal notation for price and stock`() {
        val brand = brandRepository.save(createBrand())

        val body = assertThat(
            mvc.post().uri(ENDPOINT)
                .with(ADMIN)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"brandId": ${brand.id}, "name": "티셔츠", "price": "12000", "stock": 7.0}"""),
        ).hasStatus(HttpStatus.CREATED).bodyJson()
        body.extractingPath("$.data.price").isEqualTo(12_000)
        body.extractingPath("$.data.stock").isEqualTo(7)
    }

    private fun getProducts(vararg query: Pair<String, String>, principal: RequestPostProcessor? = ADMIN): MvcTestResult =
        mvc.get().uri(ENDPOINT)
            .apply { principal?.let { with(it) } }
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()

    private fun putProduct(productId: Long, json: String, principal: RequestPostProcessor? = ADMIN): MvcTestResult =
        mvc.put().uri("$ENDPOINT/$productId")
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()

    /** [quantity]가 null이면 fixture가 뽑은 수량을 싣는다. */
    private fun putStock(productId: Long, quantity: Int? = null, principal: RequestPostProcessor? = ADMIN): MvcTestResult {
        val request = createProductAdminStockUpdateRequest(quantity = quantity)
        return mvc.put().uri("$ENDPOINT/$productId/stock")
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"quantity": ${request.quantity}}""")
            .exchange()
    }

    private fun deleteProduct(productId: Long, principal: RequestPostProcessor? = ADMIN): MvcTestResult =
        mvc.delete().uri("$ENDPOINT/$productId")
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .exchange()

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
    ): MvcTestResult {
        val request = createProductAdminRegisterRequest(brandId = brandId, name = name, price = price, stock = stock)
        val json = """{"brandId": $brandId, "name": "${request.name}", "price": ${request.price}, "stock": ${request.stock}}"""
        return mvc.post().uri(ENDPOINT)
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()
    }

    /** 삭제되지 않은 상품 행 수. 엔티티의 SQL 제한이 JPQL에도 붙는다. */
    private fun countProducts(): Long =
        entityManager
            .createQuery("select count(p) from Product p", Long::class.java)
            .singleResult
}
