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
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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

/** No test transaction: every HTTP request commits or rolls back before the next request reads it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
class OrderApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val brandService: BrandService,
    private val productService: ProductService,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory,
) {
    companion object {
        @JvmStatic
        fun malformedBodies(): List<String> = listOf(
            "", "null", "[]", "true", "123", "\"text\"", "{", "{}", "{\"items\":null}",
            "{\"items\":{\"productId\":PRODUCT_ID,\"quantity\":1}}", "{\"items\":\"text\"}",
            "{\"items\":true}", "{\"items\":1}", "{\"items\":[null]}", "{\"items\":[1]}",
            "{\"items\":[[]]}", "{\"items\":[{}]}", "{\"items\":[{\"productId\":PRODUCT_ID}]}",
            "{\"items\":[{\"quantity\":1}]}",
        ) + listOf("null", "true", "[]", "{}", "\"1\"", "1.0", "1e0", "0", "-1", "9223372036854775808")
            .map { """{"items":[{"productId":$it,"quantity":1}]}""" } +
            listOf("null", "true", "[]", "{}", "\"1\"", "1.0", "1e0", "0", "-1", "2147483648", "9223372036854775808")
                .map { """{"items":[{"productId":PRODUCT_ID,"quantity":$it}]}""" }
    }

    private var userId = 0L
    private var brandId = 0L

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        userId = userRepository.save(User()).id
        brandId = brandService.register(BrandAdminRegisterRequest("주문 브랜드")).id
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `create merges items in product order and own detail preserves the committed draft`() {
        val first = product("티셔츠", 1_000, 0)
        val second = product("바지", 2_000, 1)
        val created = create(
            """{"items":[
                {"productId":$second,"quantity":1},
                {"productId":$first,"quantity":2},
                {"productId":$first,"quantity":3}
            ],"totalAmount":1}""",
        ).andExpect {
            status { isCreated() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.status") { value("DRAFT") }
            jsonPath("$.data.totalAmount") { value(7_000) }
            jsonPath("$.data.items.length()") { value(2) }
            jsonPath("$.data.items[0].productId") { value(first) }
            jsonPath("$.data.items[0].productName") { value("티셔츠") }
            jsonPath("$.data.items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.items[0].quantity") { value(5) }
            jsonPath("$.data.items[0].lineAmount") { value(5_000) }
            jsonPath("$.data.items[1].productId") { value(second) }
            jsonPath("$.data.paidAmount") { doesNotExist() }
            jsonPath("$.data.confirmedAt") { doesNotExist() }
        }.json()

        val detail = detail(created["data"]["orderId"].longValue()).andExpect { status { isOk() } }.json()
        assertThat(detail).isEqualTo(created)
        assertThat(productService.find(first).stock).isZero()
        assertThat(productService.find(second).stock).isEqualTo(1)
    }

    private fun product(name: String = "상품", price: Long = 1_000, stock: Int = 0): Long =
        productService.register(ProductAdminRegisterRequest(brandId, name, price, stock)).id

    @Test
    fun `equivalent creation replays the first response after catalog edits and deletion`() {
        val first = product("티셔츠")
        val second = product("바지", 2_000)
        val originalBody = """{"items":[{"productId":$second,"quantity":1},{"productId":$first,"quantity":5}]}"""
        val firstResponse = create(originalBody).andExpect { status { isCreated() } }.json()
        val orderId = firstResponse["data"]["orderId"].longValue()
        productService.update(first, ProductAdminUpdateRequest("새 이름", 2_000))
        productService.update(second, ProductAdminUpdateRequest("다른 이름", 1_000))
        assertThat(detail(orderId).json()).isEqualTo(firstResponse)

        productService.delete(first)
        productService.delete(second)
        brandService.delete(brandId)
        val splitBody = """{"items":[
            {"productId":$first,"quantity":2},
            {"productId":$second,"quantity":1},
            {"productId":$first,"quantity":3}
        ]}"""
        assertThat(create(splitBody).andExpect { status { isCreated() } }.json()).isEqualTo(firstResponse)
        assertThat(detail(orderId).andExpect { status { isOk() } }.json()).isEqualTo(firstResponse)

        create("""{"items":[{"productId":$first,"quantity":6}]}""").andExpect {
            status { isConflict() }
            jsonPath("$.meta.errorCode") { value("IDEMPOTENCY_KEY_CONFLICT") }
        }
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    fun `malformed requests are rejected without consuming a key`(body: String) {
        val id = product()
        create(body.replace("PRODUCT_ID", id.toString())).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
        }
        assertNoOrders()
        create(body(id)).andExpect { status { isCreated() } }
    }

    @Test
    fun `raw items count is checked before merging and one hundred entries are allowed`() {
        val id = product()
        create("""{"items":[]}""").andExpect { status { isBadRequest() } }
        val entries = List(101) { """{"productId":$id,"quantity":1}""" }
        create("""{"items":[${entries.joinToString()}]}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
        }
        assertNoOrders()
        create("""{"items":[${entries.take(100).joinToString()}]}""").andExpect {
            status { isCreated() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].quantity") { value(100) }
        }
    }

    @Test
    fun `invalid raw quantities cannot be hidden by merging and quantity overflow saves nothing`() {
        val id = product()
        listOf("0,1", "-1,2", "2147483647,1").forEach { quantities ->
            val entries = quantities.split(',').joinToString { """{"productId":$id,"quantity":$it}""" }
            create("""{"items":[$entries]}""").andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
            }
            assertNoOrders()
        }
        create(body(id, Int.MAX_VALUE)).andExpect {
            status { isCreated() }
            jsonPath("$.data.items[0].quantity") { value(Int.MAX_VALUE) }
            jsonPath("$.data.totalAmount") { value(2_147_483_647_000L) }
        }
    }

    @Test
    fun `order amount overflow rolls back every item and leaves the key reusable`() {
        val products = List(5) { product("상품$it", 1_000_000_000) }
        val entries = products.joinToString { """{"productId":$it,"quantity":2147483647}""" }
        create("""{"items":[$entries]}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
        }
        assertNoOrders()
        create(body(products.first())).andExpect { status { isCreated() } }
    }

    @Test
    fun `keys require exact ASCII format and allow both length boundaries`() {
        val request = body(product())
        listOf(null, "", " ", " key", "key ", "a.b", "한글", "a".repeat(129)).forEach { key ->
            create(request, key).andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("INVALID_IDEMPOTENCY_KEY") }
            }
            assertNoOrders()
        }
        create(request, "A").andExpect { status { isCreated() } }
        create(request, "aZ09-_" + "x".repeat(122)).andExpect { status { isCreated() } }
    }

    @Test
    fun `keys are case sensitive and scoped to the user`() {
        val request = body(product())
        val otherUser = userRepository.save(User()).id
        val upper = create(request, "Key").andExpect { status { isCreated() } }.json()
        val lower = create(request, "key").andExpect { status { isCreated() } }.json()
        val other = create(request, "Key", otherUser).andExpect { status { isCreated() } }.json()
        assertThat(listOf(upper, lower, other).map { it["data"]["orderId"].longValue() }).doesNotHaveDuplicates()
        assertThat(create(request, "Key").json()).isEqualTo(upper)
        assertThat(create(request, "key").json()).isEqualTo(lower)
        assertThat(create(request, "Key", otherUser).json()).isEqualTo(other)
    }

    @Test
    fun `missing and nonexistent requesters are unauthorized and other orders look missing`() {
        val request = body(product())
        listOf(null, Long.MAX_VALUE).forEach { requester ->
            create(request, requester = requester).andExpect {
                status { isUnauthorized() }
                jsonPath("$.meta.errorCode") { value("Unauthorized") }
            }
            detail(1, requester).andExpect { status { isUnauthorized() } }
            assertNoOrders()
        }
        val created = create(request).andExpect { status { isCreated() } }.json()
        val orderId = created["data"]["orderId"].longValue()
        val otherUser = userRepository.save(User()).id
        val missing = detail(Long.MAX_VALUE).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("ORDER_NOT_FOUND") }
        }.json()
        val forbidden = detail(orderId, otherUser).andExpect { status { isNotFound() } }.json()
        assertThat(forbidden).isEqualTo(missing)
        // A successful key still requires identity and raw input validation.
        create(request, requester = Long.MAX_VALUE).andExpect { status { isUnauthorized() } }
        create("""{"items":[{"productId":1,"quantity":0}]}""").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/orders/$orderId") { header(UserIdHeader.NAME, "abc") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
    }

    @Test
    fun `new orders reject unknown or deleted products and deleted brands without consuming keys`() {
        val active = product()
        val deleted = product("삭제할 상품")
        productService.delete(deleted)
        listOf(Long.MAX_VALUE, deleted).forEach { id ->
            create("""{"items":[{"productId":$active,"quantity":1},{"productId":$id,"quantity":1}]}""").andExpect {
                status { isNotFound() }
                jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
            }
            assertNoOrders()
        }
        // The catalog API prevents deleting a brand with active products; seed that legacy state directly.
        jdbc.update("update brand set deleted_at = current_timestamp(6) where id = ?", brandId)
        create(body(active)).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
        }
        assertNoOrders()
        brandId = brandService.register(BrandAdminRegisterRequest("새 브랜드")).id
        create(body(product())).andExpect { status { isCreated() } }
    }

    @Test
    fun `client supplied prices names and totals are ignored`() {
        val id = product("서버 이름", 1_000)
        create(
            """{
                "items":[{"productId":$id,"quantity":2,"productName":"가짜","unitPrice":1,"lineAmount":1}],
                "totalAmount":1,"status":"CONFIRMED","paidAmount":1
            }""",
        ).andExpect {
            status { isCreated() }
            jsonPath("$.data.status") { value("DRAFT") }
            jsonPath("$.data.items[0].productName") { value("서버 이름") }
            jsonPath("$.data.items[0].unitPrice") { value(1_000) }
            jsonPath("$.data.items[0].lineAmount") { value(2_000) }
            jsonPath("$.data.totalAmount") { value(2_000) }
            jsonPath("$.data.paidAmount") { doesNotExist() }
            jsonPath("$.data.userId") { doesNotExist() }
            jsonPath("$.data.creationKey") { doesNotExist() }
        }
    }

    @Test
    fun `line amount overflow is rejected with no partial order`() {
        val id = product()
        // Valid catalog prices cannot overflow one line; a DB fixture exercises the Long multiplication guard.
        jdbc.update("update product set price = ? where id = ?", Long.MAX_VALUE, id)
        create(body(id, 2)).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
        }
        assertNoOrders()
        jdbc.update("update product set price = 1000 where id = ?", id)
        create(body(id)).andExpect { status { isCreated() } }
    }

    @Test
    fun `persisted confirmed detail includes payment but creation replay stays the original draft`() {
        val request = body(product())
        val original = create(request).andExpect { status { isCreated() } }.json()
        val orderId = original["data"]["orderId"].longValue()
        // Persist the state shape for a future confirmation slice; this is not a confirmation operation.
        jdbc.update(
            "update orders set status = 'CONFIRMED', paid_amount = total_amount, " +
                "confirmed_at = '2026-09-18 00:00:00.123456' where id = ?",
            orderId,
        )
        detail(orderId).andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("CONFIRMED") }
            jsonPath("$.data.paidAmount") { value(1_000) }
            jsonPath("$.data.confirmedAt") { value("2026-09-18T00:00:00.123456Z") }
        }
        assertThat(create(request).andExpect { status { isCreated() } }.json()).isEqualTo(original)
    }

    @Test
    fun `foreign keys and user key and order product uniqueness are enforced by MySQL`() {
        val id = product()
        val original = create(body(id)).andExpect { status { isCreated() } }.json()
        val orderId = original["data"]["orderId"].longValue()
        val foreignKeys = jdbc.queryForList(
            "select concat(table_name, '.', column_name, '->', referenced_table_name) as reference_name " +
                "from information_schema.key_column_usage where table_schema = database() " +
                "and table_name in ('orders', 'order_line_item') and referenced_table_name is not null",
            String::class.java,
        )
        assertThat(foreignKeys).containsExactlyInAnyOrder(
            "orders.user_id->users",
            "order_line_item.order_id->orders",
            "order_line_item.product_id->product",
        )
        // 생성 키는 충전 키와 같은 열 정의를 쓴다(IdempotencyKey.COLUMN_DEFINITION, 설계 12.2).
        val keyColumn = jdbc.queryForMap(
            "select character_set_name, collation_name, character_maximum_length from information_schema.columns " +
                "where table_schema = database() and table_name = 'orders' and column_name = 'creation_key'",
        )
        assertThat(keyColumn).containsEntry("character_set_name", "utf8mb4")
            .containsEntry("collation_name", "utf8mb4_bin")
            .containsEntry("character_maximum_length", 128L)
        assertConstraint(
            "insert into orders (user_id, creation_key, status, total_amount, created_at) " +
                "values (?, 'bad-user', 'DRAFT', 1000, now(6))",
            Long.MAX_VALUE,
        )
        assertConstraint(
            "insert into orders (user_id, creation_key, status, total_amount, created_at) " +
                "values (?, 'create-1', 'DRAFT', 1000, now(6))",
            userId,
        )
        val insertItem = "insert into order_line_item (order_id, product_id, product_name, unit_price, quantity, line_amount) " +
            "values (?, ?, '상품', 1000, 1, 1000)"
        assertConstraint(insertItem, Long.MAX_VALUE, id)
        assertConstraint(insertItem, orderId, Long.MAX_VALUE)
        assertConstraint(insertItem, orderId, id)
        assertConstraint("delete from users where id = ?", userId)
        assertConstraint("delete from product where id = ?", id)
        assertConstraint("delete from orders where id = ?", orderId)
        assertThat(detail(orderId).json()).isEqualTo(original)
    }

    @Test
    fun `storage failure on the second item rolls back the order items and creation key`() {
        val first = product("첫 상품")
        val second = product("둘째 상품")
        val request = """{"items":[{"productId":$first,"quantity":1},{"productId":$second,"quantity":1}]}"""
        jdbc.execute("alter table order_line_item add constraint fail_second_order_item check (product_id <> $second)")
        try {
            create(request).andExpect { status { isInternalServerError() } }
            assertNoOrders()
        } finally {
            jdbc.execute("alter table order_line_item drop check fail_second_order_item")
        }
        create(request).andExpect { status { isCreated() } }
    }

    private fun assertConstraint(sql: String, vararg args: Any) {
        assertThatThrownBy { jdbc.update(sql, *args) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database rejects inconsistent payment state and nonpositive item values`() {
        val id = product()
        val original = create(body(id)).andExpect { status { isCreated() } }.json()
        val orderId = original["data"]["orderId"].longValue()
        listOf(
            "paid_amount = 1000",
            "confirmed_at = now(6)",
            "status = 'CONFIRMED'",
            "total_amount = 0",
            "status = 'CONFIRMED', paid_amount = 999, confirmed_at = now(6)",
            "status = 'CONFIRMED', paid_amount = null, confirmed_at = now(6)",
        ).forEach { update -> assertCheckConstraint("update orders set $update where id = ?", orderId) }
        listOf("quantity = 0", "unit_price = 0", "line_amount = 0").forEach { update ->
            assertCheckConstraint("update order_line_item set $update where order_id = ?", orderId)
        }
        assertThat(detail(orderId).json()).isEqualTo(original)
    }

    private fun assertCheckConstraint(sql: String, vararg args: Any) {
        val failure = assertThrows<UncategorizedSQLException> { jdbc.update(sql, *args) }
        assertThat(failure.sqlException!!.errorCode).isEqualTo(3819)
    }

    @Test
    fun `Hibernate schema recreation drops scalar references and recreates every order foreign key`() {
        create(body(product())).andExpect { status { isCreated() } }
        val schema = entityManagerFactory.unwrap(SessionFactory::class.java).schemaManager
        try {
            schema.dropMappedObjects(false)
            val remaining = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database() " +
                    "and table_name in ('orders', 'order_line_item', 'product', 'users')",
                String::class.java,
            )
            assertThat(remaining).isEmpty()
        } finally {
            schema.exportMappedObjects(false)
        }
        assertNoOrders()
        val constraints = jdbc.queryForList(
            "select constraint_name from information_schema.referential_constraints where constraint_schema = database() " +
                "and table_name in ('orders', 'order_line_item')",
            String::class.java,
        )
        assertThat(constraints).containsExactlyInAnyOrder(
            "fk_orders_user",
            "fk_order_line_item_order",
            "fk_order_line_item_product",
        )
        userId = userRepository.save(User()).id
        brandId = brandService.register(BrandAdminRegisterRequest("재생성 브랜드")).id
        create(body(product())).andExpect { status { isCreated() } }
    }

    private fun body(id: Long, quantity: Int = 1): String = """{"items":[{"productId":$id,"quantity":$quantity}]}"""

    private fun assertNoOrders() {
        assertThat(jdbc.queryForObject("select count(*) from orders", Long::class.java)!!).isZero()
        assertThat(jdbc.queryForObject("select count(*) from order_line_item", Long::class.java)!!).isZero()
    }

    private fun create(body: String, key: String? = "create-1", requester: Long? = userId): ResultActionsDsl =
        mockMvc.post("/api/v1/orders") {
            if (requester != null) header(UserIdHeader.NAME, requester)
            if (key != null) header(IdempotencyKeyHeader.NAME, key)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun detail(orderId: Long, requester: Long? = userId): ResultActionsDsl =
        mockMvc.get("/api/v1/orders/$orderId") { if (requester != null) header(UserIdHeader.NAME, requester) }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
