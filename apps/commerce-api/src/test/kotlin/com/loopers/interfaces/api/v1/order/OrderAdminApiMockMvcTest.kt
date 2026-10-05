package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.support.DatabaseCleanUp
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * 관리자 주문 조회. 주문은 [OrderService]로 만들고 관리자 API로 읽으므로, 테스트 전체를 트랜잭션으로 감싸지 않는 까닭은
 * [OrderApiMockMvcTest]와 같다. 준비와 요청마다 서비스 트랜잭션이 끝나고 다음 요청은 새 영속성 컨텍스트에서 읽는다.
 *
 * 관리자 경계는 기존 테스트 전용 설정을 쓴다([AdminSecurityConfig]). 운영 인증 수단을 더하는 것이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
class OrderAdminApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val orderService: OrderService,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/orders"
        private val ADMIN = user("admin").roles("ADMIN")
        private val USER = user("user").roles("USER")
    }

    private val transaction = TransactionTemplate(transactionManager)
    private lateinit var brand: Brand

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        brand = brandRepository.save(createBrand())
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `admin lists the orders of every user latest first with the ordering user id`() {
        val shirt = productRepository.save(createProduct(brand, name = "티셔츠", price = Money(1_000))).id
        val pants = productRepository.save(createProduct(brand, price = Money(2_000))).id
        val userId = userRepository.save(User()).id
        val otherUserId = userRepository.save(User()).id
        val first = orderService.create(userId, createOrderCreateRequest(pants to 1, shirt to 2)).orderId
        val second = orderService.create(otherUserId, createOrderCreateRequest(listOf(shirt))).orderId

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
        val shirt = productRepository.save(createProduct(brand)).id
        val socks = productRepository.save(createProduct(brand)).id
        val userId = userRepository.save(User()).id
        val otherUserId = userRepository.save(User()).id
        // 품목을 상품 ID의 거꾸로 넣는다. 응답이 넣은 차례 그대로면 품목의 차례를 확인한 것이 아니다.
        val older = orderService.create(userId, createOrderCreateRequest(listOf(socks, shirt))).orderId
        val newer = orderService.create(otherUserId, createOrderCreateRequest(listOf(shirt))).orderId

        val listed = getOrders().andExpect {
            status { isOk() }
            jsonPath("$.data.items.length()") { value(2) }
        }.json()["data"]["items"]

        assertThat(listed[0]).isEqualTo(getOrder(newer).andExpect { status { isOk() } }.json()["data"])
        assertThat(listed[1]).isEqualTo(getOrder(older).andExpect { status { isOk() } }.json()["data"])
    }

    @Test
    fun `admin filters the list by the user and sees an empty slice for a user without orders`() {
        val productId = productRepository.save(createProduct(brand)).id
        val userId = userRepository.save(User()).id
        val otherUserId = userRepository.save(User()).id
        val quietUserId = userRepository.save(User()).id
        val first = orderService.create(userId, createOrderCreateRequest(listOf(productId))).orderId
        orderService.create(otherUserId, createOrderCreateRequest(listOf(productId)))
        val second = orderService.create(userId, createOrderCreateRequest(listOf(productId))).orderId

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
        val request = createOrderCreateRequest(listOf(productRepository.save(createProduct(brand)).id))
        val userId = userRepository.save(User()).id
        val otherUserId = userRepository.save(User()).id
        val oldest = orderService.create(userId, request).orderId
        val foreignOlder = orderService.create(otherUserId, request).orderId
        val middle = orderService.create(userId, request).orderId
        val foreignNewer = orderService.create(otherUserId, request).orderId
        val newest = orderService.create(userId, request).orderId

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
        val shirt = productRepository.save(createProduct(brand, price = Money(1_000))).id
        val userId = userRepository.save(User()).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(shirt), quantity = 3)).orderId

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
        val productId = productRepository.save(createProduct(brand, price = Money(1_000))).id
        val userId = userRepository.save(User()).id
        val draft = orderService.create(userId, createOrderCreateRequest(listOf(productId))).orderId
        val confirmed = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 2)).orderId
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
        val userId = userRepository.save(User()).id
        val productId = productRepository.save(createProduct(brand)).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId))).orderId

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
        val request = createOrderCreateRequest(listOf(productRepository.save(createProduct(brand)).id))
        val userId = userRepository.save(User()).id
        val ids = List(3) { orderService.create(userId, request).orderId }
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
        val productIds = List(3) { productRepository.save(createProduct(brand)).id }
        val userId = userRepository.save(User()).id
        val many = orderService.create(userId, createOrderCreateRequest(productIds)).orderId
        val one = orderService.create(userId, createOrderCreateRequest(listOf(productIds.first()))).orderId

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
        val productId = productRepository.save(createProduct(brand, name = "티셔츠", price = Money(1_000))).id
        val userId = userRepository.save(User()).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 2)).orderId
        val before = getOrder(orderId).andExpect { status { isOk() } }.json()

        transaction.executeWithoutResult {
            productRepository.findById(productId)!!.apply { update("새 이름", Money(9_000)) }.delete()
            brandRepository.findById(brand.id)!!.delete()
        }

        assertThat(getOrder(orderId).andExpect { status { isOk() } }.json()).isEqualTo(before)
        getOrders().andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].items[0].productName") { value("티셔츠") }
            jsonPath("$.data.items[0].items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.items[0].totalAmount") { value(2_000) }
        }
    }

    private fun getOrders(vararg query: Pair<String, String>, principal: RequestPostProcessor? = ADMIN): ResultActionsDsl =
        mockMvc.get(ENDPOINT) {
            principal?.let { with(it) }
            query.forEach { (name, value) -> param(name, value) }
        }

    private fun getOrder(orderId: Long, principal: RequestPostProcessor? = ADMIN): ResultActionsDsl =
        mockMvc.get("$ENDPOINT/$orderId") { principal?.let { with(it) } }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
