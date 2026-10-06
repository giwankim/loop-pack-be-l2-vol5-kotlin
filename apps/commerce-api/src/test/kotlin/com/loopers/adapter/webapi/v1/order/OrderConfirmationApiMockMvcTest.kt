package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.order.OrderService
import com.loopers.application.point.PointService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.UserFixture
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.isEqualToLong
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** Each request ends its own transaction; read-back uses fresh persistence contexts. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
class OrderConfirmationApiMockMvcTest(
    private val mvc: MockMvcTester,
    private val objectMapper: ObjectMapper,
    private val userFixture: UserFixture,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val orderService: OrderService,
    private val pointService: PointService,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private var userId = 0L
    private lateinit var brand: Brand

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        userId = userFixture.registerUser().id
        brand = brandRepository.save(createBrand())
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `charging creating and confirming deduct stock and points once and read back the confirmed order`() {
        balance(0)
        pointService.charge(userId, createPointChargeRequest(amount = 10_000))
        val first = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(10))).id
        val second = productRepository.save(createProduct(brand, price = Money(2_000), stock = Stock(5))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(second to 1, first to 5)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()
        assertThat(draft.json()["data"]["totalAmount"].longValue()).isEqualTo(7_000)
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 5)

        val before = Instant.now().minusSeconds(1)
        val confirmed = confirm(orderId)
        val body = assertThat(confirmed).hasStatusOk().bodyJson()
        body.extractingPath("$.data.status").isEqualTo("CONFIRMED")
        body.extractingPath("$.data.paidAmount").isEqualTo(7_000)
        body.extractingPath("$.data.totalAmount").isEqualTo(7_000)

        assertThat(confirmed.json()["data"]["items"]).isEqualTo(draft.json()["data"]["items"])
        assertThat(confirmed.json()["data"]["createdAt"]).isEqualTo(draft.json()["data"]["createdAt"])
        assertThat(Instant.parse(confirmed.json()["data"]["confirmedAt"].stringValue())).isBetween(before, Instant.now())
        balance(3_000)
        assertStock(first, 5)
        assertStock(second, 4)
        val confirmedDetail = detail(orderId)
        assertThat(confirmedDetail).hasStatusOk()
        assertThat(confirmedDetail.json()).isEqualTo(confirmed.json())
        assertAlreadyConfirmed(orderId)
        balance(3_000)
        assertStock(first, 5)
        assertStock(second, 4)
    }

    @Test
    fun `a later item shortage rolls back all deductions and replenishment makes the same draft confirmable`() {
        pointService.charge(userId, createPointChargeRequest(amount = 10_000))
        val first = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(10))).id
        val second = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(4))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(first to 2, second to 5)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()

        val body = assertThat(confirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("INSUFFICIENT_STOCK")

        assertThat(detail(orderId).json()).isEqualTo(draft.json())
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 4)
        transaction.executeWithoutResult { productRepository.findById(second)!!.updateStock(5) }
        val confirmBody = assertThat(confirm(orderId)).hasStatusOk().bodyJson()
        confirmBody.extractingPath("$.data.paidAmount").isEqualTo(7_000)
        balance(3_000)
        assertStock(first, 8)
        assertStock(second, 0)
    }

    @Test
    fun `insufficient points rolls back every item and charging allows the same order to be confirmed`() {
        pointService.charge(userId, createPointChargeRequest(amount = 3_000))
        val first = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(2))).id
        val second = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(2))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(first, second), quantity = 2)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()

        val body = assertThat(confirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("INSUFFICIENT_POINTS")

        assertThat(detail(orderId).json()).isEqualTo(draft.json())
        balance(3_000)
        assertStock(first, 2)
        assertStock(second, 2)
        pointService.charge(userId, createPointChargeRequest(amount = 1_000))
        val confirmBody = assertThat(confirm(orderId)).hasStatusOk().bodyJson()
        confirmBody.extractingPath("$.data.paidAmount").isEqualTo(4_000)
        balance(0)
        assertStock(first, 0)
        assertStock(second, 0)
    }

    @ParameterizedTest
    @ValueSource(longs = [500, 2_000])
    fun `an old draft confirms at its saved price after a catalog price increase or decrease`(newPrice: Long) {
        pointService.charge(userId, createPointChargeRequest(amount = 1_000))
        val productId = productRepository.save(
            createProduct(brand, name = "원래 이름", price = Money(1_000), stock = Stock(10)),
        ).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 1)).orderId
        jdbc.update("update orders set created_at = '2020-01-01 00:00:00.123456' where id = ?", orderId)
        transaction.executeWithoutResult { productRepository.findById(productId)!!.update("바뀐 이름", Money(newPrice)) }

        val confirmed = confirm(orderId)
        val body = assertThat(confirmed).hasStatusOk().bodyJson()
        body.extractingPath("$.data.paidAmount").isEqualTo(1_000)
        body.extractingPath("$.data.items[0].productName").isEqualTo("원래 이름")
        body.extractingPath("$.data.items[0].unitPrice").isEqualTo(1_000)
        body.extractingPath("$.data.createdAt").isEqualTo("2020-01-01T00:00:00.123456Z")

        assertThat(detail(orderId).json()).isEqualTo(confirmed.json())
        balance(0)
        assertStock(productId, 9)
    }

    @ParameterizedTest
    @ValueSource(strings = ["product", "brand"])
    fun `unavailable products or brands reject the whole confirmation and preserve the draft`(deleted: String) {
        pointService.charge(userId, createPointChargeRequest(amount = 10_000))
        val first = productRepository.save(createProduct(brand, stock = Stock(10))).id
        val secondBrand = brandRepository.save(createBrand())
        val second = productRepository.save(createProduct(secondBrand, stock = Stock(10))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(first, second))).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()
        if (deleted == "product") {
            transaction.executeWithoutResult { productRepository.findById(second)!!.delete() }
        } else {
            // The catalog normally prevents this; exercise the same legacy state as OrderApiMockMvcTest.
            jdbc.update("update brand set deleted_at = now(6) where id = ?", secondBrand.id)
        }

        val body = assertThat(confirm(orderId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_PRODUCT_NOT_AVAILABLE")

        assertThat(detail(orderId).json()).isEqualTo(draft.json())
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 10)
    }

    /**
     * 품목은 상품 ID 오름차순이다. 판매 불가와 재고 부족을 함께 두고 둘의 차례를 바꿔 넣어, 응답하는 오류가
     * 품목의 차례가 아니라 확정의 검사 차례로 정해지는지 본다(설계 15).
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `an unavailable product outranks a shortage whichever item comes first`(shortageFirst: Boolean) {
        pointService.charge(userId, createPointChargeRequest(amount = 10_000))
        val first = productRepository.save(createProduct(brand)).id
        val second = productRepository.save(createProduct(brand)).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(first, second), quantity = 2)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()
        val (short, unavailable) = if (shortageFirst) first to second else second to first
        transaction.executeWithoutResult {
            productRepository.findById(short)!!.updateStock(1)
            productRepository.findById(unavailable)!!.delete()
        }

        val body = assertThat(confirm(orderId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_PRODUCT_NOT_AVAILABLE")

        assertThat(detail(orderId).json()).isEqualTo(draft.json())
        balance(10_000)
        assertStock(short, 1)
    }

    @Test
    fun `requester and ownership checks precede both first confirmation and the already confirmed rejection`() {
        pointService.charge(userId, createPointChargeRequest(amount = 1_000))
        val productId = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(10))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 1)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()
        val otherUser = userFixture.registerUser().id

        fun assertAccessDenied() {
            listOf(null, Long.MAX_VALUE).forEach { requester ->
                val body = assertThat(confirm(orderId, requester)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
                body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
            }
            val missing = confirm(Long.MAX_VALUE)
            val error = assertThat(missing).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
            error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")
            val forbidden = confirm(orderId, otherUser)
            assertThat(forbidden).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(forbidden.json()).isEqualTo(missing.json())
        }

        assertAccessDenied()
        assertThat(detail(orderId).json()).isEqualTo(draft.json())
        balance(1_000)
        assertStock(productId, 10)
        val confirmed = confirm(orderId)
        assertThat(confirmed).hasStatusOk()
        assertAccessDenied()
        assertAlreadyConfirmed(orderId)
        assertThat(detail(orderId).json()).isEqualTo(confirmed.json())
        balance(0)
        assertStock(productId, 9)
    }

    /** 다시 확정하면 차감 없이 거절된다. 첫 확정의 결과는 GET으로 읽는다(ADR 0005). */
    private fun assertAlreadyConfirmed(orderId: Long) {
        val body = assertThat(confirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_ALREADY_CONFIRMED")
    }

    /**
     * 확정할 수 있는지를 상품·재고·포인트보다 먼저 본다. 잔액을 다 쓰고 상품·브랜드가 삭제돼도 판매 불가나 잔액 부족이 아니라
     * 이미 확정된 주문이라는 거절이 나온다(ADR 0005).
     */
    @Test
    fun `re-confirming is rejected as already confirmed even after later spending and product and brand deletion`() {
        pointService.charge(userId, createPointChargeRequest(amount = 3_000))
        val productId = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(3))).id
        val firstId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 1)).orderId
        val confirmed = confirm(firstId)
        assertThat(confirmed).hasStatusOk()
        val secondId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 2)).orderId
        assertThat(confirm(secondId)).hasStatusOk()
        balance(0)
        assertStock(productId, 0)
        transaction.executeWithoutResult {
            productRepository.findById(productId)!!.delete()
            brandRepository.findById(brand.id)!!.delete()
        }

        repeat(2) {
            assertAlreadyConfirmed(firstId)
            val firstDetail = detail(firstId)
            assertThat(firstDetail).hasStatusOk()
            assertThat(firstDetail.json()).isEqualTo(confirmed.json())
        }

        balance(0)
        assertStock(productId, 0)
    }

    @Test
    fun `a missing point account is an internal error and rolls back stock without creating an account`() {
        userId = userFixture.registerUserWithoutAccount().id
        val productId = productRepository.save(createProduct(brand, stock = Stock(10))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 1)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()

        assertThat(confirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)

        assertThat(detail(orderId).json()).isEqualTo(draft.json())
        assertStock(productId, 10)
        assertThat(jdbc.queryForObject("select count(*) from point_account where user_id = ?", Long::class.java, userId)!!)
            .isZero()
    }

    private fun assertStock(productId: Long, expected: Int) {
        assertThat(jdbc.queryForObject("select stock_quantity from product where id = ?", Int::class.java, productId)!!)
            .isEqualTo(expected)
    }

    /**
     * 확정은 저장소를 부르지 않고 커밋의 flush로 쓴다. flush는 엔티티를 읽은 차례로 UPDATE를 보내므로 주문, 상품,
     * 포인트 계정 차례다. 마지막인 `point_account`의 UPDATE를 임시 CHECK로 거절해, 앞서 나간 주문·재고의 UPDATE까지
     * 함께 되돌아가는지 본다(설계 18.2).
     */
    @Test
    fun `a failure on the last write at commit rolls back the earlier stock and order writes and permits retry`() {
        pointService.charge(userId, createPointChargeRequest(amount = 10_000))
        val first = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(6))).id
        val second = productRepository.save(createProduct(brand, price = Money(2_000), stock = Stock(2))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(first to 5, second to 1)).orderId
        val draft = detail(orderId)
        assertThat(draft).hasStatusOk()
        jdbc.execute("alter table point_account add constraint fail_paid_balance check (balance <> 3000)")
        try {
            assertThat(confirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        } finally {
            jdbc.execute("alter table point_account drop check fail_paid_balance")
        }

        // A new transaction, outside the failed HTTP request, proves rollback rather than test cleanup.
        transaction.executeWithoutResult {
            assertStock(first, 6)
            assertStock(second, 2)
            assertThat(jdbc.queryForObject("select balance from point_account where user_id = ?", Long::class.java, userId)!!)
                .isEqualTo(10_000L)
            assertThat(jdbc.queryForMap("select status, paid_amount, confirmed_at from orders where id = ?", orderId))
                .containsAllEntriesOf(mapOf("status" to "DRAFT", "paid_amount" to null, "confirmed_at" to null))
        }
        val afterRollback = detail(orderId)
        assertThat(afterRollback).hasStatusOk()
        assertThat(afterRollback.json()).isEqualTo(draft.json())
        assertThat(confirm(orderId)).hasStatusOk()
        balance(3_000)
        assertStock(first, 1)
        assertStock(second, 1)
    }

    private fun balance(expected: Long) {
        val body = assertThat(mvc.get().uri("/api/v1/points").header(UserIdHeader.NAME, userId)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.balance").isEqualToLong(expected)
    }

    private fun confirm(orderId: Long, requester: Long? = userId): MvcTestResult =
        mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .apply { requester?.let { header(UserIdHeader.NAME, it) } }
            .exchange()

    private fun detail(orderId: Long): MvcTestResult =
        mvc.get().uri("/api/v1/orders/$orderId").header(UserIdHeader.NAME, userId).exchange()

    private fun MvcTestResult.json(): JsonNode = objectMapper.readTree(response.contentAsString)
}
