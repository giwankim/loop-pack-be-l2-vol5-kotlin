package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderService
import com.loopers.application.point.PointService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.point.createPointChargeRequest
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.UserFixture
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
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
    private val mockMvc: MockMvc,
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
        val draft = detail(orderId).andExpect { status { isOk() } }.json()
        assertThat(draft["data"]["totalAmount"].longValue()).isEqualTo(7_000)
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 5)

        val before = Instant.now().minusSeconds(1)
        val confirmed = confirm(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("CONFIRMED") }
            jsonPath("$.data.paidAmount") { value(7_000) }
            jsonPath("$.data.totalAmount") { value(7_000) }
        }.json()

        assertThat(confirmed["data"]["items"]).isEqualTo(draft["data"]["items"])
        assertThat(confirmed["data"]["createdAt"]).isEqualTo(draft["data"]["createdAt"])
        assertThat(Instant.parse(confirmed["data"]["confirmedAt"].stringValue())).isBetween(before, Instant.now())
        balance(3_000)
        assertStock(first, 5)
        assertStock(second, 4)
        assertThat(detail(orderId).andExpect { status { isOk() } }.json()).isEqualTo(confirmed)
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
        val draft = detail(orderId).andExpect { status { isOk() } }.json()

        confirm(orderId).andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("INSUFFICIENT_STOCK") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 4)
        transaction.executeWithoutResult { productRepository.findById(second)!!.updateStock(5) }
        confirm(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.paidAmount") { value(7_000) }
        }
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
        val draft = detail(orderId).andExpect { status { isOk() } }.json()

        confirm(orderId).andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("INSUFFICIENT_POINTS") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(3_000)
        assertStock(first, 2)
        assertStock(second, 2)
        pointService.charge(userId, createPointChargeRequest(amount = 1_000))
        confirm(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.paidAmount") { value(4_000) }
        }
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

        val confirmed = confirm(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.paidAmount") { value(1_000) }
            jsonPath("$.data.items[0].productName") { value("원래 이름") }
            jsonPath("$.data.items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.createdAt") { value("2020-01-01T00:00:00.123456Z") }
        }.json()

        assertThat(detail(orderId).json()).isEqualTo(confirmed)
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
        val draft = detail(orderId).andExpect { status { isOk() } }.json()
        if (deleted == "product") {
            transaction.executeWithoutResult { productRepository.findById(second)!!.delete() }
        } else {
            // The catalog normally prevents this; exercise the same legacy state as OrderApiMockMvcTest.
            jdbc.update("update brand set deleted_at = now(6) where id = ?", secondBrand.id)
        }

        confirm(orderId).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
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
        val draft = detail(orderId).andExpect { status { isOk() } }.json()
        val (short, unavailable) = if (shortageFirst) first to second else second to first
        transaction.executeWithoutResult {
            productRepository.findById(short)!!.updateStock(1)
            productRepository.findById(unavailable)!!.delete()
        }

        confirm(orderId).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(10_000)
        assertStock(short, 1)
    }

    @Test
    fun `requester and ownership checks precede both first confirmation and the already confirmed rejection`() {
        pointService.charge(userId, createPointChargeRequest(amount = 1_000))
        val productId = productRepository.save(createProduct(brand, price = Money(1_000), stock = Stock(10))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 1)).orderId
        val draft = detail(orderId).andExpect { status { isOk() } }.json()
        val otherUser = userFixture.registerUser().id

        fun assertAccessDenied() {
            listOf(null, Long.MAX_VALUE).forEach { requester ->
                confirm(orderId, requester).andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.meta.errorCode") { value("Unauthorized") }
                }
            }
            val missing = confirm(Long.MAX_VALUE).andExpect {
                status { isNotFound() }
                jsonPath("$.meta.errorCode") { value("ORDER_NOT_FOUND") }
            }.json()
            assertThat(confirm(orderId, otherUser).andExpect { status { isNotFound() } }.json()).isEqualTo(missing)
        }

        assertAccessDenied()
        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(1_000)
        assertStock(productId, 10)
        val confirmed = confirm(orderId).andExpect { status { isOk() } }.json()
        assertAccessDenied()
        assertAlreadyConfirmed(orderId)
        assertThat(detail(orderId).json()).isEqualTo(confirmed)
        balance(0)
        assertStock(productId, 9)
    }

    /** 다시 확정하면 차감 없이 거절된다. 첫 확정의 결과는 GET으로 읽는다(ADR 0005). */
    private fun assertAlreadyConfirmed(orderId: Long) {
        confirm(orderId).andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("ORDER_ALREADY_CONFIRMED") }
        }
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
        val confirmed = confirm(firstId).andExpect { status { isOk() } }.json()
        val secondId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 2)).orderId
        confirm(secondId).andExpect { status { isOk() } }
        balance(0)
        assertStock(productId, 0)
        transaction.executeWithoutResult {
            productRepository.findById(productId)!!.delete()
            brandRepository.findById(brand.id)!!.delete()
        }

        repeat(2) {
            assertAlreadyConfirmed(firstId)
            assertThat(detail(firstId).andExpect { status { isOk() } }.json()).isEqualTo(confirmed)
        }

        balance(0)
        assertStock(productId, 0)
    }

    @Test
    fun `a missing point account is an internal error and rolls back stock without creating an account`() {
        userId = userFixture.registerUserWithoutAccount().id
        val productId = productRepository.save(createProduct(brand, stock = Stock(10))).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId), quantity = 1)).orderId
        val draft = detail(orderId).andExpect { status { isOk() } }.json()

        confirm(orderId).andExpect { status { isInternalServerError() } }

        assertThat(detail(orderId).json()).isEqualTo(draft)
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
        val draft = detail(orderId).andExpect { status { isOk() } }.json()
        jdbc.execute("alter table point_account add constraint fail_paid_balance check (balance <> 3000)")
        try {
            confirm(orderId).andExpect { status { isInternalServerError() } }
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
        assertThat(detail(orderId).andExpect { status { isOk() } }.json()).isEqualTo(draft)
        confirm(orderId).andExpect { status { isOk() } }
        balance(3_000)
        assertStock(first, 1)
        assertStock(second, 1)
    }

    private fun balance(expected: Long) {
        mockMvc.get("/api/v1/points") { header(UserIdHeader.NAME, userId) }.andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(expected) }
        }
    }

    private fun confirm(orderId: Long, requester: Long? = userId): ResultActionsDsl =
        mockMvc.post("/api/v1/orders/$orderId/confirm") { if (requester != null) header(UserIdHeader.NAME, requester) }

    private fun detail(orderId: Long): ResultActionsDsl = mockMvc.get(
        "/api/v1/orders/$orderId",
    ) { header(UserIdHeader.NAME, userId) }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
