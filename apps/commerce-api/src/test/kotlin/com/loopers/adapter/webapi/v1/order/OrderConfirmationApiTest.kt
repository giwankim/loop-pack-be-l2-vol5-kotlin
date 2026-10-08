package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.domain.product.Product
import com.loopers.domain.user.User
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** Each request ends its own transaction; read-back uses fresh persistence contexts. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderConfirmationApiTest(
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : BaseWebApiAdapterTest() {
    private val transaction = TransactionTemplate(transactionManager)

    /**
     * 요청 도우미가 기본으로 싣는 요청자이자 주문의 주인. [setUp]이 준비하고, 다른 주인이 필요한 테스트는 바꿔 넣는다.
     * 다른 사용자를 준비하는 테스트가 기반 클래스의 `user` 필드를 바꾸므로 그 필드가 아닌 반환값을 따로 쥔다.
     */
    private lateinit var owner: User

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        owner = prepareUser()
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `charging creating and confirming deduct stock and points once and read back the confirmed order`() {
        balance(0)
        charge(amount = 10_000, user = owner)
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 10)
        val second = prepareProduct(brand, price = 2_000, stock = 5)
        val orderId = prepareOrder(second to 1, first to 5, user = owner).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        assertThat(draft.json()["data"]["totalAmount"].longValue()).isEqualTo(7_000)
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 5)

        val before = Instant.now().minusSeconds(1)
        val confirmed = requestConfirm(orderId)
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
        val confirmedDetail = requestDetail(orderId)
        assertThat(confirmedDetail).hasStatusOk()
        assertThat(confirmedDetail.json()).isEqualTo(confirmed.json())
        assertAlreadyConfirmed(orderId)
        balance(3_000)
        assertStock(first, 5)
        assertStock(second, 4)
    }

    @Test
    fun `a later item shortage rolls back all deductions and replenishment makes the same draft confirmable`() {
        charge(amount = 10_000, user = owner)
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 10)
        val second = prepareProduct(brand, price = 1_000, stock = 4)
        val orderId = prepareOrder(first to 2, second to 5, user = owner).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("INSUFFICIENT_STOCK")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        balance(10_000)
        assertStock(first, 10)
        assertStock(second, 4)
        updateProductStock(quantity = 5, product = second)
        val confirmBody = assertThat(requestConfirm(orderId)).hasStatusOk().bodyJson()
        confirmBody.extractingPath("$.data.paidAmount").isEqualTo(7_000)
        balance(3_000)
        assertStock(first, 8)
        assertStock(second, 0)
    }

    @Test
    fun `insufficient points rolls back every item and charging allows the same order to be confirmed`() {
        charge(amount = 3_000, user = owner)
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 2)
        val second = prepareProduct(brand, price = 1_000, stock = 2)
        val orderId = prepareOrder(owner, listOf(first, second), quantity = 2).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("INSUFFICIENT_POINTS")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        balance(3_000)
        assertStock(first, 2)
        assertStock(second, 2)
        charge(amount = 1_000, user = owner)
        val confirmBody = assertThat(requestConfirm(orderId)).hasStatusOk().bodyJson()
        confirmBody.extractingPath("$.data.paidAmount").isEqualTo(4_000)
        balance(0)
        assertStock(first, 0)
        assertStock(second, 0)
    }

    @ParameterizedTest
    @ValueSource(longs = [500, 2_000])
    fun `an old draft confirms at its saved price after a catalog price increase or decrease`(newPrice: Long) {
        charge(amount = 1_000, user = owner)
        prepareProduct(name = "원래 이름", price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        jdbc.update("update orders set created_at = '2020-01-01 00:00:00.123456' where id = ?", orderId)
        updateProduct(name = "바뀐 이름", price = newPrice)

        val confirmed = requestConfirm(orderId)
        val body = assertThat(confirmed).hasStatusOk().bodyJson()
        body.extractingPath("$.data.paidAmount").isEqualTo(1_000)
        body.extractingPath("$.data.items[0].productName").isEqualTo("원래 이름")
        body.extractingPath("$.data.items[0].unitPrice").isEqualTo(1_000)
        body.extractingPath("$.data.createdAt").isEqualTo("2020-01-01T00:00:00.123456Z")

        assertThat(requestDetail(orderId).json()).isEqualTo(confirmed.json())
        balance(0)
        assertStock(product, 9)
    }

    @ParameterizedTest
    @ValueSource(strings = ["product", "brand"])
    fun `unavailable products or brands reject the whole confirmation and preserve the draft`(deleted: String) {
        charge(amount = 10_000, user = owner)
        val first = prepareProduct(stock = 10)
        val secondBrand = prepareBrand()
        val second = prepareProduct(secondBrand, stock = 10)
        val orderId = prepareOrder(owner, listOf(first, second)).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        if (deleted == "product") {
            deleteProduct(second)
        } else {
            // The catalog normally prevents this; exercise the same legacy state as OrderApiTest.
            deleteBrandKeepingProducts(secondBrand)
        }

        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_PRODUCT_NOT_AVAILABLE")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
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
        charge(amount = 10_000, user = owner)
        prepareBrand()
        val first = prepareProduct(brand)
        val second = prepareProduct(brand)
        val orderId = prepareOrder(owner, listOf(first, second), quantity = 2).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        val (short, unavailable) = if (shortageFirst) first to second else second to first
        updateProductStock(quantity = 1, product = short)
        deleteProduct(unavailable)

        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_PRODUCT_NOT_AVAILABLE")

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        balance(10_000)
        assertStock(short, 1)
    }

    @Test
    fun `requester and ownership checks precede both first confirmation and the already confirmed rejection`() {
        charge(amount = 1_000, user = owner)
        prepareProduct(price = 1_000, stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        val otherUser = prepareUser().id

        fun assertAccessDenied() {
            listOf(null, Long.MAX_VALUE).forEach { requester ->
                val body = assertThat(requestConfirm(orderId, requester)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
                body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
            }
            val missing = requestConfirm(Long.MAX_VALUE)
            val error = assertThat(missing).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
            error.extractingPath("$.meta.errorCode").isEqualTo("ORDER_NOT_FOUND")
            val forbidden = requestConfirm(orderId, otherUser)
            assertThat(forbidden).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(forbidden.json()).isEqualTo(missing.json())
        }

        assertAccessDenied()
        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        balance(1_000)
        assertStock(product, 10)
        val confirmed = requestConfirm(orderId)
        assertThat(confirmed).hasStatusOk()
        assertAccessDenied()
        assertAlreadyConfirmed(orderId)
        assertThat(requestDetail(orderId).json()).isEqualTo(confirmed.json())
        balance(0)
        assertStock(product, 9)
    }

    /** 다시 확정하면 차감 없이 거절된다. 첫 확정의 결과는 GET으로 읽는다(ADR 0005). */
    private fun assertAlreadyConfirmed(orderId: Long) {
        val body = assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("ORDER_ALREADY_CONFIRMED")
    }

    /**
     * 확정할 수 있는지를 상품·재고·포인트보다 먼저 본다. 잔액을 다 쓰고 상품·브랜드가 삭제돼도 판매 불가나 잔액 부족이 아니라
     * 이미 확정된 주문이라는 거절이 나온다(ADR 0005).
     */
    @Test
    fun `re-confirming is rejected as already confirmed even after later spending and product and brand deletion`() {
        charge(amount = 3_000, user = owner)
        prepareProduct(price = 1_000, stock = 3)
        val firstId = prepareOrder(owner, listOf(product), quantity = 1).id
        val confirmed = requestConfirm(firstId)
        assertThat(confirmed).hasStatusOk()
        val secondId = prepareOrder(owner, listOf(product), quantity = 2).id
        assertThat(requestConfirm(secondId)).hasStatusOk()
        balance(0)
        assertStock(product, 0)
        deleteProduct()
        deleteBrand()

        repeat(2) {
            assertAlreadyConfirmed(firstId)
            val firstDetail = requestDetail(firstId)
            assertThat(firstDetail).hasStatusOk()
            assertThat(firstDetail.json()).isEqualTo(confirmed.json())
        }

        balance(0)
        assertStock(product, 0)
    }

    @Test
    fun `a missing point account is an internal error and rolls back stock without creating an account`() {
        owner = prepareUserWithoutAccount()
        prepareProduct(stock = 10)
        val orderId = prepareOrder(owner, listOf(product), quantity = 1).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()

        assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)

        assertThat(requestDetail(orderId).json()).isEqualTo(draft.json())
        assertStock(product, 10)
        assertThat(jdbc.queryForObject("select count(*) from point_account where user_id = ?", Long::class.java, owner.id)!!)
            .isZero()
    }

    private fun assertStock(product: Product, expected: Int) {
        assertThat(jdbc.queryForObject("select stock_quantity from product where id = ?", Int::class.java, product.id)!!)
            .isEqualTo(expected)
    }

    /**
     * 확정은 저장소를 부르지 않고 커밋의 flush로 쓴다. flush는 엔티티를 읽은 차례로 UPDATE를 보내므로 주문, 상품,
     * 포인트 계정 차례다. 마지막인 `point_account`의 UPDATE를 임시 CHECK로 거절해, 앞서 나간 주문·재고의 UPDATE까지
     * 함께 되돌아가는지 본다(설계 18.2).
     */
    @Test
    fun `a failure on the last write at commit rolls back the earlier stock and order writes and permits retry`() {
        charge(amount = 10_000, user = owner)
        prepareBrand()
        val first = prepareProduct(brand, price = 1_000, stock = 6)
        val second = prepareProduct(brand, price = 2_000, stock = 2)
        val orderId = prepareOrder(first to 5, second to 1, user = owner).id
        val draft = requestDetail(orderId)
        assertThat(draft).hasStatusOk()
        jdbc.execute("alter table point_account add constraint fail_paid_balance check (balance <> 3000)")
        try {
            assertThat(requestConfirm(orderId)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        } finally {
            jdbc.execute("alter table point_account drop check fail_paid_balance")
        }

        // A new transaction, outside the failed HTTP request, proves rollback rather than test cleanup.
        transaction.executeWithoutResult {
            assertStock(first, 6)
            assertStock(second, 2)
            assertThat(jdbc.queryForObject("select balance from point_account where user_id = ?", Long::class.java, owner.id)!!)
                .isEqualTo(10_000L)
            assertThat(jdbc.queryForMap("select status, paid_amount, confirmed_at from orders where id = ?", orderId))
                .containsAllEntriesOf(mapOf("status" to "DRAFT", "paid_amount" to null, "confirmed_at" to null))
        }
        val afterRollback = requestDetail(orderId)
        assertThat(afterRollback).hasStatusOk()
        assertThat(afterRollback.json()).isEqualTo(draft.json())
        assertThat(requestConfirm(orderId)).hasStatusOk()
        balance(3_000)
        assertStock(first, 1)
        assertStock(second, 1)
    }

    private fun balance(expected: Long) {
        val body = assertThat(mvc.get().uri("/api/v1/points").header(UserIdHeader.NAME, owner.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.data.balance").isEqualToLong(expected)
    }

    private fun requestConfirm(orderId: Long, requester: Long? = owner.id): MvcTestResult =
        mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .apply { requester?.let { header(UserIdHeader.NAME, it) } }
            .exchange()

    private fun requestDetail(orderId: Long): MvcTestResult =
        mvc.get().uri("/api/v1/orders/$orderId").header(UserIdHeader.NAME, owner.id).exchange()

    private fun MvcTestResult.json(): JsonNode = objectMapper.readTree(response.contentAsString)
}
