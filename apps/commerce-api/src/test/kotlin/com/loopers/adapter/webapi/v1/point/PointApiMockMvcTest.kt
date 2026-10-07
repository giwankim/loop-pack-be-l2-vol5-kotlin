package com.loopers.adapter.webapi.v1.point

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.support.balanceOf
import com.loopers.support.countPointAccounts
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * 고객 포인트 API. 요청자는 `X-USER-ID` 헤더로 식별하며 관리자 경계 밖이라 principal은 싣지 않는다.
 * 고객 경로가 인증 없이 지나가는 까닭은 [BaseWebApiAdapterTest]에 있다.
 *
 * 계정 없음과 충전이 잔액에 더해지는 규칙은 [com.loopers.application.point.provided.PointChargerTest]와
 * [com.loopers.application.point.provided.PointAccountFinderTest]가 MySQL 위에서 이미 고정한다.
 * 여기서는 헤더가 요청자로 이어지는지, 본문의 JSON 토큰을 어디까지 받는지, 오류가 어느 status와 code로
 * 내려가는지, 거절 뒤 잔액이 그대로인지를 본다(설계 5.10, 6). 요청 사이를 비우는 까닭은
 * [com.loopers.adapter.webapi.v1.product.ProductApiMockMvcTest]와 같다.
 */
class PointApiMockMvcTest : BaseWebApiAdapterTest() {
    companion object {
        private const val POINTS = "/api/v1/points"
        private const val CHARGE = "$POINTS/charge"
    }

