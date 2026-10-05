package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderService
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.brand.createBrand
import com.loopers.domain.order.createOrderCreateRequest
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.Stock
import com.loopers.domain.product.createProduct
import com.loopers.domain.shared.Money
import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.assertCheckConstraintRejects
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** No test transaction: every HTTP request commits or rolls back before the next request reads it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
class OrderApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val brandRepository: BrandRepository,
    private val productRepository: ProductRepository,
    private val orderService: OrderService,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory,
    transactionManager: PlatformTransactionManager,
) {
    companion object {
        /** 본문을 JSON으로 읽을 수조차 없는 것. Spring·Jackson의 범용 400이다(설계 13.1). */
        @JvmStatic
        fun unreadableBodies(): List<String> = listOf("", "null", "{")

        /**
         * Jackson 기본 바인딩이 거절하는 모양. 숫자 문자열·소수 표기의 정수는 받으므로 여기 없다(설계 5.10).
         * 양수 조건은 Request 제약이라 여기 없다.
         */
        @JvmStatic
        fun malformedBodies(): List<String> = listOf(
            "[]", "true", "123", "\"text\"", "{}", "{\"items\":null}", "{\"items\":\"text\"}",
            "{\"items\":true}", "{\"items\":1}", "{\"items\":[null]}", "{\"items\":[1]}",
            "{\"items\":[[]]}", "{\"items\":[{}]}", "{\"items\":[{\"productId\":PRODUCT_ID}]}",
            "{\"items\":[{\"quantity\":1}]}",
        ) + listOf("null", "true", "[]", "{}", "\"abc\"", "9223372036854775808")
            .map { """{"items":[{"productId":$it,"quantity":1}]}""" } +
            listOf("null", "true", "[]", "{}", "\"abc\"", "2147483648", "9223372036854775808")
                .map { """{"items":[{"productId":PRODUCT_ID,"quantity":$it}]}""" }
    }

    private val transaction = TransactionTemplate(transactionManager)
    private var userId = 0L
    private lateinit var brand: Brand

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        userId = userRepository.save(User()).id
        brand = brandRepository.save(createBrand())
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `create sorts items by product id and own detail preserves the committed draft`() {
        val first = productRepository.save(createProduct(brand, name = "티셔츠", price = Money(1_000), stock = Stock(0))).id
        val second = productRepository.save(createProduct(brand, price = Money(2_000), stock = Stock(1))).id
        val created = create(
            """{"items":[
                {"productId":$second,"quantity":1},
                {"productId":$first,"quantity":5}
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
        assertThat(productRepository.findById(first)!!.stock).isEqualTo(Stock(0))
        assertThat(productRepository.findById(second)!!.stock).isEqualTo(Stock(1))
    }

    @Test
    fun `a request that lists the same product twice is rejected and saves nothing`() {
        val id = productRepository.save(createProduct(brand)).id
        val other = productRepository.save(createProduct(brand)).id
        listOf(listOf(id, id), listOf(id, other, id)).forEach { productIds ->
            create(items(productIds)).andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value("주문은 상품별로 하나씩인 품목을 포함해야 합니다.") }
            }
            assertNoOrders()
        }
    }

    /** 상품마다 판매 가능 여부를 본 뒤 주문을 만들므로 그 404가 중복의 400보다 먼저다(설계 15). */
    @Test
    fun `a request that repeats an unavailable product returns 404 and saves nothing`() {
        val deleted = productRepository.save(createProduct(brand).apply { delete() }).id
        listOf(Long.MAX_VALUE, deleted).forEach { id ->
            create(items(id, "1,1")).andExpect {
                status { isNotFound() }
                jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
            }
            assertNoOrders()
        }
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    fun `malformed requests are rejected and save nothing`(body: String) {
        val id = productRepository.save(createProduct(brand)).id
        create(body.replace("PRODUCT_ID", id.toString())).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
        assertNoOrders()
    }

    /** 본문은 카탈로그처럼 Jackson 기본대로 읽는다. 숫자 문자열도 수량이다(설계 5.10). */
    @Test
    fun `an item quantity sent as a numeric string creates the order`() {
        val id = productRepository.save(createProduct(brand, price = Money(1_000))).id
        create("""{"items":[{"productId":$id,"quantity":"2"}]}""").andExpect {
            status { isCreated() }
            jsonPath("$.data.items[0].quantity") { value(2) }
            jsonPath("$.data.totalAmount") { value(2_000) }
        }
    }

    /** `JacksonConfig`의 `ACCEPT_SINGLE_VALUE_AS_ARRAY`가 품목 객체 하나를 한 품목 배열로 읽는다. 5.10이 적은 대가다. */
    @Test
    fun `a single item object in place of the items array creates a one item order`() {
        val id = productRepository.save(createProduct(brand)).id
        create("""{"items":{"productId":$id,"quantity":1}}""").andExpect {
            status { isCreated() }
            jsonPath("$.data.items.length()") { value(1) }
            jsonPath("$.data.items[0].productId") { value(id) }
        }
    }

    @ParameterizedTest
    @MethodSource("unreadableBodies")
    fun `unreadable bodies are rejected and save nothing`(body: String) {
        productRepository.save(createProduct(brand))
        create(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
        assertNoOrders()
    }

    @Test
    fun `items count is checked as sent and one hundred distinct products are allowed`() {
        val products = List(100) { productRepository.save(createProduct(brand, price = Money(1_000))).id }
        // 같은 상품 101개도 개수 제약이 먼저 거른다. 받은 품목을 그대로 센다.
        listOf(emptyList(), List(101) { products.first() }).forEach { productIds ->
            create(items(productIds)).andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value("주문 품목은 1개 이상 100개 이하여야 합니다.") }
            }
            assertNoOrders()
        }
        create(items(products)).andExpect {
            status { isCreated() }
            jsonPath("$.data.items.length()") { value(100) }
            jsonPath("$.data.totalAmount") { value(100_000) }
        }
    }

    @Test
    fun `nonpositive product ids and quantities are rejected and the largest quantity is accepted`() {
        val id = productRepository.save(createProduct(brand, price = Money(1_000))).id
        // 양수 조건은 Request 제약이라 범용 400 + 규칙 메시지다(설계 12.4, 13.1).
        listOf(0, -1).forEach { quantity ->
            create(body(id, quantity)).andExpect {
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
        create(body(id, Int.MAX_VALUE)).andExpect {
            status { isCreated() }
            jsonPath("$.data.items[0].quantity") { value(Int.MAX_VALUE) }
            jsonPath("$.data.totalAmount") { value(2_147_483_647_000L) }
        }
    }

    @Test
    fun `order amount overflow rolls back every item`() {
        val products = List(5) { productRepository.save(createProduct(brand, price = Money(1_000_000_000))).id }
        val entries = products.joinToString { """{"productId":$it,"quantity":2147483647}""" }
        create("""{"items":[$entries]}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("금액 계산 결과가 표현 범위를 넘습니다.") }
        }
        assertNoOrders()
    }

    @Test
    fun `missing and nonexistent requesters are unauthorized and other orders look missing`() {
        val request = body(productRepository.save(createProduct(brand)).id)
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
        mockMvc.get("/api/v1/orders/$orderId") { header(UserIdHeader.NAME, "abc") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
    }

    @Test
    fun `new orders reject unknown or deleted products and deleted brands`() {
        val active = productRepository.save(createProduct(brand)).id
        val deleted = productRepository.save(createProduct(brand).apply { delete() }).id
        listOf(Long.MAX_VALUE, deleted).forEach { id ->
            create("""{"items":[{"productId":$active,"quantity":1},{"productId":$id,"quantity":1}]}""").andExpect {
                status { isNotFound() }
                jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
            }
            assertNoOrders()
        }
        // The catalog API prevents deleting a brand with active products; seed that legacy state directly.
        jdbc.update("update brand set deleted_at = current_timestamp(6) where id = ?", brand.id)
        create(body(active)).andExpect {
            status { isNotFound() }
            jsonPath("$.meta.errorCode") { value("ORDER_PRODUCT_NOT_AVAILABLE") }
        }
        assertNoOrders()
    }

    @Test
    fun `client supplied prices names and totals are ignored`() {
        val id = productRepository.save(createProduct(brand, name = "서버 이름", price = Money(1_000))).id
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
        }
    }

    @Test
    fun `line amount overflow is rejected with no partial order`() {
        val id = productRepository.save(createProduct(brand)).id
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
    fun `foreign keys and order product uniqueness are enforced by MySQL`() {
        val id = productRepository.save(createProduct(brand)).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(id))).orderId
        val original = detail(orderId).andExpect { status { isOk() } }.json()
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
        assertConstraint(
            "insert into orders (user_id, status, total_amount, created_at) values (?, 'DRAFT', 1000, now(6))",
            Long.MAX_VALUE,
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
    fun `storage failure on the second item rolls back the order and its items`() {
        val first = productRepository.save(createProduct(brand)).id
        val second = productRepository.save(createProduct(brand)).id
        val request = """{"items":[{"productId":$first,"quantity":1},{"productId":$second,"quantity":1}]}"""
        jdbc.execute("alter table order_line_item add constraint fail_second_order_item check (product_id <> $second)")
        try {
            create(request).andExpect { status { isInternalServerError() } }
            assertNoOrders()
        } finally {
            jdbc.execute("alter table order_line_item drop check fail_second_order_item")
        }
    }

    private fun assertConstraint(sql: String, vararg args: Any) {
        assertThatThrownBy { jdbc.update(sql, *args) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database rejects inconsistent payment state and nonpositive item values`() {
        val productId = productRepository.save(createProduct(brand)).id
        val orderId = orderService.create(userId, createOrderCreateRequest(listOf(productId))).orderId
        val original = detail(orderId).andExpect { status { isOk() } }.json()
        listOf(
            "paid_amount = 1000",
            "confirmed_at = now(6)",
            "status = 'CONFIRMED'",
            "total_amount = 0",
            "status = 'CONFIRMED', paid_amount = total_amount - 1, confirmed_at = now(6)",
            "status = 'CONFIRMED', paid_amount = null, confirmed_at = now(6)",
        ).forEach { update -> jdbc.assertCheckConstraintRejects("update orders set $update where id = ?", orderId) }
        listOf("quantity = 0", "unit_price = 0", "line_amount = 0").forEach { update ->
            jdbc.assertCheckConstraintRejects("update order_line_item set $update where order_id = ?", orderId)
        }
        assertThat(detail(orderId).json()).isEqualTo(original)
    }

    @Test
    fun `Hibernate schema recreation drops scalar references and recreates every order foreign key`() {
        orderService.create(userId, createOrderCreateRequest(listOf(productRepository.save(createProduct(brand)).id)))
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
            "FK_ORDERS_USER",
            "FK_ORDER_LINE_ITEM_ORDER",
            "FK_ORDER_LINE_ITEM_PRODUCT",
        )
        userId = userRepository.save(User()).id
        brand = brandRepository.save(createBrand())
        create(body(productRepository.save(createProduct(brand)).id)).andExpect { status { isCreated() } }
    }

    /**
     * 목록의 항목은 상세와 같은 주문 응답이다. 필드를 하나씩 다시 세지 않고 상세의 JSON과 그대로 견준다.
     * 두 응답이 말없이 어긋날 수 없게 하려는 것이며, 스냅샷이 카탈로그의 변경을 따라가지 않는 것도 함께 본다(ADR 0002).
     */
    @Test
    fun `the order list returns only the requester's orders from the newest with the same entries as the detail`() {
        val shirt = productRepository.save(createProduct(brand, name = "티셔츠", price = Money(1_000))).id
        val socks = productRepository.save(createProduct(brand, price = Money(2_000))).id
        // 품목을 상품 ID의 거꾸로 넣는다. 응답이 넣은 차례 그대로면 품목의 차례를 확인한 것이 아니다.
        val older = detail(orderService.create(userId, createOrderCreateRequest(socks to 1, shirt to 2)).orderId)
            .json()["data"]
        val newer = detail(orderService.create(userId, createOrderCreateRequest(listOf(socks))).orderId).json()["data"]
        val otherUser = userRepository.save(User()).id
        val foreign = orderService.create(otherUser, createOrderCreateRequest(listOf(shirt))).orderId
        transaction.executeWithoutResult {
            productRepository.findById(shirt)!!.update("바뀐 이름", Money(9_000))
            productRepository.findById(socks)!!.delete()
        }

        val listed = list().andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.page") { value(0) }
            jsonPath("$.data.size") { value(20) }
            jsonPath("$.data.hasNext") { value(false) }
            jsonPath("$.data.items.length()") { value(2) }
        }.json()["data"]["items"]

        assertThat(listed[0]).isEqualTo(newer)
        assertThat(listed[1]).isEqualTo(older)
        assertThat(listed[1]["items"][0]["productName"].stringValue()).isEqualTo("티셔츠")
        assertThat(listed[1]["items"][0]["unitPrice"].longValue()).isEqualTo(1_000)
        assertThat(listed[1]["items"][0]["quantity"].intValue()).isEqualTo(2)
        assertThat(listed[1]["items"][1]["productId"].longValue()).isEqualTo(socks)
        assertThat(listed[1]["totalAmount"].longValue()).isEqualTo(4_000)
        assertThat(listed.values().map { it["orderId"].longValue() }).doesNotContain(foreign)
        val foreignList = list(requester = otherUser).json()["data"]["items"]
        assertThat(foreignList.single()["orderId"].longValue()).isEqualTo(foreign)
    }

    /** 저장된 두 상태가 목록에서도 상세와 같은 모양이다. 확정 동작은 후속 티켓의 책임이라 상태를 DB fixture로 만든다(설계 13). */
    @Test
    fun `the order list shows draft and confirmed entries with their stored payment fields`() {
        val id = productRepository.save(createProduct(brand, price = Money(1_000))).id
        orderService.create(userId, createOrderCreateRequest(listOf(id)))
        val confirmed = orderService.create(userId, createOrderCreateRequest(listOf(id), quantity = 2)).orderId
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
        val productIds = List(2) { productRepository.save(createProduct(brand)).id }
        val oldest = orderService.create(userId, createOrderCreateRequest(productIds)).orderId
        val tied = orderService.create(userId, createOrderCreateRequest(productIds)).orderId
        val tiedLater = orderService.create(userId, createOrderCreateRequest(productIds)).orderId
        jdbc.update("update orders set created_at = '2026-09-17 10:00:00.000000' where id = ?", oldest)
        jdbc.update("update orders set created_at = '2026-09-18 10:00:00.000000' where id in (?, ?)", tied, tiedLater)

        val pages = (0..3).map { page ->
            list("page" to "$page", "size" to "1").andExpect {
                status { isOk() }
                jsonPath("$.data.page") { value(page) }
                jsonPath("$.data.size") { value(1) }
            }.json()["data"]
        }

        assertThat(pages[0]["items"][0]["orderId"].longValue()).isEqualTo(tiedLater)
        assertThat(pages[1]["items"][0]["orderId"].longValue()).isEqualTo(tied)
        assertThat(pages[2]["items"][0]["orderId"].longValue()).isEqualTo(oldest)
        assertThat(pages.take(2).map { it["hasNext"].booleanValue() }).containsOnly(true)
        assertThat(pages[2]["hasNext"].booleanValue()).isFalse()
        assertThat(pages.take(3).map { it["items"][0]["items"].size() }).containsOnly(2)
        assertThat(pages[3]["items"].size()).isZero()
        assertThat(pages[3]["hasNext"].booleanValue()).isFalse()
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
        orderService.create(userId, createOrderCreateRequest(listOf(productRepository.save(createProduct(brand)).id)))
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

    private fun items(productIds: List<Long>): String =
        productIds.joinToString(prefix = """{"items":[""", postfix = "]}") { """{"productId":$it,"quantity":1}""" }

    private fun assertNoOrders() {
        assertThat(jdbc.queryForObject("select count(*) from orders", Long::class.java)!!).isZero()
        assertThat(jdbc.queryForObject("select count(*) from order_line_item", Long::class.java)!!).isZero()
    }

    private fun create(body: String, requester: Long? = userId): ResultActionsDsl =
        mockMvc.post("/api/v1/orders") {
            if (requester != null) header(UserIdHeader.NAME, requester)
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
