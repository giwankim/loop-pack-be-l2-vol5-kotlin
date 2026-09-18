package com.loopers.interfaces.api.v1.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandService
import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.application.product.ProductAdminStockUpdateRequest
import com.loopers.application.product.ProductAdminUpdateRequest
import com.loopers.application.product.ProductService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.point.PointHistoryRepository
import com.loopers.interfaces.api.IdempotencyKeyHeader
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.utils.DatabaseCleanUp
import com.loopers.utils.UserFixture
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** Each request ends its own transaction; read-back and replay use fresh persistence contexts. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
class OrderConfirmationApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userFixture: UserFixture,
    private val brandService: BrandService,
    private val productService: ProductService,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    @SpykBean
    private lateinit var pointHistoryRepository: PointHistoryRepository

    private val transaction = TransactionTemplate(transactionManager)
    private var userId = 0L
    private var brandId = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        userId = userFixture.registerUser().id
        brandId = brandService.register(BrandAdminRegisterRequest("주문 브랜드")).id
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `charge create confirm and read back persist one payment with the original charge and draft replays`() {
        balance(0)
        val charge = charge(10_000).andExpect { status { isOk() } }.json()
        val first = product("티셔츠", 1_000, 10)
        val second = product("바지", 2_000, 5)
        // Split lines must be paid and deducted using their summed quantity.
        val items = listOf(second to 1, first to 2, first to 3)
        val draft = create(items).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()
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
        assertThat(Instant.parse(confirmed["data"]["confirmedAt"].textValue())).isBetween(before, Instant.now())
        balance(3_000)
        assertStock(first, 5)
        assertStock(second, 4)
        assertThat(detail(orderId).andExpect { status { isOk() } }.json()).isEqualTo(confirmed)
        assertThat(confirm(orderId).andExpect { status { isOk() } }.json()).isEqualTo(confirmed)
        assertThat(create(items).andExpect { status { isCreated() } }.json()).isEqualTo(draft)
        assertThat(charge(10_000).andExpect { status { isOk() } }.json()).isEqualTo(charge)
        balance(3_000)
        assertStock(first, 5)
        assertStock(second, 4)
        val histories = jdbc.queryForList(
            "select type, amount, balance_after, charge_key, order_id from point_history order by id",
        )
        assertThat(histories).hasSize(2)
        assertThat(histories[0]).containsAllEntriesOf(
            mapOf(
                "type" to "CHARGE",
                "amount" to 10_000L,
                "balance_after" to 10_000L,
                "charge_key" to "charge-1",
                "order_id" to null,
            ),
        )
        assertThat(histories[1]).containsAllEntriesOf(
            mapOf(
                "type" to "PAYMENT",
                "amount" to 7_000L,
                "balance_after" to 3_000L,
                "charge_key" to null,
                "order_id" to orderId,
            ),
        )
    }

    private fun product(name: String = "상품", price: Long = 1_000, stock: Int = 10): Long =
        productService.register(ProductAdminRegisterRequest(brandId, name, price, stock)).id

    @Test
    fun `a later item shortage rolls back all deductions and replenishment makes the same draft confirmable`() {
        charge(10_000).andExpect { status { isOk() } }
        val first = product("첫 상품", stock = 10)
        val second = product("둘째 상품", stock = 4)
        val draft = create(listOf(first to 2, second to 2, second to 3)).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()

        confirm(orderId).andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("INSUFFICIENT_STOCK") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 4)
        assertPaymentCount(0)
        productService.updateStock(second, ProductAdminStockUpdateRequest(5))
        confirm(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.paidAmount") { value(7_000) }
        }
        balance(3_000)
        assertStock(first, 8)
        assertStock(second, 0)
        assertPaymentCount(1)
    }

    private fun assertPaymentCount(expected: Long) {
        assertThat(jdbc.queryForObject("select count(*) from point_history where type = 'PAYMENT'", Long::class.java)!!)
            .isEqualTo(expected)
    }

    @Test
    fun `insufficient points rolls back every item and charging allows the same order to be confirmed`() {
        charge(3_000).andExpect { status { isOk() } }
        val first = product("첫 상품", stock = 2)
        val second = product("둘째 상품", stock = 2)
        val draft = create(listOf(first to 2, second to 2)).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()

        confirm(orderId).andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("INSUFFICIENT_POINTS") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(3_000)
        assertStock(first, 2)
        assertStock(second, 2)
        assertPaymentCount(0)
        charge(1_000, "charge-2").andExpect { status { isOk() } }
        confirm(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.paidAmount") { value(4_000) }
        }
        balance(0)
        assertStock(first, 0)
        assertStock(second, 0)
        assertPaymentCount(1)
    }

    @ParameterizedTest
    @ValueSource(longs = [500, 2_000])
    fun `an old draft confirms at its saved price after a catalog price increase or decrease`(newPrice: Long) {
        charge(1_000).andExpect { status { isOk() } }
        val productId = product("원래 이름")
        val orderId = create(listOf(productId to 1)).andExpect { status { isCreated() } }.json()["data"]["orderId"].longValue()
        jdbc.update("update orders set created_at = '2020-01-01 00:00:00.123456' where id = ?", orderId)
        productService.update(productId, ProductAdminUpdateRequest("바뀐 이름", newPrice))

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
        assertPaymentCount(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["product", "brand"])
    fun `unavailable products or brands reject the whole confirmation and preserve the draft`(deleted: String) {
        charge(10_000).andExpect { status { isOk() } }
        val first = product("첫 상품")
        val secondBrand = brandService.register(BrandAdminRegisterRequest("둘째 브랜드")).id
        val second = productService.register(ProductAdminRegisterRequest(secondBrand, "둘째 상품", 1_000, 10)).id
        val draft = create(listOf(first to 1, second to 1)).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()
        if (deleted == "product") {
            productService.delete(second)
        } else {
            // The catalog normally prevents this; exercise the same legacy state as OrderApiMockMvcTest.
            jdbc.update("update brand set deleted_at = now(6) where id = ?", secondBrand)
        }

        confirm(orderId).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
        }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 10)
        assertPaymentCount(0)
    }

    @Test
    fun `requester and ownership checks precede both first confirmation and successful replay`() {
        charge(1_000).andExpect { status { isOk() } }
        val productId = product()
        val draft = create(listOf(productId to 1)).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()
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
        assertPaymentCount(0)
        val confirmed = confirm(orderId).andExpect { status { isOk() } }.json()
        assertAccessDenied()
        assertThat(detail(orderId).json()).isEqualTo(confirmed)
        balance(0)
        assertStock(productId, 9)
        assertPaymentCount(1)
    }

    @Test
    fun `successful confirmation replays after later spending and product and brand deletion`() {
        charge(3_000).andExpect { status { isOk() } }
        val productId = product(stock = 3)
        val first = create(listOf(productId to 1)).andExpect { status { isCreated() } }.json()
        val firstId = first["data"]["orderId"].longValue()
        val confirmed = confirm(firstId).andExpect { status { isOk() } }.json()
        val second = create(listOf(productId to 2), "create-2").andExpect { status { isCreated() } }.json()
        confirm(second["data"]["orderId"].longValue()).andExpect { status { isOk() } }
        balance(0)
        assertStock(productId, 0)
        productService.delete(productId)
        brandService.delete(brandId)

        repeat(2) {
            assertThat(confirm(firstId).andExpect { status { isOk() } }.json()).isEqualTo(confirmed)
            assertThat(detail(firstId).andExpect { status { isOk() } }.json()).isEqualTo(confirmed)
            assertThat(create(listOf(productId to 1)).andExpect { status { isCreated() } }.json()).isEqualTo(first)
        }

        balance(0)
        assertStock(productId, 0)
        assertPaymentCount(2)
        assertThat(jdbc.queryForObject("select count(*) from point_history", Long::class.java)!!).isEqualTo(3)
    }

    @Test
    fun `a missing point account is an internal error and rolls back stock without creating an account`() {
        userId = userFixture.registerUserWithoutAccount().id
        val productId = product()
        val draft = create(listOf(productId to 1)).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()

        confirm(orderId).andExpect { status { isInternalServerError() } }

        assertThat(detail(orderId).json()).isEqualTo(draft)
        assertStock(productId, 10)
        assertPaymentCount(0)
        assertThat(jdbc.queryForObject("select count(*) from point_account where user_id = ?", Long::class.java, userId)!!)
            .isZero()
    }

    private fun assertStock(productId: Long, expected: Int) {
        assertThat(jdbc.queryForObject("select stock_quantity from product where id = ?", Int::class.java, productId)!!)
            .isEqualTo(expected)
    }

    @Test
    fun `a late persistence failure rolls back flushed stock balance order and history and permits retry`() {
        charge(10_000).andExpect { status { isOk() } }
        val first = product("첫 상품", 1_000, 6)
        val second = product("둘째 상품", 2_000, 2)
        val draft = create(listOf(first to 5, second to 1)).andExpect { status { isCreated() } }.json()
        val orderId = draft["data"]["orderId"].longValue()
        var flushed: Map<String, Any?>? = null
        every { pointHistoryRepository.save(any()) } answers {
            callOriginal()
            entityManager.flush()
            // Observe actual SQL writes inside the request transaction before simulating the storage failure.
            assertStock(first, 1)
            assertStock(second, 1)
            flushed = jdbc.queryForMap(
                "select o.status, o.paid_amount, o.confirmed_at, a.balance, h.amount, h.balance_after " +
                    "from orders o join point_account a on a.user_id = o.user_id " +
                    "join point_history h on h.order_id = o.id where o.id = ?",
                orderId,
            )
            throw DataIntegrityViolationException("controlled failure after confirmation writes")
        }
        try {
            confirm(orderId).andExpect { status { isInternalServerError() } }
        } finally {
            every { pointHistoryRepository.save(any()) } answers { callOriginal() }
        }

        assertThat(flushed).isNotNull
        assertThat(flushed!!).containsAllEntriesOf(
            mapOf(
                "status" to "CONFIRMED",
                "paid_amount" to 7_000L,
                "balance" to 3_000L,
                "amount" to 7_000L,
                "balance_after" to 3_000L,
            ),
        )
        assertThat(flushed["confirmed_at"]).isNotNull()
        // A new transaction, outside the failed HTTP request, proves rollback rather than test cleanup.
        transaction.executeWithoutResult {
            assertStock(first, 6)
            assertStock(second, 2)
            assertThat(jdbc.queryForObject("select balance from point_account where user_id = ?", Long::class.java, userId)!!)
                .isEqualTo(10_000L)
            assertThat(jdbc.queryForMap("select status, paid_amount, confirmed_at from orders where id = ?", orderId))
                .containsAllEntriesOf(mapOf("status" to "DRAFT", "paid_amount" to null, "confirmed_at" to null))
            assertPaymentCount(0)
            assertThat(jdbc.queryForObject("select count(*) from point_history", Long::class.java)!!).isEqualTo(1)
        }
        assertThat(detail(orderId).andExpect { status { isOk() } }.json()).isEqualTo(draft)
        confirm(orderId).andExpect { status { isOk() } }
        balance(3_000)
        assertStock(first, 1)
        assertStock(second, 1)
        assertPaymentCount(1)
    }

    @Test
    fun `MySQL enforces the payment order foreign key uniqueness and charge or payment history shape`() {
        charge(3_000).andExpect { status { isOk() } }
        val productId = product()
        val orderIds = (1..2).map { index ->
            val id = create(listOf(productId to 1), "create-$index").andExpect { status { isCreated() } }
                .json()["data"]["orderId"].longValue()
            confirm(id).andExpect { status { isOk() } }
            id
        }
        val reference = jdbc.queryForMap(
            "select referenced_table_name, referenced_column_name from information_schema.key_column_usage " +
                "where table_schema = database() and table_name = 'point_history' and constraint_name = 'fk_point_history_order'",
        )
        assertThat(reference).containsAllEntriesOf(mapOf("referenced_table_name" to "orders", "referenced_column_name" to "id"))
        val deleteRule = jdbc.queryForObject(
            "select delete_rule from information_schema.referential_constraints " +
                "where constraint_schema = database() and constraint_name = 'fk_point_history_order'",
            String::class.java,
        )
        assertThat(deleteRule).isEqualTo("RESTRICT")
        val uniqueColumns = jdbc.queryForList(
            "select column_name from information_schema.statistics where table_schema = database() " +
                "and table_name = 'point_history' and index_name = 'uk_point_history_order_id' and non_unique = 0",
            String::class.java,
        )
        assertThat(uniqueColumns).containsExactly("order_id")

        assertThatThrownBy {
            jdbc.update(
            "update point_history set order_id = ? where order_id = ?",
            Long.MAX_VALUE,
            orderIds[0],
        )
        }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("update point_history set order_id = ? where order_id = ?", orderIds[0], orderIds[1]) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        listOf("charge_key = 'unexpected'", "order_id = null", "amount = 0", "balance_after = -1").forEach { update ->
            assertHistoryCheck("update point_history set $update where order_id = ?", orderIds[0])
        }
        assertHistoryCheck("update point_history set charge_key = null where type = 'CHARGE'")
        // Use an existing order without a PAYMENT so the CHECK, rather than the unique key, is the rejecting constraint.
        val draftId = create(listOf(productId to 1), "create-3").andExpect { status { isCreated() } }
            .json()["data"]["orderId"].longValue()
        assertHistoryCheck("update point_history set order_id = ? where type = 'CHARGE'", draftId)
        assertPaymentCount(2)
        charge(3_000).andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(3_000) }
        }
        balance(1_000)
    }

    private fun assertHistoryCheck(sql: String, vararg args: Any) {
        val failure = assertThrows<UncategorizedSQLException> { jdbc.update(sql, *args) }
        assertThat(failure.sqlException!!.errorCode).isEqualTo(3819)
    }

    private fun balance(expected: Long) {
        mockMvc.get("/api/v1/points") { header(UserIdHeader.NAME, userId) }.andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(expected) }
        }
    }

    private fun charge(amount: Long, key: String = "charge-1"): ResultActionsDsl = mockMvc.post("/api/v1/points/charge") {
        header(UserIdHeader.NAME, userId)
        header(IdempotencyKeyHeader.NAME, key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"amount":$amount}"""
    }

    private fun create(items: List<Pair<Long, Int>>, key: String = "create-1"): ResultActionsDsl = mockMvc.post(
        "/api/v1/orders",
    ) {
        header(UserIdHeader.NAME, userId)
        header(IdempotencyKeyHeader.NAME, key)
        contentType = MediaType.APPLICATION_JSON
        content = items.joinToString(prefix = """{"items":[""", postfix = "]}") { (id, quantity) ->
            """{"productId":$id,"quantity":$quantity}"""
        }
    }

    private fun confirm(orderId: Long, requester: Long? = userId): ResultActionsDsl =
        mockMvc.post("/api/v1/orders/$orderId/confirm") { if (requester != null) header(UserIdHeader.NAME, requester) }

    private fun detail(orderId: Long): ResultActionsDsl = mockMvc.get(
        "/api/v1/orders/$orderId",
    ) { header(UserIdHeader.NAME, userId) }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
