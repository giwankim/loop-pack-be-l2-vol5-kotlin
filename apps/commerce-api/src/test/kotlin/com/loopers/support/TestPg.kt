package com.loopers.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import com.loopers.domain.payment.Card

/**
 * 테스트 PG([EnableTestPg])의 장면에 이름을 붙이고, PG가 받은 요청을 센다.
 *
 * 모든 스텁은 `X-USER-ID`와 정확한 JSON 본문을 맞춘다. 헤더, 필드, JSON 타입이 하나라도 틀린 요청은 스텁을 찾지 못해 WireMock의 404를 받고,
 * 어댑터는 그것을 결과 미확인으로 돌려주므로 테스트가 실패한다. JSON은 손으로 적는다. 필드 이름과 JSON 타입(금액은 숫자)까지 PG 계약이다.
 * 무작위 지연은 쓰지 않는다. 금액은 PG가 보는 그대로 원화 정수다.
 *
 * [callbackUrl]은 생성 요청에 실려 와야 하는 알림 주소다. 어댑터는 그것을 요청이 아니라 설정 `pg.callback-url`에서 읽는다.
 * 기본값은 test 프로필의 값이다. 설정을 바꾼 테스트는 바꾼 값을 넘긴다.
 */
class TestPg(
    private val server: WireMockServer,
    private val callbackUrl: String = "http://127.0.0.1:8080/api/v1/payments/callback",
) {
    companion object {
        /** `@EnableTestPg`의 서버 이름. `@InjectWireMock`에 넘긴다. */
        const val NAME = "pg"

        /** 테스트 PG가 생성 전 거절([rejectsBeforeCreate])에만 싣는 code. 그것을 닮은 본문을 손으로 적는 테스트도 쓴다. */
        const val REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE"

        /** PG 계약이 정한 합성 계정. 모든 요청의 `X-USER-ID`다. */
        private const val USER_ID = "135135"

        private const val PAYMENTS_PATH = "/api/v1/payments"

        /** [redirectsCreate]가 가리키는 경로. 어댑터가 리다이렉트를 따라가면 PG가 이 경로로 요청을 하나 더 받는다. */
        private const val REDIRECT_PATH = "/api/v1/payments/redirected"

        /** [stalls]의 고정 지연. 운영의 응답 한도 1 s를 넘는다. */
        private const val STALL_MILLIS = 1_500
    }

    /** 생성을 받아들여 202와 PG 거래를 돌려준다. PG 상태는 문자열 그대로 싣는다. */
    fun acceptsCreate(
        pgOrderId: String,
        amount: Long,
        card: Card,
        transactionKey: String,
        pgStatus: String = "PENDING",
    ) {
        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(accepted(pgOrderId, amount, transactionKey, pgStatus)))
    }

    /**
     * 생성을 받아들이지만 [STALL_MILLIS] 동안 답하지 않는다. 운영의 응답 한도에서는 어댑터가 먼저 기다리기를 그만두므로,
     * PG에는 거래가 생겼는데 어댑터는 그 답을 받지 못한다. 지연은 고정이다.
     */
    fun stalls(pgOrderId: String, amount: Long, card: Card, transactionKey: String) {
        server.stubFor(
            createRequest(pgOrderId, amount, card).willReturn(
                accepted(pgOrderId, amount, transactionKey, pgStatus = "PENDING").withFixedDelay(STALL_MILLIS),
            ),
        )
    }

    /**
     * 생성을 받아 거래를 저장한 뒤 응답 없이 연결을 끊는다(`Fault.EMPTY_RESPONSE`). WireMock은 요청 본문을 다 읽은 뒤 끊는다
     * (wiremock-spring-boot 탐침 보고서 §3의 b).
     */
    fun losesCreateResponse(pgOrderId: String, amount: Long, card: Card) {
        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))
    }

    /** 생성에 307로 답해 [REDIRECT_PATH]로 돌려보낸다. 따라간 요청은 [requestCount]에 더해진다. */
    fun redirectsCreate(pgOrderId: String, amount: Long, card: Card) {
        server.stubFor(
            createRequest(pgOrderId, amount, card).willReturn(
                aResponse().withStatus(307).withHeader("Location", REDIRECT_PATH),
            ),
        )
    }

    /**
     * 생성 전 거절. PG 거래를 만들기 전에 끝내고 이 요청으로 앞으로도 만들지 않는다는 422다(ADR 0022 규칙 2).
     * 본문은 받은 PG 주문 번호와 금액을 되돌려 준다.
     */
    fun rejectsBeforeCreate(pgOrderId: String, amount: Long, card: Card) {
        val body = """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$pgOrderId","amount":$amount,"transactionCreated":false}"""

        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(json(422, body)))
    }

    /** 생성에 HTTP 상태 [httpStatus]로 답한다. [body]가 있으면 JSON으로 그대로 싣고, 없으면 본문 없이 답한다. */
    fun answersCreate(pgOrderId: String, amount: Long, card: Card, httpStatus: Int, body: String? = null) {
        val response = if (body == null) {
            aResponse().withStatus(httpStatus)
        } else {
            json(httpStatus, body)
        }

        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(response))
    }

    /** PG가 받은 생성 요청 수. 헤더와 본문이 무엇이든 센다. */
    fun createCount(): Int {
        return server.findAll(postRequestedFor(urlEqualTo(PAYMENTS_PATH))).size
    }

    /** PG가 받은 모든 요청 수. 메서드와 경로가 무엇이든, 맞는 스텁이 없던 요청까지 센다. */
    fun requestCount(): Int {
        return server.findAll(anyRequestedFor(anyUrl())).size
    }

    private fun accepted(pgOrderId: String, amount: Long, transactionKey: String, pgStatus: String): ResponseDefinitionBuilder {
        return json(202, """{"transactionKey":"$transactionKey","orderId":"$pgOrderId","amount":$amount,"status":"$pgStatus"}""")
    }

    private fun json(httpStatus: Int, body: String): ResponseDefinitionBuilder {
        return aResponse()
            .withStatus(httpStatus)
            .withHeader("Content-Type", "application/json")
            .withBody(body)
    }

    private fun createRequest(pgOrderId: String, amount: Long, card: Card): MappingBuilder {
        val json = """
            {
              "orderId": "$pgOrderId",
              "cardType": "${card.type}",
              "cardNo": "${card.number}",
              "amount": $amount,
              "callbackUrl": "$callbackUrl"
            }
        """

        return post(urlEqualTo(PAYMENTS_PATH))
            .withHeader("X-USER-ID", equalTo(USER_ID))
            .withRequestBody(equalToJson(json))
    }
}
