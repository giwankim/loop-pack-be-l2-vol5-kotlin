package com.loopers.interfaces.api.v1.point

import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.point.PointAccountRepository
import com.loopers.interfaces.api.IdempotencyKeyHeader
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.support.error.ErrorType
import com.loopers.utils.UserFixture
import com.loopers.utils.balanceOf
import com.loopers.utils.countPointAccounts
import com.loopers.utils.countPointHistories
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/**
 * 고객 포인트 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * [AdminSecurityConfig]를 가져오는 까닭은 [com.loopers.interfaces.api.v1.brand.BrandApiMockMvcTest]와 같다.
 *
 * 재요청·키 범위·계정 없음의 규칙은 [com.loopers.application.point.PointServiceTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 헤더가 요청자와 충전 키로 이어지는지, 본문의 JSON 토큰을 어디까지 받는지, 오류가 어느 status와 code로
 * 내려가는지, 거절 뒤 잔액과 이력이 그대로인지를 본다(설계 5.10, 6). 요청 사이를 비우는 까닭은
 * [com.loopers.interfaces.api.v1.product.ProductApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class PointApiMockMvcTest(
    private val mockMvc: MockMvc,
    private val pointAccountRepository: PointAccountRepository,
    private val userFixture: UserFixture,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val POINTS = "/api/v1/points"
        private const val CHARGE = "$POINTS/charge"
    }

    /** 이 티켓의 인수 조건인 흐름. 0원 → 충전 10,000 → 조회 10,000 → 충전 500 → 조회 10,500. */
    @Test
    fun `charging shows the balance right after and the balance read shows the current balance`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        getBalance(userId).andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.balance") { value(0) }
        }

        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.balance") { value(10_000) }
            jsonPath("$.data.length()") { value(1) }
        }
        entityManager.flushAndClear()

        getBalance(userId).andExpect { jsonPath("$.data.balance") { value(10_000) } }

        charge(userId, key = "charge-002", body = """{"amount": 500}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_500) }
        }
        entityManager.flushAndClear()

        getBalance(userId).andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_500) }
        }
    }

    /** 같은 키·같은 충전액의 재요청은 새 영속성 컨텍스트에서도 첫 응답과 같은 status·본문이다(설계 10 영속 재생). */
    @Test
    fun `charging again with the same key and amount replays the first response and adds no history`() {
        val userId = registerUser()
        entityManager.flushAndClear()
        val first = charge(userId, key = "charge-001", body = """{"amount": 10000}""")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        entityManager.flushAndClear()

        val replayed = charge(userId, key = "charge-001", body = """{"amount": 10000}""")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        entityManager.flushAndClear()
        val accountId = accountIdOf(userId)

        assertAll(
            { assertThat(replayed).isEqualTo(first) },
            { assertThat(entityManager.balanceOf(accountId)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(accountId)).isOne() },
        )
    }

    /** 뒤에 충전해 잔액이 늘어도 앞선 충전의 재요청은 그때의 잔액이고, 조회는 현재 잔액이다(ADR 0004). */
    @Test
    fun `a replay returns the balance right after that charge while the balance read returns the current one`() {
        val userId = registerUser()
        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect { status { isOk() } }
        charge(userId, key = "charge-002", body = """{"amount": 500}""").andExpect { status { isOk() } }
        entityManager.flushAndClear()

        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_000) }
        }
        getBalance(userId).andExpect { jsonPath("$.data.balance") { value(10_500) } }
    }

    @Test
    fun `charging again with the same key and another amount returns 409 and changes nothing`() {
        val userId = registerUser()
        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect { status { isOk() } }
        entityManager.flushAndClear()

        charge(userId, key = "charge-001", body = """{"amount": 20000}""").andExpect {
            status { isConflict() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("IDEMPOTENCY_KEY_CONFLICT") }
            jsonPath("$.meta.message") { value(ErrorType.IDEMPOTENCY_KEY_CONFLICT.message) }
            jsonPath("$.data") { doesNotExist() }
        }
        entityManager.flushAndClear()
        val accountId = accountIdOf(userId)

        assertAll(
            { assertThat(entityManager.balanceOf(accountId)).isEqualTo(10_000L) },
            { assertThat(entityManager.countPointHistories(accountId)).isOne() },
        )
    }

    /** 키는 대소문자를 구분하고 공백을 떼거나 소문자로 바꾸지 않는다(설계 5.8). */
    @Test
    fun `keys that differ only in case are separate charges`() {
        val userId = registerUser()
        charge(userId, key = "Charge-A", body = """{"amount": 1000}""").andExpect { status { isOk() } }
        entityManager.flushAndClear()

        charge(userId, key = "charge-a", body = """{"amount": 2000}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(3_000) }
        }
        entityManager.flushAndClear()

        assertThat(entityManager.countPointHistories(accountIdOf(userId))).isEqualTo(2L)
    }

    @Test
    fun `the same key charges each user independently`() {
        val userId = registerUser()
        val otherUserId = registerUser()
        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect { status { isOk() } }
        entityManager.flushAndClear()

        charge(otherUserId, key = "charge-001", body = """{"amount": 3000}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(3_000) }
        }
        entityManager.flushAndClear()

        getBalance(userId).andExpect { jsonPath("$.data.balance") { value(10_000) } }
        getBalance(otherUserId).andExpect { jsonPath("$.data.balance") { value(3_000) } }
    }

    @Test
    fun `charging and reading without the user header return 401`() {
        mockMvc.post(CHARGE) {
            header(IdempotencyKeyHeader.NAME, "charge-001")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": 10000}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
            jsonPath("$.meta.message") { value(ErrorType.UNAUTHORIZED.message) }
        }
        mockMvc.get(POINTS).andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
        }
    }

    @Test
    fun `charging and reading as a user that does not exist return 401 and create no account`() {
        charge(999L, key = "charge-001", body = """{"amount": 10000}""").andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
        }
        getBalance(999L).andExpect {
            status { isUnauthorized() }
            jsonPath("$.meta.errorCode") { value("Unauthorized") }
        }

        assertThat(entityManager.countPointAccounts(999L)).isZero()
    }

    /** 헤더가 있으나 숫자가 아니면 요청자가 없는 것이 아니라 요청이 잘못된 것이다. Spring의 타입 변환이 거절한다(카탈로그 설계 5.27). */
    @Test
    fun `a user header that is not a number returns 400 on both APIs`() {
        mockMvc.post(CHARGE) {
            header(UserIdHeader.NAME, "abc")
            header(IdempotencyKeyHeader.NAME, "charge-001")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": 10000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
        mockMvc.get(POINTS) { header(UserIdHeader.NAME, "abc") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
        }
    }

    /** 사용자는 있는데 계정이 없는 것은 데이터 불일치라 내부 오류다. 0원 계정을 만들어 주지 않는다(설계 5.9, 6 끝). */
    @Test
    fun `an existing user without an account gets 500 on both APIs and no account is created`() {
        val userId = userFixture.registerUserWithoutAccount().id
        entityManager.flushAndClear()

        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect {
            status { isInternalServerError() }
            jsonPath("$.meta.errorCode") { value("Internal Server Error") }
            jsonPath("$.meta.message") { value(ErrorType.POINT_ACCOUNT_MISSING.message) }
        }
        getBalance(userId).andExpect {
            status { isInternalServerError() }
            jsonPath("$.meta.message") { value(ErrorType.POINT_ACCOUNT_MISSING.message) }
        }

        assertThat(entityManager.countPointAccounts(userId)).isZero()
    }

    @Test
    fun `charging without the Idempotency-Key header returns 400 and changes nothing`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        mockMvc.post(CHARGE) {
            header(UserIdHeader.NAME, userId)
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": 10000}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("INVALID_IDEMPOTENCY_KEY") }
            jsonPath("$.meta.message") { value(ErrorType.INVALID_IDEMPOTENCY_KEY.message) }
        }
        entityManager.flushAndClear()

        assertUnchanged(userId)
    }

    /** 허용 형식은 ASCII 영문·숫자·하이픈·밑줄 1–128자다. 빈 값, 공백, 다른 문자, 129자는 거절한다(설계 5.8). */
    @Test
    fun `an Idempotency-Key outside the allowed form returns 400 and changes nothing`() {
        val userId = registerUser()
        entityManager.flushAndClear()
        val invalidKeys = listOf("", "charge 001", "charge/001", "충전-001", "k".repeat(129))

        assertAll(
            invalidKeys.map { key ->
                {
                    charge(userId, key = key, body = """{"amount": 10000}""").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.meta.errorCode") { value("INVALID_IDEMPOTENCY_KEY") }
                    }
                }
            },
        )
        entityManager.flushAndClear()

        assertUnchanged(userId)
    }

    /** 128자는 허용하고 세 종류의 문자를 모두 쓸 수 있다. */
    @Test
    fun `an Idempotency-Key of 128 letters, digits, hyphens, and underscores is accepted`() {
        val userId = registerUser()
        entityManager.flushAndClear()
        val key = "Aa0-_".repeat(25) + "Zz9"

        charge(userId, key = key, body = """{"amount": 10000}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_000) }
        }
        entityManager.flushAndClear()

        assertThat(entityManager.countPointHistories(accountIdOf(userId), key)).isOne()
    }

    /**
     * 충전액은 정수 표기의 JSON 숫자만 받는다. 숫자 문자열·소수·지수·null·누락·boolean·배열·`Long` 범위 밖은 400이다(설계 5.10).
     * 거절된 요청은 키를 쓰지 않으므로 같은 키로 고쳐 보내면 성공한다(설계 5.8).
     */
    @Test
    fun `an amount that is not an integer JSON number returns 400, changes nothing, and leaves the key reusable`() {
        val userId = registerUser()
        entityManager.flushAndClear()
        val invalidBodies = listOf(
            """{"amount": "10000"}""",
            """{"amount": 10000.0}""",
            """{"amount": 1e4}""",
            """{"amount": null}""",
            """{}""",
            """{"amount": true}""",
            """{"amount": [10000]}""",
            """{"amount": 100000000000000000000}""",
        )

        assertAll(
            invalidBodies.map { body ->
                {
                    charge(userId, key = "charge-001", body = body).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.meta.result") { value("FAIL") }
                        jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
                        jsonPath("$.meta.message") { value(ErrorType.INVALID_POINT_ORDER_REQUEST.message) }
                    }
                }
            },
        )
        entityManager.flushAndClear()
        assertUnchanged(userId)

        charge(userId, key = "charge-001", body = """{"amount": 10000}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_000) }
        }
    }

    /** 알 수 없는 필드는 기존 정책대로 무시한다(설계 5.10). */
    @Test
    fun `an unknown field next to a valid amount is ignored`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        charge(userId, key = "charge-001", body = """{"amount": 10000, "balance": 1}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_000) }
        }
    }

    /** 0원 이하는 Request 제약이 거른다. 범용 400이며 메시지가 규칙을 말한다(카탈로그 설계 5.18). */
    @Test
    fun `charging zero or a negative amount returns 400 and changes nothing`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        assertAll(
            listOf(0L, -1L).map { amount ->
                {
                    charge(userId, key = "charge-001", body = """{"amount": $amount}""").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.meta.errorCode") { value("Bad Request") }
                        jsonPath("$.meta.message") { value("충전액은 1원 이상이어야 합니다.") }
                    }
                }
            },
        )
        entityManager.flushAndClear()

        assertUnchanged(userId)
    }

    /** 충전 후 잔액이 `Long` 범위를 넘으면 domain이 거절한다. 잔액은 그대로이고 키는 다시 쓸 수 있다(설계 5.7). */
    @Test
    fun `a charge that overflows the balance returns 400, keeps the balance, and leaves the key reusable`() {
        val userId = registerUser()
        charge(userId, key = "charge-001", body = """{"amount": ${Long.MAX_VALUE}}""").andExpect { status { isOk() } }
        entityManager.flushAndClear()

        charge(userId, key = "charge-002", body = """{"amount": 1}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("금액 계산 결과가 표현 범위를 넘습니다.") }
        }
        entityManager.flushAndClear()
        val accountId = accountIdOf(userId)

        assertAll(
            { assertThat(entityManager.balanceOf(accountId)).isEqualTo(Long.MAX_VALUE) },
            { assertThat(entityManager.countPointHistories(accountId)).isOne() },
            { assertThat(entityManager.countPointHistories(accountId, "charge-002")).isZero() },
        )
    }

    /** 상품 가격의 10억 원 상한은 잔액에 적용되지 않는다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        charge(userId, key = "charge-001", body = """{"amount": 1000000001}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(1_000_000_001L) }
        }
    }

    private fun charge(userId: Long, key: String, body: String): ResultActionsDsl =
        mockMvc.post(CHARGE) {
            header(UserIdHeader.NAME, userId)
            header(IdempotencyKeyHeader.NAME, key)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun getBalance(userId: Long): ResultActionsDsl = mockMvc.get(POINTS) { header(UserIdHeader.NAME, userId) }

    private fun registerUser(): Long = userFixture.registerUser().id

    private fun accountIdOf(userId: Long): Long = pointAccountRepository.findByUserId(userId)!!.id

    /** 거절 뒤 잔액이 0원 그대로이고 이력이 하나도 없다. */
    private fun assertUnchanged(userId: Long) {
        val accountId = accountIdOf(userId)
        assertAll(
            { assertThat(entityManager.balanceOf(accountId)).isZero() },
            { assertThat(entityManager.countPointHistories(accountId)).isZero() },
        )
    }
}