    /** 이 티켓의 인수 조건인 흐름. 0원 → 충전 10,000 → 조회 10,000 → 충전 500 → 조회 10,500. */
    @Test
    fun `charging shows the balance right after and the balance read shows the current balance`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestGetBalance(user.id)).hasStatusOk().bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.balance").isEqualTo(0)

        val chargeBody = assertThat(requestCharge(user.id, json = """{"amount": 10000}""")).hasStatusOk().bodyJson()
        chargeBody.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        chargeBody.extractingPath("$.data.balance").isEqualTo(10_000)
        chargeBody.extractingPath("$.data.length()").isEqualTo(1)
        entityManager.flushAndClear()

        val detail = assertThat(requestGetBalance(user.id)).bodyJson()
        detail.extractingPath("$.data.balance").isEqualTo(10_000)

        val secondChargeBody = assertThat(requestCharge(user.id, json = """{"amount": 500}""")).hasStatusOk().bodyJson()
        secondChargeBody.extractingPath("$.data.balance").isEqualTo(10_500)
        entityManager.flushAndClear()

        val detailAfterSecondCharge = assertThat(requestGetBalance(user.id)).hasStatusOk().bodyJson()
        detailAfterSecondCharge.extractingPath("$.data.balance").isEqualTo(10_500)
    }

    @Test
    fun `charging and reading without the user header return 401`() {
        val body = assertThat(
            mvc.post().uri(CHARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount": 10000}"""),
        ).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.UNAUTHORIZED.message)

        val error = assertThat(mvc.get().uri(POINTS)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")
    }

    @Test
    fun `charging and reading as a user that does not exist return 401 and create no account`() {
        val body = assertThat(requestCharge(999L, json = """{"amount": 10000}""")).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")

        val error = assertThat(requestGetBalance(999L)).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("Unauthorized")

        assertThat(entityManager.countPointAccounts(999L)).isZero()
    }

    /** 헤더가 있으나 숫자가 아니면 요청자가 없는 것이 아니라 요청이 잘못된 것이다. Spring의 타입 변환이 거절한다(카탈로그 설계 5.27). */
    @Test
    fun `a user header that is not a number returns 400 on both APIs`() {
        val body = assertThat(
            mvc.post().uri(CHARGE)
                .header(UserIdHeader.NAME, "abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount": 10000}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")

        val error = assertThat(
            mvc.get().uri(POINTS).header(UserIdHeader.NAME, "abc"),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        error.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
    }

    /** 사용자는 있는데 계정이 없는 것은 데이터 불일치라 내부 오류다. 0원 계정을 만들어 주지 않는다(설계 5.9, 6 끝). */
    @Test
    fun `an existing user without an account gets 500 on both APIs and no account is created`() {
        prepareUserWithoutAccount()
        entityManager.flushAndClear()

        val body = assertThat(
            requestCharge(user.id, json = """{"amount": 10000}"""),
        ).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Internal Server Error")
        body.extractingPath("$.meta.message").isEqualTo(ErrorType.POINT_ACCOUNT_MISSING.message)

        val error = assertThat(requestGetBalance(user.id)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR).bodyJson()
        error.extractingPath("$.meta.message").isEqualTo(ErrorType.POINT_ACCOUNT_MISSING.message)

        assertThat(entityManager.countPointAccounts(user.id)).isZero()
    }

    /** 충전액은 카탈로그처럼 Jackson 기본대로 읽는다. 숫자 문자열과 소수 표기의 정수도 받는다(설계 5.10). */
    @Test
    fun `an amount sent as a numeric string or a whole decimal is charged`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestCharge(user.id, json = """{"amount": "5"}""")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.balance").isEqualTo(5)

        val chargeBody = assertThat(requestCharge(user.id, json = """{"amount": 5.0}""")).hasStatusOk().bodyJson()
        chargeBody.extractingPath("$.data.balance").isEqualTo(10)
        entityManager.flushAndClear()

        assertThat(entityManager.balanceOf(pointAccount.id)).isEqualTo(10)
    }

    /** 숫자로 읽을 수 없거나 `Long` 범위 밖인 값은 다른 본문 오류와 같은 범용 400이다(설계 5.10). */
    @Test
    fun `a missing, null, non-numeric or out-of-range amount returns 400 and changes nothing`() {
        prepareUser()
        entityManager.flushAndClear()
        val invalidJsons = listOf(
            """{}""",
            """{"amount": null}""",
            """{"amount": "abc"}""",
            """{"amount": true}""",
            """{"amount": [10000]}""",
            """{"amount": 100000000000000000000}""",
        )

        invalidJsons.forEach { json ->
            val body = assertThat(requestCharge(user.id, json = json)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
            body.extractingPath("$.meta.result").isEqualTo("FAIL")
            body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        }
        entityManager.flushAndClear()
        assertUnchanged(pointAccount.id)
    }

    /** 소수 금액을 Jackson 3이 어떻게 읽는지 고정한다. 바뀌면 이 테스트가 먼저 알린다(설계 5.10). */
    @Test
    fun `a fractional amount is truncated to its integer part`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestCharge(user.id, json = """{"amount": 1.5}""")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.balance").isEqualTo(1)
    }

    /** 알 수 없는 필드는 기존 정책대로 무시한다(설계 5.10). */
    @Test
    fun `an unknown field next to a valid amount is ignored`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestCharge(user.id, json = """{"amount": 10000, "balance": 1}""")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.balance").isEqualTo(10_000)
    }

    /** 0원 이하는 Request 제약이 거른다. 범용 400이며 메시지가 규칙을 말한다(카탈로그 설계 5.18). */
    @Test
    fun `charging zero or a negative amount returns 400 and changes nothing`() {
        prepareUser()
        entityManager.flushAndClear()

        listOf(0L, -1L).forEach { amount ->
            val body = assertThat(
                requestCharge(user.id, json = """{"amount": $amount}"""),
            ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
            body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
            body.extractingPath("$.meta.message").isEqualTo("충전액은 1원 이상이어야 합니다.")
        }
        entityManager.flushAndClear()

        assertUnchanged(pointAccount.id)
    }

    /**
     * 충전 후 잔액이 `Long` 범위를 넘으면 domain이 거절한다. 잔액은 그대로다(설계 5.7).
     * 첫 충전은 준비가 아니라 검증하는 요청이다. `Long` 최댓값의 충전액을 받는지 본다.
     */
    @Test
    fun `a charge that overflows the balance returns 400 and keeps the balance`() {
        prepareUser()
        assertThat(requestCharge(user.id, json = """{"amount": ${Long.MAX_VALUE}}""")).hasStatusOk()
        entityManager.flushAndClear()

        val body = assertThat(requestCharge(user.id, json = """{"amount": 1}""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("금액 계산 결과가 표현 범위를 넘습니다.")
        entityManager.flushAndClear()

        assertThat(entityManager.balanceOf(pointAccount.id)).isEqualTo(Long.MAX_VALUE)
    }

    /** 상품 가격의 10억 원 상한은 잔액에 적용되지 않는다(설계 5.7). */
    @Test
    fun `the balance may exceed the product price cap`() {
        prepareUser()
        entityManager.flushAndClear()

        val body = assertThat(requestCharge(user.id, json = """{"amount": 1000000001}""")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.balance").isEqualToLong(1_000_000_001L)
    }

    private fun requestCharge(userId: Long, json: String): MvcTestResult =
        mvc.post().uri(CHARGE)
            .header(UserIdHeader.NAME, userId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()

    private fun requestGetBalance(userId: Long): MvcTestResult =
        mvc.get().uri(POINTS).header(UserIdHeader.NAME, userId).exchange()

    /** 거절 뒤 잔액이 0원 그대로다. */
    private fun assertUnchanged(accountId: Long) {
        assertThat(entityManager.balanceOf(accountId)).isZero()
    }
}
