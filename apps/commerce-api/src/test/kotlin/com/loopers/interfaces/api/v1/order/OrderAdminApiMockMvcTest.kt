package com.loopers.interfaces.api.v1.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.application.product.ProductAdminUpdateRequest
import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.interfaces.api.IdempotencyKeyHeader
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * 관리자 주문 조회. 주문은 고객 API로 만들고 관리자 API로 읽으므로, 테스트 전체를 트랜잭션으로 감싸지 않는 까닭은
 * [OrderApiMockMvcTest]와 같다. 요청마다 서비스 트랜잭션이 끝나고 다음 요청은 새 영속성 컨텍스트에서 읽는다.
 *
 * 관리자 경계는 기존 테스트 전용 설정을 쓴다([AdminSecurityConfig]). 운영 인증 수단을 더하는 것이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
class OrderAdminApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val brandService: BrandService,
    private val productService: ProductService,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/orders"
        private val ADMIN = user("admin").roles("ADMIN")
        private val USER = user("user").roles("USER")
    }

    private var brandId = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        brandId = brandService.register(BrandAdminRegisterRequest("주문 브랜드")).id
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `admin lists the orders of every user latest first with the buyer id`() {
        val shirt = product("티셔츠", 1_000)
        val pants = product("바지", 2_000)
        val buyer = user()
        val otherBuyer = user()
        val first = createOrder(buyer, "create-1", items(pants to 1, shirt to 2))
        val second = createOrder(otherBuyer, "create-2", items(shirt to 1))

        getOrders().andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].orderId") { value(second) }
            jsonPath("$.data.items[0].userId") { value(otherBuyer) }
            jsonPath("$.data.items[1].orderId") { value(first) }
            jsonPath("$.data.items[1].userId") { value(buyer) }
            jsonPath("$.data.items[1].totalAmount") { value(4_000) }
            jsonPath("$.data.items[1].items.length()") { value(2) }
            jsonPath("$.data.items[1].items[0].productId") { value(shirt) }
            jsonPath("$.data.items[1].items[0].productName") { value("티셔츠") }
            jsonPath("$.data.items[1].items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.items[1].items[0].quantity") { value(2) }
            jsonPath("$.data.items[1].items[0].lineAmount") { value(2_000) }
            jsonPath("$.data.items[1].items[1].productId") { value(pants) }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `admin filters the list by the buyer and sees an empty slice for a buyer without orders`() {
        val product = product()
        val buyer = user()
        val otherBuyer = user()
        val quiet = user()
        val first = createOrder(buyer, "create-1", items(product to 1))
        createOrder(otherBuyer, "create-2", items(product to 1))
        val second = createOrder(buyer, "create-3", items(product to 2))

        getOrders("userId" to buyer.toString()).andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].orderId") { value(second) }
            jsonPath("$.data.items[0].userId") { value(buyer) }
            jsonPath("$.data.items[1].orderId") { value(first) }
            jsonPath("$.data.items[1].userId") { value(buyer) }
        }

        getOrders("userId" to quiet.toString()).andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(0) }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `admin reads any buyer's order detail and a missing order is not found`() {
        val shirt = product("티셔츠", 1_000)
        val buyer = user()
        val orderId = createOrder(buyer, "create-1", items(shirt to 3))

        getOrder(orderId).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.orderId") { value(orderId) }
            jsonPath("$.data.userId") { value(buyer) }
            jsonPath("$.data.status") { value("DRAFT") }
            jsonPath("$.data.totalAmount") { value(3_000) }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].productId") { value(shirt) }
            jsonPath("$.data.items[0].quantity") { value(3) }
            jsonPath("$.data.paidAmount") { doesNotExist() }
            jsonPath("$.data.confirmedAt") { doesNotExist() }
            jsonPath("$.data.creationKey") { doesNotExist() }
        }

        getOrder(Long.MAX_VALUE).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("ORDER_NOT_FOUND") }
        }
    }

    /**
     * 확정된 주문의 저장 형태를 DB fixture로 준비한다. 확정 동작 자체는 이 티켓의 책임이 아니며,
     * 관리자 조회가 저장된 결제 결과를 그대로 싣는지만 본다(설계 13의 같은 판단).
     */
    @Test
    fun `the list and the detail show the stored payment result of a confirmed order and omit it for a draft`() {
        val product = product("티셔츠", 1_000)
        val buyer = user()
        val draft = createOrder(buyer, "create-1", items(product to 1))
        val confirmed = createOrder(buyer, "create-2", items(product to 2))
        jdbc.update(
            "update orders set status = 'CONFIRMED', paid_amount = total_amount, " +
                "confirmed_at = '2026-09-18 00:00:00.123456' where id = ?",
            confirmed,
        )

        getOrders().andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].orderId") { value(confirmed) }
            jsonPath("$.data.items[0].status") { value("CONFIRMED") }
            jsonPath("$.data.items[0].paidAmount") { value(2_000) }
            jsonPath("$.data.items[0].confirmedAt") { value("2026-09-18T00:00:00.123456Z") }
            jsonPath("$.data.items[1].orderId") { value(draft) }
            jsonPath("$.data.items[1].status") { value("DRAFT") }
            jsonPath("$.data.items[1].paidAmount") { doesNotExist() }
            jsonPath("$.data.items[1].confirmedAt") { doesNotExist() }
        }

        getOrder(confirmed).andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("CONFIRMED") }
            jsonPath("$.data.paidAmount") { value(2_000) }
            jsonPath("$.data.confirmedAt") { value("2026-09-18T00:00:00.123456Z") }
        }
    }

    @Test
    fun `reading as a user or without identification returns 403`() {
        val buyer = user()
        val orderId = createOrder(buyer, "create-1", items(product() to 1))

        listOf(USER, null).forEach { principal ->
            getOrders(principal = principal).andExpect { status { isForbidden() } }
            getOrder(orderId, principal = principal).andExpect { status { isForbidden() } }
        }
    }

    @Test
    fun `listing outside the page and size bounds returns 400`() {
        getOrders("page" to "-1").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value(containsString("page는 0 이상이어야 합니다")) }
        }

        getOrders("size" to "0").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.message") { value(containsString("size는 1 이상이어야 합니다")) }
        }

        getOrders("size" to "101").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.message") { value(containsString("size는 100 이하여야 합니다")) }
        }
    }

    @Test
    fun `orders created in the same microsecond are listed with the later id first`() {
        val product = product()
        val buyer = user()
        val ids = (1..3).map { createOrder(buyer, "create-$it", items(product to it)) }
        jdbc.update("update orders set created_at = '2026-09-18 00:00:00.000000'")

        getOrders("page" to "0", "size" to "2").andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].orderId") { value(ids[2]) }
            jsonPath("$.data.items[1].orderId") { value(ids[1]) }
            jsonPath("$.data.hasNext") { value(true) }
        }

        getOrders("page" to "1", "size" to "2").andExpect {
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].orderId") { value(ids[0]) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    /** 한 조각이 세는 것은 주문이므로 품목이 많은 주문도 다음 주문을 밀어내지 않는다. */
    @Test
    fun `a multi item order fills one page entry and keeps all of its items`() {
        val products = List(3) { product("상품 $it", price = 1_000) }
        val buyer = user()
        val many = createOrder(buyer, "create-1", items(*products.map { it to 1 }.toTypedArray()))
        val one = createOrder(buyer, "create-2", items(products.first() to 1))

        getOrders("size" to "1").andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].orderId") { value(one) }
            jsonPath("$.data.hasNext") { value(true) }
        }

        getOrders("page" to "1", "size" to "1").andExpect {
            jsonPath("$.data.items[0].orderId") { value(many) }
            jsonPath("$.data.items[0].items.length()") { value(3) }
            products.sorted().forEachIndexed { index, productId ->
                jsonPath("$.data.items[0].items[$index].productId") { value(productId) }
            }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `catalog edits and soft deletion leave the stored order readable`() {
        val product = product("티셔츠", 1_000)
        val buyer = user()
        val orderId = createOrder(buyer, "create-1", items(product to 2))
        val before = getOrder(orderId).andExpect { status { isOk() } }.json()

        productService.update(product, ProductAdminUpdateRequest("새 이름", 9_000))
        productService.delete(product)
        brandService.delete(brandId)

        assertThat(getOrder(orderId).andExpect { status { isOk() } }.json()).isEqualTo(before)
        getOrders().andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].items[0].productName") { value("티셔츠") }
            jsonPath("$.data.items[0].items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.items[0].totalAmount") { value(2_000) }
        }
    }

    private fun product(name: String = "상품", price: Long = 1_000, stock: Int = 7): Long =
        productService.register(ProductAdminRegisterRequest(brandId, name, price, stock)).id

    private fun user(): Long = userRepository.save(User()).id

    /** 품목 요청 본문. 상품과 수량의 짝을 보낸 순서 그대로 싣는다. */
    private fun items(vararg products: Pair<Long, Int>): String =
        products.joinToString { (productId, quantity) -> """{"productId":$productId,"quantity":$quantity}""" }

    /** 고객 API로 주문을 만들고 그 식별자를 준다. 관리자 조회가 보는 것이 실제로 저장된 주문이어야 한다. */
    private fun createOrder(userId: Long, creationKey: String, items: String): Long =
        mockMvc.post("/api/v1/orders") {
            header(UserIdHeader.NAME, userId)
            header(IdempotencyKeyHeader.NAME, creationKey)
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[$items]}"""
        }.andExpect { status { isCreated() } }.json()["data"]["orderId"].longValue()

    private fun getOrders(vararg query: Pair<String, String>, principal: RequestPostProcessor? = ADMIN): ResultActionsDsl =
        mockMvc.get(ENDPOINT) {
            principal?.let { with(it) }
            query.forEach { (name, value) -> param(name, value) }
        }

    private fun getOrder(orderId: Long, principal: RequestPostProcessor? = ADMIN): ResultActionsDsl =
        mockMvc.get("$ENDPOINT/$orderId") { principal?.let { with(it) } }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
