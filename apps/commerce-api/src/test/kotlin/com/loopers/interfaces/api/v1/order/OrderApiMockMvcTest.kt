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
import org.hamcrest.Matchers.containsString
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
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
        /** 본문을 읽을 수조차 없는 것. 역직렬화기가 판단할 기회가 없어 Spring·Jackson의 범용 400이다(설계 13.1). */
        @JvmStatic
        fun unreadableBodies(): List<String> = listOf("", "null", "{")

        /** 역직렬화기가 토큰과 컨테이너의 모양을 보고 거절하는 것. 양수 조건은 Request 제약이라 여기 없다. */
        @JvmStatic
        fun malformedBodies(): List<String> = listOf(
            "[]", "true", "123", "\"text\"", "{}", "{\"items\":null}",
            "{\"items\":{\"productId\":PRODUCT_ID,\"quantity\":1}}", "{\"items\":\"text\"}",
            "{\"items\":true}", "{\"items\":1}", "{\"items\":[null]}", "{\"items\":[1]}",
            "{\"items\":[[]]}", "{\"items\":[{}]}", "{\"items\":[{\"productId\":PRODUCT_ID}]}",
            "{\"items\":[{\"quantity\":1}]}",
        ) + listOf("null", "true", "[]", "{}", "\"1\"", "1.0", "1e0", "9223372036854775808")
            .map { """{"items":[{"productId":$it,"quantity":1}]}""" } +
            listOf("null", "true", "[]", "{}", "\"1\"", "1.0", "1e0", "2147483648", "9223372036854775808")
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

    @ParameterizedTest
    @MethodSource("unreadableBodies")
    fun `unreadable bodies are rejected without consuming a key`(body: String) {
        val id = product()
        create(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
        assertNoOrders()
        create(body(id)).andExpect { status { isCreated() } }
    }

    @Test
    fun `raw items count is checked before merging and one hundred entries are allowed`() {
        val id = product()
        listOf(0, 101).forEach { count ->
            val raw = List(count) { """{"productId":$id,"quantity":1}""" }
            create("""{"items":[${raw.joinToString()}]}""").andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value("주문 품목은 1개 이상 100개 이하여야 합니다.") }
            }
            assertNoOrders()
        }
        val entries = List(101) { """{"productId":$id,"quantity":1}""" }
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
        // 원본 값의 양수 조건은 Request 제약이라 범용 400 + 규칙 메시지다(설계 12.4, 13.1).
        listOf("0,1", "-1,2").forEach { quantities ->
            create(items(id, quantities)).andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value("수량은 1개 이상이어야 합니다.") }
            }
            assertNoOrders()
        }
        listOf(0, -1).forEach { productId ->
            create("""{"items":[{"productId":$productId,"quantity":1}]}""").andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value("상품 ID는 1 이상이어야 합니다.") }
            }
            assertNoOrders()
        }
        // 합산 넘침은 정규화가 거르므로 주문 전용 code가 남는다.
        create(items(id, "2147483647,1")).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
        }
        assertNoOrders()
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
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("금액 계산 결과가 표현 범위를 넘습니다.") }
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
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("금액 계산 결과가 표현 범위를 넘습니다.") }
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

    /**
     * 목록의 항목은 상세와 같은 주문 응답이다. 필드를 하나씩 다시 세지 않고 상세의 JSON과 그대로 견준다.
     * 두 응답이 말없이 어긋날 수 없게 하려는 것이며, 스냅샷이 카탈로그의 변경을 따라가지 않는 것도 함께 본다(ADR 0002).
     */
    @Test
    fun `the order list returns only the requester's orders from the newest with the same entries as the detail`() {
        val shirt = product("티셔츠", 1_000)
        val socks = product("양말", 2_000)
        // 품목을 상품 ID의 거꾸로 보낸다. 응답이 보낸 차례 그대로면 품목의 차례를 확인한 것이 아니다.
        val older = create("""{"items":[{"productId":$socks,"quantity":1},{"productId":$shirt,"quantity":2}]}""", "older")
            .andExpect { status { isCreated() } }.json()["data"]
        val newer = create(body(socks), "newer").andExpect { status { isCreated() } }.json()["data"]
        val otherUser = userRepository.save(User()).id
        val foreign = create(body(shirt), "foreign", otherUser).andExpect { status { isCreated() } }
            .json()["data"]["orderId"].longValue()
        productService.update(shirt, ProductAdminUpdateRequest("바뀐 이름", 9_000))
        productService.delete(socks)

        val listed = list().andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
            jsonPath("$.data.items.length()") { value(2) }
        }.json()["data"]["items"]

        assertAll(
            { assertThat(listed[0]).isEqualTo(newer) },
            { assertThat(listed[1]).isEqualTo(older) },
            { assertThat(listed[1]["items"][0]["productName"].textValue()).isEqualTo("티셔츠") },
            { assertThat(listed[1]["items"][0]["unitPrice"].longValue()).isEqualTo(1_000) },
            { assertThat(listed[1]["items"][0]["quantity"].intValue()).isEqualTo(2) },
            { assertThat(listed[1]["items"][1]["productId"].longValue()).isEqualTo(socks) },
            { assertThat(listed[1]["totalAmount"].longValue()).isEqualTo(4_000) },
            { assertThat(listed.map { it["orderId"].longValue() }).doesNotContain(foreign) },
            {
                val foreignList = list(requester = otherUser).json()["data"]["items"]
                assertThat(foreignList.single()["orderId"].longValue()).isEqualTo(foreign)
            },
        )
    }

    /** 저장된 두 상태가 목록에서도 상세와 같은 모양이다. 확정 동작은 후속 티켓의 책임이라 상태를 DB fixture로 만든다(설계 13). */
    @Test
    fun `the order list shows draft and confirmed entries with their stored payment fields`() {
        val id = product()
        create(body(id), "draft").andExpect { status { isCreated() } }
        val confirmed = create(body(id, 2), "confirmed").andExpect { status { isCreated() } }
            .json()["data"]["orderId"].longValue()
        jdbc.update(
            "update orders set status = 'CONFIRMED', paid_amount = total_amount, " +
                "confirmed_at = '2026-09-18 00:00:00.123456' where id = ?",
            confirmed,
        )

        list().andExpect {
            status { isOk() }
            jsonPath("$.data.items[0].status") { value("CONFIRMED") }
            jsonPath("$.data.items[0].paidAmount") { value(2_000) }
            jsonPath("$.data.items[0].confirmedAt") { value("2026-09-18T00:00:00.123456Z") }
            jsonPath("$.data.items[1].status") { value("DRAFT") }
            jsonPath("$.data.items[1].paidAmount") { doesNotExist() }
            jsonPath("$.data.items[1].confirmedAt") { doesNotExist() }
        }
    }

    /**
     * 쪽을 넘겨도 주문이 겹치거나 빠지지 않고 품목도 잘리지 않는다. 품목이 둘인 주문으로 확인한다.
     * 만든 시각이 같은 둘은 나중에 받은 식별자가 앞선다. 시각은 주문이 스스로 정하므로 SQL로 겹쳐 놓는다.
     */
    @Test
    fun `the page and size in the query string reach the order slice and equal creation times break by id`() {
        val shirt = product("티셔츠")
        val socks = product("양말", 2_000)
        val twoItems = """{"items":[{"productId":$shirt,"quantity":1},{"productId":$socks,"quantity":1}]}"""
        val oldest = create(twoItems, "oldest").andExpect { status { isCreated() } }.json()["data"]["orderId"].longValue()
        val tied = create(twoItems, "tied").andExpect { status { isCreated() } }.json()["data"]["orderId"].longValue()
        val tiedLater = create(twoItems, "tied-later").andExpect { status { isCreated() } }
            .json()["data"]["orderId"].longValue()
        jdbc.update("update orders set created_at = '2026-09-17 10:00:00.000000' where id = ?", oldest)
        jdbc.update("update orders set created_at = '2026-09-18 10:00:00.000000' where id in (?, ?)", tied, tiedLater)

        val pages = (0..3).map { page ->
            list("page" to "$page", "size" to "1").andExpect {
                status { isOk() }
                jsonPath("$.data.page") { value(page) }
                jsonPath("$.data.size") { value(1) }
            }.json()["data"]
        }

        assertAll(
            { assertThat(pages[0]["items"][0]["orderId"].longValue()).isEqualTo(tiedLater) },
            { assertThat(pages[1]["items"][0]["orderId"].longValue()).isEqualTo(tied) },
            { assertThat(pages[2]["items"][0]["orderId"].longValue()).isEqualTo(oldest) },
            { assertThat(pages.take(2).map { it["hasNext"].booleanValue() }).containsOnly(true) },
            { assertThat(pages[2]["hasNext"].booleanValue()).isFalse() },
            { assertThat(pages.take(3).map { it["items"][0]["items"].size() }).containsOnly(2) },
            { assertThat(pages[3]["items"].size()).isZero() },
            { assertThat(pages[3]["hasNext"].booleanValue()).isFalse() },
        )
    }

    @Test
    fun `listing orders outside the page and size bounds returns 400`() {
        listOf(
            ("page" to "-1") to "page는 0 이상이어야 합니다",
            ("size" to "0") to "size는 1 이상이어야 합니다",
            ("size" to "101") to "size는 100 이하여야 합니다",
        ).forEach { (query, message) ->
            list(query).andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value(containsString(message)) }
            }
        }
    }

    @Test
    fun `listing orders needs a requester and a user without orders gets an empty page`() {
        create(body(product())).andExpect { status { isCreated() } }
        listOf(null, Long.MAX_VALUE).forEach { requester ->
            list(requester = requester).andExpect {
                status { isUnauthorized() }
                jsonPath("$.meta.errorCode") { value("Unauthorized") }
            }
        }

        // 헤더가 사용자 식별자로 읽히지 않는 것은 요청자 확인보다 앞선 HTTP의 사실이라 400이다(설계 13.1).
        mockMvc.get("/api/v1/orders") { header(UserIdHeader.NAME, "abc") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }

        list(requester = userRepository.save(User()).id).andExpect {
            status { isOk() }
            jsonPath("$.data.items") { isEmpty() }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
        }
    }

    private fun body(id: Long, quantity: Int = 1): String = """{"items":[{"productId":$id,"quantity":$quantity}]}"""

    private fun items(id: Long, quantities: String): String = quantities.split(',')
        .joinToString(prefix = """{"items":[""", postfix = "]}") { """{"productId":$id,"quantity":$it}""" }

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

    private fun list(vararg query: Pair<String, String>, requester: Long? = userId): ResultActionsDsl =
        mockMvc.get("/api/v1/orders") {
            if (requester != null) header(UserIdHeader.NAME, requester)
            query.forEach { (name, value) -> param(name, value) }
        }

    private fun ResultActionsDsl.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)
}
