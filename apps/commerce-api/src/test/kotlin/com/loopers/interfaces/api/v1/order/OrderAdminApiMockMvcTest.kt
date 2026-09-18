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
import org.junit.jupiter.api.assertAll
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
    fun `admin lists the orders of every user latest first with the ordering user id`() {
        val shirt = productId("티셔츠", 1_000)
        val pants = productId("바지", 2_000)
        val userId = userId()
        val otherUserId = userId()
        val first = createOrder(userId, "create-1", items(pants to 1, shirt to 2))
        val second = createOrder(otherUserId, "create-2", items(shirt to 1))

        getOrders().andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].orderId") { value(second) }
            jsonPath("$.data.items[0].userId") { value(otherUserId) }
            jsonPath("$.data.items[1].orderId") { value(first) }
            jsonPath("$.data.items[1].userId") { value(userId) }
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

    /**
     * 목록의 항목은 상세와 같은 주문 응답이다. 필드를 하나씩 다시 세지 않고 상세의 JSON과 그대로 견준다.
     * 두 관리자 응답이 말없이 어긋날 수 없게 하려는 것이며, 고객 목록이 [OrderApiMockMvcTest]에서 보는 것과 같은 자리다.
     */
    @Test
    fun `the admin list entries are the same order responses as the detail`() {
        val shirt = productId("티셔츠", 1_000)
        val socks = productId("양말", 2_000)
        val userId = userId()
        val otherUserId = userId()
        // 품목을 상품 ID의 거꾸로 보낸다. 응답이 보낸 차례 그대로면 품목의 차례를 확인한 것이 아니다.
        val older = createOrder(userId, "create-1", items(socks to 1, shirt to 2))
        val newer = createOrder(otherUserId, "create-2", items(shirt to 1))

        val listed = getOrders().andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(2) }
        }.json()["data"]["items"]

        assertAll(
            { assertThat(listed[0]).isEqualTo(getOrder(newer).andExpect { status { isOk() } }.json()["data"]) },
            { assertThat(listed[1]).isEqualTo(getOrder(older).andExpect { status { isOk() } }.json()["data"]) },
        )
    }

    @Test
    fun `admin filters the list by the user and sees an empty slice for a user without orders`() {
        val productId = productId()
        val userId = userId()
        val otherUserId = userId()
        val quietUserId = userId()
        val first = createOrder(userId, "create-1", items(productId to 1))
        createOrder(otherUserId, "create-2", items(productId to 1))
        val second = createOrder(userId, "create-3", items(productId to 2))

        getOrders("userId" to userId.toString()).andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].orderId") { value(second) }
            jsonPath("$.data.items[0].userId") { value(userId) }
            jsonPath("$.data.items[1].orderId") { value(first) }
            jsonPath("$.data.items[1].userId") { value(userId) }
        }

        getOrders("userId" to quietUserId.toString()).andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(0) }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    /**
     * 거를 사용자를 넣은 조각도 주문 루트만 센다. 기본 크기로만 걸러 보면 조각을 만든 뒤에 거르는 구현도 통과하므로,
     * 쪽을 넘기는 자리에서 필터와 `hasNext`를 함께 본다. 두 사용자의 주문을 번갈아 만들어 거르지 않은 조각과
     * 거른 조각의 차례가 달라지게 한다(설계 16.1).
     */
    @Test
    fun `the admin list pages within one user's orders and never shows another user's`() {
        val productId = productId()
        val userId = userId()
        val otherUserId = userId()
        val oldest = createOrder(userId, "mine-1", items(productId to 1))
        val foreignOlder = createOrder(otherUserId, "theirs-1", items(productId to 1))
        val middle = createOrder(userId, "mine-2", items(productId to 1))
        val foreignNewer = createOrder(otherUserId, "theirs-2", items(productId to 1))
        val newest = createOrder(userId, "mine-3", items(productId to 1))

        val first = getOrders("userId" to userId.toString(), "size" to "2").andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].orderId") { value(newest) }
            jsonPath("$.data.items[1].orderId") { value(middle) }
            jsonPath("$.data.hasNext") { value(true) }
        }.json()["data"]["items"]

        val second = getOrders("userId" to userId.toString(), "page" to "1", "size" to "2").andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].orderId") { value(oldest) }
            jsonPath("$.data.hasNext") { value(false) }
        }.json()["data"]["items"]

        assertThat((first.toList() + second.toList()).map { it["orderId"].longValue() })
            .doesNotContain(foreignOlder, foreignNewer)
    }

    @Test
    fun `admin reads any user's order detail and a missing order is not found`() {
        val shirt = productId("티셔츠", 1_000)
        val userId = userId()
        val orderId = createOrder(userId, "create-1", items(shirt to 3))

        getOrder(orderId).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.orderId") { value(orderId) }
            jsonPath("$.data.userId") { value(userId) }
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
        val productId = productId("티셔츠", 1_000)
        val userId = userId()
        val draft = createOrder(userId, "create-1", items(productId to 1))
        val confirmed = createOrder(userId, "create-2", items(productId to 2))
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
        val userId = userId()
        val orderId = createOrder(userId, "create-1", items(productId() to 1))

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
        val productId = productId()
        val userId = userId()
        val ids = (1..3).map { createOrder(userId, "create-$it", items(productId to it)) }
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
        val productIds = List(3) { productId("상품 $it", price = 1_000) }
        val userId = userId()
        val many = createOrder(userId, "create-1", items(*productIds.map { it to 1 }.toTypedArray()))
        val one = createOrder(userId, "create-2", items(productIds.first() to 1))

        getOrders("size" to "1").andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].orderId") { value(one) }
            jsonPath("$.data.hasNext") { value(true) }
        }

        getOrders("page" to "1", "size" to "1").andExpect {
            jsonPath("$.data.items[0].orderId") { value(many) }
            jsonPath("$.data.items[0].items.length()") { value(3) }
            productIds.sorted().forEachIndexed { index, productId ->
                jsonPath("$.data.items[0].items[$index].productId") { value(productId) }
            }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    @Test
    fun `catalog edits and soft deletion leave the stored order readable`() {
        val productId = productId("티셔츠", 1_000)
        val userId = userId()
        val orderId = createOrder(userId, "create-1", items(productId to 2))
        val before = getOrder(orderId).andExpect { status { isOk() } }.json()

        productService.update(productId, ProductAdminUpdateRequest("새 이름", 9_000))
        productService.delete(productId)
        brandService.delete(brandId)

        assertThat(getOrder(orderId).andExpect { status { isOk() } }.json()).isEqualTo(before)
        getOrders().andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].items[0].productName") { value("티셔츠") }
            jsonPath("$.data.items[0].items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.items[0].totalAmount") { value(2_000) }
        }
    }

    private fun productId(name: String = "상품", price: Long = 1_000, stock: Int = 7): Long =
        productService.register(ProductAdminRegisterRequest(brandId, name, price, stock)).id

    private fun userId(): Long = userRepository.save(User()).id

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
