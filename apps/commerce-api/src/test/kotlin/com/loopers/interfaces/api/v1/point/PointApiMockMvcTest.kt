package com.loopers.interfaces.api.v1.point

import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.user.UserFixture
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.support.error.ErrorType
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import com.loopers.utils.balanceOf
import com.loopers.utils.countPointAccounts
import com.loopers.utils.flushAndClear
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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
 * 계정 없음과 충전이 잔액에 더해지는 규칙은 [com.loopers.application.point.PointServiceTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 헤더가 요청자로 이어지는지, 본문의 JSON 토큰을 어디까지 받는지, 오류가 어느 status와 code로
 * 내려가는지, 거절 뒤 잔액이 그대로인지를 본다(설계 5.10, 6). 요청 사이를 비우는 까닭은
 * [com.loopers.interfaces.api.v1.product.ProductApiMockMvcTest]와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class, AdminSecurityConfig::class)
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

        charge(userId, body = """{"amount": 10000}""").andExpect {
            status { isOk() }
            jsonPath("$.meta.result") { value("SUCCESS") }
            jsonPath("$.data.balance") { value(10_000) }
            jsonPath("$.data.length()") { value(1) }
        }
        entityManager.flushAndClear()

        getBalance(userId).andExpect { jsonPath("$.data.balance") { value(10_000) } }

        charge(userId, body = """{"amount": 500}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_500) }
        }
        entityManager.flushAndClear()

        getBalance(userId).andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10_500) }
        }
    }

    @Test
    fun `charging and reading without the user header return 401`() {
        mockMvc.post(CHARGE) {
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
        charge(999L, body = """{"amount": 10000}""").andExpect {
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

        charge(userId, body = """{"amount": 10000}""").andExpect {
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

    /** 충전액은 카탈로그처럼 Jackson 기본대로 읽는다. 숫자 문자열과 소수 표기의 정수도 받는다(설계 5.10). */
    @Test
    fun `an amount sent as a numeric string or a whole decimal is charged`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        charge(userId, body = """{"amount": "5"}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(5) }
        }
        charge(userId, body = """{"amount": 5.0}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(10) }
        }
        entityManager.flushAndClear()

        assertThat(entityManager.balanceOf(accountIdOf(userId))).isEqualTo(10)
    }

    /** 숫자로 읽을 수 없거나 `Long` 범위 밖인 값은 다른 본문 오류와 같은 범용 400이다(설계 5.10). */
    @Test
    fun `a missing, null, non-numeric or out-of-range amount returns 400 and changes nothing`() {
        val userId = registerUser()
        entityManager.flushAndClear()
        val invalidBodies = listOf(
            """{}""",
            """{"amount": null}""",
            """{"amount": "abc"}""",
            """{"amount": true}""",
            """{"amount": [10000]}""",
            """{"amount": 100000000000000000000}""",
        )

        assertAll(
            invalidBodies.map { body ->
                {
                    charge(userId, body = body).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.meta.result") { value("FAIL") }
                        jsonPath("$.meta.errorCode") { value("Bad Request") }
                    }
                }
            },
        )
        entityManager.flushAndClear()
        assertUnchanged(userId)
    }

    /** 소수 금액을 Jackson 3이 어떻게 읽는지 고정한다. 바뀌면 이 테스트가 먼저 알린다(설계 5.10). */
    @Test
    fun `a fractional amount is truncated to its integer part`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        charge(userId, body = """{"amount": 1.5}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(1) }
        }
    }

    /** 알 수 없는 필드는 기존 정책대로 무시한다(설계 5.10). */
    @Test
    fun `an unknown field next to a valid amount is ignored`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        charge(userId, body = """{"amount": 10000, "balance": 1}""").andExpect {
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
                    charge(userId, body = """{"amount": $amount}""").andExpect {
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

    /** 충전 후 잔액이 `Long` 범위를 넘으면 domain이 거절한다. 잔액은 그대로다(설계 5.7). */
    @Test
    fun `a charge that overflows the balance returns 400 and keeps the balance`() {
        val userId = registerUser()
        charge(userId, body = """{"amount": ${Long.MAX_VALUE}}""").andExpect { status { isOk() } }
        entityManager.flushAndClear()

        charge(userId, body = """{"amount": 1}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("금액 계산 결과가 표현 범위를 넘습니다.") }
        }
        entityManager.flushAndClear()

        assertThat(entityManager.balanceOf(accountIdOf(userId))).isEqualTo(Long.MAX_VALUE)
    }

    /** 상품 가격의 10억 원 상한은 잔액에 적용되지 않는다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        val userId = registerUser()
        entityManager.flushAndClear()

        charge(userId, body = """{"amount": 1000000001}""").andExpect {
            status { isOk() }
            jsonPath("$.data.balance") { value(1_000_000_001L) }
        }
    }

    private fun charge(userId: Long, body: String): ResultActionsDsl =
        mockMvc.post(CHARGE) {
            header(UserIdHeader.NAME, userId)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun getBalance(userId: Long): ResultActionsDsl = mockMvc.get(POINTS) { header(UserIdHeader.NAME, userId) }

    private fun registerUser(): Long = userFixture.registerUser().id

    private fun accountIdOf(userId: Long): Long = pointAccountRepository.findByUserId(userId)!!.id

    /** 거절 뒤 잔액이 0원 그대로다. */
    private fun assertUnchanged(userId: Long) {
        assertThat(entityManager.balanceOf(accountIdOf(userId))).isZero()
    }
}
