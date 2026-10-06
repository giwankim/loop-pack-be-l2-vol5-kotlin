package com.loopers.adapter.webapi.v1.order

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.order.OrderService
import com.loopers.application.product.required.ProductRepository
import com.loopers.application.user.required.UserRepository
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.isEqualToLong
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
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
    private val mvc: MockMvcTester,
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

        val body = assertThat(getOrders()).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].orderId").isEqualToLong(second)
        body.extractingPath("$.data.items[0].userId").isEqualToLong(otherUserId)
        body.extractingPath("$.data.items[1].orderId").isEqualToLong(first)
        body.extractingPath("$.data.items[1].userId").isEqualToLong(userId)
        body.extractingPath("$.data.items[1].totalAmount").isEqualTo(4_000)
        body.extractingPath("$.data.items[1].items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[1].items[0].productId").isEqualToLong(shirt)
        body.extractingPath("$.data.items[1].items[0].productName").isEqualTo("티셔츠")
        body.extractingPath("$.data.items[1].items[0].unitPrice").isEqualTo(1_000)
        body.extractingPath("$.data.items[1].items[0].quantity").isEqualTo(2)
        body.extractingPath("$.data.items[1].items[0].lineAmount").isEqualTo(2_000)
        body.extractingPath("$.data.items[1].items[1].productId").isEqualToLong(pants)
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
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

        val orders = getOrders()
        val body = assertThat(orders).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        val listed = orders.json()["data"]["items"]

        val newerDetail = getOrder(newer)
        assertThat(newerDetail).hasStatusOk()
        assertThat(listed[0]).isEqualTo(newerDetail.json()["data"])
        val olderDetail = getOrder(older)
        assertThat(olderDetail).hasStatusOk()
        assertThat(listed[1]).isEqualTo(olderDetail.json()["data"])
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

        val body = assertThat(getOrders("userId" to userId.toString())).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].orderId").isEqualToLong(second)
        body.extractingPath("$.data.items[0].userId").isEqualToLong(userId)
        body.extractingPath("$.data.items[1].orderId").isEqualToLong(first)
        body.extractingPath("$.data.items[1].userId").isEqualToLong(userId)

        val list = assertThat(getOrders("userId" to quietUserId.toString())).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items").asArray().isEmpty()
        list.extractingPath("$.data.page").isEqualTo(0)
        list.extractingPath("$.data.size").isEqualTo(20)
        list.extractingPath("$.data.hasNext").isEqualTo(false)
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

        val firstPage = getOrders("userId" to userId.toString(), "size" to "2")
        val body = assertThat(firstPage).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(2)
        body.extractingPath("$.data.items[0].orderId").isEqualToLong(newest)
        body.extractingPath("$.data.items[1].orderId").isEqualToLong(middle)
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val secondPage = getOrders("userId" to userId.toString(), "page" to "1", "size" to "2")
        val list = assertThat(secondPage).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items.length()").isEqualTo(1)
        list.extractingPath("$.data.items[0].orderId").isEqualToLong(oldest)
        list.extractingPath("$.data.hasNext").isEqualTo(false)

        val listed = firstPage.json()["data"]["items"].toList() + secondPage.json()["data"]["items"].toList()
        assertThat(listed.map { it["orderId"].longValue() }).doesNotContain(foreignOlder, foreignNewer)
    }

    @Test
    fun `admin reads any user's order detail and a missing order is not found`() {
        val shirt = productRepository.save(createProduct(brand, price = Money(1_000))).id
        val userId = userRepository.save(User()).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(shirt), quantity = 3)).orderId

        val body = assertThat(getOrder(orderId)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.orderId").isEqualToLong(orderId)
        body.extractingPath("$.data.userId").isEqualToLong(userId)
        body.extractingPath("$.data.status").isEqualTo("DRAFT")
        body.extractingPath("$.data.totalAmount").isEqualTo(3_000)
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].productId").isEqualToLong(shirt)
        body.extractingPath("$.data.items[0].quantity").isEqualTo(3)
        body.doesNotHavePath("$.data.paidAmount")
        body.doesNotHavePath("$.data.confirmedAt")

        val error = assertThat(getOrder(Long.MAX_VALUE)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        error.extractingPath("$.meta.result").isEqualTo("FAIL")
        error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")
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

        val body = assertThat(getOrders()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].orderId").isEqualToLong(confirmed)
        body.extractingPath("$.data.items[0].status").isEqualTo("CONFIRMED")
        body.extractingPath("$.data.items[0].paidAmount").isEqualTo(2_000)
        body.extractingPath("$.data.items[0].confirmedAt").isEqualTo("2026-09-18T00:00:00.123456Z")
        body.extractingPath("$.data.items[1].orderId").isEqualToLong(draft)
        body.extractingPath("$.data.items[1].status").isEqualTo("DRAFT")
        body.doesNotHavePath("$.data.items[1].paidAmount")
        body.doesNotHavePath("$.data.items[1].confirmedAt")

        val detail = assertThat(getOrder(confirmed)).hasStatusOk().bodyJson()
        detail.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        detail.extractingPath("$.data.paidAmount").isEqualTo(2_000)
        detail.extractingPath("$.data.confirmedAt").isEqualTo("2026-09-18T00:00:00.123456Z")
    }

    @Test
    fun `reading as a user or without identification returns 403`() {
        val userId = userRepository.save(User()).id
        val productId = productRepository.save(createProduct(brand)).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId))).orderId

        listOf(USER, null).forEach { principal ->
            assertThat(getOrders(principal = principal)).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(getOrder(orderId, principal = principal)).hasStatus(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `listing outside the page and size bounds returns 400`() {
        val body = assertThat(getOrders("page" to "-1")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("page는 0 이상이어야 합니다")

        val error = assertThat(getOrders("size" to "0")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.message").asString().contains("size는 1 이상이어야 합니다")

        val secondError = assertThat(getOrders("size" to "101")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        secondError.extractingPath("$.meta.message").asString().contains("size는 100 이하여야 합니다")
    }

    @Test
    fun `orders created in the same microsecond are listed with the later id first`() {
        val request = createOrderCreateRequest(listOf(productRepository.save(createProduct(brand)).id))
        val userId = userRepository.save(User()).id
        val ids = List(3) { orderService.create(userId, request).orderId }
        jdbc.update("update orders set created_at = '2026-09-18 00:00:00.000000'")

        val body = assertThat(getOrders("page" to "0", "size" to "2")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].orderId").isEqualToLong(ids[2])
        body.extractingPath("$.data.items[1].orderId").isEqualToLong(ids[1])
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val list = assertThat(getOrders("page" to "1", "size" to "2")).bodyJson()
        list.extractingPath("$.data.items.length()").isEqualTo(1)
        list.extractingPath("$.data.items[0].orderId").isEqualToLong(ids[0])
        list.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    /** 한 조각이 세는 것은 주문이므로 품목이 많은 주문도 다음 주문을 밀어내지 않는다. */
    @Test
    fun `a multi item order fills one page entry and keeps all of its items`() {
        val productIds = List(3) { productRepository.save(createProduct(brand)).id }
        val userId = userRepository.save(User()).id
        val many = orderService.create(userId, createOrderCreateRequest(productIds)).orderId
        val one = orderService.create(userId, createOrderCreateRequest(listOf(productIds.first()))).orderId

        val body = assertThat(getOrders("size" to "1")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].orderId").isEqualToLong(one)
        body.extractingPath("$.data.hasNext").isEqualTo(true)

        val list = assertThat(getOrders("page" to "1", "size" to "1")).bodyJson()
        list.extractingPath("$.data.items[0].orderId").isEqualToLong(many)
        list.extractingPath("$.data.items[0].items.length()").isEqualTo(3)
        productIds.sorted().forEachIndexed { index, productId ->
            list.extractingPath("$.data.items[0].items[$index].productId").isEqualToLong(productId)
        }
        list.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `catalog edits and soft deletion leave the stored order readable`() {
        val productId = productRepository.save(createProduct(brand, name = "티셔츠", price = Money(1_000))).id
        val userId = userRepository.save(User()).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 2)).orderId
        val original = getOrder(orderId)
        assertThat(original).hasStatusOk()

        transaction.executeWithoutResult {
            productRepository.findById(productId)!!.apply { update("새 이름", Money(9_000)) }.delete()
            brandRepository.findById(brand.id)!!.delete()
        }

        val afterEdits = getOrder(orderId)
        assertThat(afterEdits).hasStatusOk()
        assertThat(afterEdits.json()).isEqualTo(original.json())
        val body = assertThat(getOrders()).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items[0].items[0].productName").isEqualTo("티셔츠")
        body.extractingPath("$.data.items[0].items[0].unitPrice").isEqualTo(1_000)
        body.extractingPath("$.data.items[0].totalAmount").isEqualTo(2_000)
    }

    private fun getOrders(vararg query: Pair<String, String>, principal: RequestPostProcessor? = ADMIN): MvcTestResult =
        mvc.get().uri(ENDPOINT)
            .apply { principal?.let { with(it) } }
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()

    private fun getOrder(orderId: Long, principal: RequestPostProcessor? = ADMIN): MvcTestResult =
        mvc.get().uri("$ENDPOINT/$orderId")
            .apply { principal?.let { with(it) } }
            .exchange()

    private fun MvcTestResult.json(): JsonNode = objectMapper.readTree(response.contentAsString)
}
