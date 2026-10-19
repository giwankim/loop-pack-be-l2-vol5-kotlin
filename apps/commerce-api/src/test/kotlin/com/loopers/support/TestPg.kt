package com.loopers.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
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

        /** PG 계약이 정한 합성 계정. 모든 요청의 `X-USER-ID`다. */
        private const val USER_ID = "135135"

        private const val PAYMENTS_PATH = "/api/v1/payments"
    }

    /** 생성을 받아들여 202와 PG 거래를 돌려준다. PG 상태는 문자열 그대로 싣는다. */
    fun acceptsCreate(
        pgOrderId: String,
        amount: Long,
        card: Card,
        transactionKey: String,
        pgStatus: String = "PENDING",
    ) {
        server.stubFor(
            createRequest(pgOrderId, amount, card).willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """{"transactionKey":"$transactionKey","orderId":"$pgOrderId","amount":$amount,"status":"$pgStatus"}""",
                    ),
            ),
        )
    }

    /** 생성에 HTTP 상태 [httpStatus]만 답하고 본문은 싣지 않는다. */
    fun answersCreate(pgOrderId: String, amount: Long, card: Card, httpStatus: Int) {
        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(aResponse().withStatus(httpStatus)))
    }

    /** PG가 받은 생성 요청 수. 헤더와 본문이 무엇이든 센다. */
    fun createCount(): Int {
        return server.findAll(postRequestedFor(urlEqualTo(PAYMENTS_PATH))).size
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
