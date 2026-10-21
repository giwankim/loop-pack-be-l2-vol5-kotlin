package com.loopers.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgTransaction
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * 테스트 PG([EnableTestPg])의 장면에 이름을 붙이고, PG가 받은 요청을 센다.
 *
 * 모든 스텁은 `X-USER-ID`를 맞추고, 생성은 정확한 JSON 본문을, 거래 키 조회는 경로의 거래 키를, 목록 조회는 query의 `orderId`를 정확히
 * 맞춘다. 하나라도 틀린 요청은 스텁을 찾지 못해 WireMock의 404를 받는다. 생성은 그 404를 결과 미확인으로 돌려주므로 테스트가 실패한다.
 * 조회는 그렇지 않다. 거래 키 조회는 404를 찾은 것 없음으로, 목록 조회는 조회 불가로 읽으므로, 테스트가 기대한 결과와 같을 수 있다.
 * 그래서 [unmatchedRequests]가 스텁과 맞지 않은 요청을 돌려주고, 조회를 쓰는 테스트는 그것이 비었는지 본다.
 *
 * JSON은 손으로 적는다. 필드 이름과 JSON 타입(금액은 숫자)까지 PG 계약이다. 무작위 지연은 쓰지 않는다. 금액은 PG가 보는 그대로 원화 정수다.
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

        /** PG 계약의 본문 형식. 장면은 따로 정하지 않으면 모두 이것으로 싣는다. */
        private const val JSON_CONTENT_TYPE = "application/json"

        /** [redirectsCreate]가 가리키는 경로. 어댑터가 리다이렉트를 따라가면 PG가 이 경로로 요청을 하나 더 받는다. */
        private const val REDIRECT_PATH = "/api/v1/payments/redirected"

        /** 두 조회의 경로. 거래 키 조회는 `/api/v1/payments/{거래 키}`, 목록 조회는 query만 다른 `/api/v1/payments`다. */
        private const val LOOKUP_PATHS = "$PAYMENTS_PATH(/[^/]+)?"

        /** [stallsCreate]와 [stallsLookup]의 고정 지연. 운영의 응답 한도 1 s를 넘는다. */
        private const val STALL_MILLIS = 1_500
    }

    /** 생성을 받아들여 202와 PG 거래를 돌려준다. PG 상태는 문자열 그대로 싣는다. */
    fun acceptsCreate(
        pgOrderId: String,
        amount: Long,
        card: CreditCard,
        transactionKey: String,
        pgStatus: String = "PENDING",
    ) {
        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(accepted(pgOrderId, amount, transactionKey, pgStatus)))
    }

    /**
     * 생성을 받아들이지만 [STALL_MILLIS] 동안 답하지 않는다. 운영의 응답 한도에서는 어댑터가 먼저 기다리기를 그만두므로,
     * PG에는 PG 거래가 생겼는데 어댑터는 그 답을 받지 못한다. 지연은 고정이다.
     */
    fun stallsCreate(pgOrderId: String, amount: Long, card: CreditCard, transactionKey: String) {
        server.stubFor(
            createRequest(pgOrderId, amount, card).willReturn(
                accepted(pgOrderId, amount, transactionKey, pgStatus = "PENDING").withFixedDelay(STALL_MILLIS),
            ),
        )
    }

    /**
     * 생성을 받아 PG 거래를 저장한 뒤 응답 없이 연결을 끊는다(`Fault.EMPTY_RESPONSE`). WireMock은 요청 본문을 다 읽은 뒤 끊는다
     * (wiremock-spring-boot 탐침 보고서 §3의 b).
     */
    fun losesCreateResponse(pgOrderId: String, amount: Long, card: CreditCard) {
        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))
    }

    /** 생성에 307로 답해 [REDIRECT_PATH]로 돌려보낸다. 따라간 요청은 [requestCount]에 더해진다. */
    fun redirectsCreate(pgOrderId: String, amount: Long, card: CreditCard) {
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
    fun rejectsBeforeCreate(pgOrderId: String, amount: Long, card: CreditCard) {
        val body = """{"code":"$REJECTED_BEFORE_CREATE","orderId":"$pgOrderId","amount":$amount,"transactionCreated":false}"""

        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(json(422, body)))
    }

    /**
     * 생성에 HTTP 상태 [httpStatus]로 답한다. [body]가 있으면 [contentType]으로 그대로 싣고, 없으면 본문 없이 답한다.
     * [contentType]의 기본값은 JSON이다.
     */
    fun answersCreate(
        pgOrderId: String,
        amount: Long,
        card: CreditCard,
        httpStatus: Int,
        body: String? = null,
        contentType: String = JSON_CONTENT_TYPE,
    ) {
        server.stubFor(createRequest(pgOrderId, amount, card).willReturn(response(httpStatus, body, contentType)))
    }

    /** PG가 받은 생성 요청 수. 헤더와 본문이 무엇이든 센다. */
    fun createCount(): Int {
        return server.findAll(postRequestedFor(urlEqualTo(PAYMENTS_PATH))).size
    }

    /** PG가 받은 모든 요청 수. 메서드와 경로가 무엇이든, 맞는 스텁이 없던 요청까지 센다. */
    fun requestCount(): Int {
        return server.findAll(anyRequestedFor(anyUrl())).size
    }

    /** 거래 키 조회에 200과 PG 거래 [transaction]을 돌려준다. 조회하는 거래 키는 그 PG 거래의 것이다. */
    fun hasTransaction(transaction: PgTransaction) {
        server.stubFor(retrieveRequest(transaction.transactionKey).willReturn(json(200, transactionJson(transaction))))
    }

    /** PG 주문 번호 [pgOrderId]의 목록 조회에 200과 PG 거래 [transactions]의 JSON 배열을 돌려준다. PG 거래가 없으면 빈 배열이다. */
    fun hasTransactions(pgOrderId: String, vararg transactions: PgTransaction) {
        val body = transactions.joinToString(separator = ",", prefix = "[", postfix = "]") { transactionJson(it) }

        server.stubFor(listRequest(pgOrderId).willReturn(json(200, body)))
    }

    /** 거래 키 조회에 HTTP 상태 [httpStatus]로 답한다. [body]가 있으면 JSON으로 그대로 싣고, 없으면 본문 없이 답한다. */
    fun answersRetrieve(transactionKey: String, httpStatus: Int, body: String? = null) {
        server.stubFor(retrieveRequest(transactionKey).willReturn(response(httpStatus, body)))
    }

    /** PG 주문 번호 [pgOrderId]의 목록 조회에 HTTP 상태 [httpStatus]로 답한다. [body]는 [answersRetrieve]와 같다. */
    fun answersList(pgOrderId: String, httpStatus: Int, body: String? = null) {
        server.stubFor(listRequest(pgOrderId).willReturn(response(httpStatus, body)))
    }

    /**
     * 모든 조회를 받고 [STALL_MILLIS] 뒤에야 본문 없는 503으로 답한다. 운영의 응답 한도 1 s 안에 답을 기다리는 클라이언트에게
     * 그 답은 닿지 않는다. 한도가 더 긴 클라이언트에게도 두 조회 모두 조회 불가다. 404였다면 거래 키 조회가 찾은 것 없음이라는 사실로 읽었을 것이다.
     */
    fun stallsLookup() {
        server.stubFor(anyLookup().willReturn(aResponse().withStatus(503).withFixedDelay(STALL_MILLIS)))
    }

    /** 모든 조회를 받은 뒤 아무것도 답하지 않고 연결을 끊는다(`Fault.EMPTY_RESPONSE`). WireMock은 요청을 다 읽은 뒤에 끊는다. */
    fun losesLookupResponse() {
        server.stubFor(anyLookup().willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)))
    }

    /** PG가 받은 조회 수. 거래 키 조회와 목록 조회를 함께 세며, 헤더와 query가 무엇이든 센다. */
    fun lookupCount(): Int {
        return server.findAll(getRequestedFor(urlPathMatching(LOOKUP_PATHS))).size
    }

    /** PG가 받은 요청 가운데 어느 장면의 스텁과도 맞지 않은 것. 그 요청들은 WireMock의 404를 받았다. */
    fun unmatchedRequests(): List<LoggedRequest> {
        return server.findAllUnmatchedRequests()
    }

    /** 거래 키나 query가 무엇이든 두 조회 모두. */
    private fun anyLookup(): MappingBuilder {
        return get(urlPathMatching(LOOKUP_PATHS))
            .withHeader("X-USER-ID", equalTo(USER_ID))
    }

    /** 거래 키 조회. WireMock은 경로를 디코딩하지 않고 견주므로, 경로의 거래 키를 URI builder처럼 인코딩해 맞춘다. */
    private fun retrieveRequest(transactionKey: String): MappingBuilder {
        return get(urlPathEqualTo("$PAYMENTS_PATH/${UriUtils.encodePathSegment(transactionKey, StandardCharsets.UTF_8)}"))
            .withHeader("X-USER-ID", equalTo(USER_ID))
    }

    /** 목록 조회. query의 `orderId`가 [pgOrderId]와 정확히 같아야 맞는다. WireMock은 query 값을 디코딩해 견준다. */
    private fun listRequest(pgOrderId: String): MappingBuilder {
        return get(urlPathEqualTo(PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(pgOrderId))
            .withHeader("X-USER-ID", equalTo(USER_ID))
    }

    private fun accepted(pgOrderId: String, amount: Long, transactionKey: String, pgStatus: String): ResponseDefinitionBuilder {
        return json(202, transactionJson(transactionKey, pgOrderId, amount, pgStatus))
    }

    private fun transactionJson(transaction: PgTransaction): String {
        return transactionJson(
            transactionKey = transaction.transactionKey,
            pgOrderId = transaction.pgOrderId,
            amount = transaction.amount.amount.longValueExact(),
            pgStatus = transaction.status.name,
        )
    }

    /** PG 거래의 JSON. 생성의 202와 두 조회가 같은 모양을 싣는다. 필드 이름과 JSON 타입(금액은 숫자, PG 상태는 문자열)까지 PG 계약이다. */
    private fun transactionJson(transactionKey: String, pgOrderId: String, amount: Long, pgStatus: String): String {
        return """{"transactionKey":"$transactionKey","orderId":"$pgOrderId","amount":$amount,"status":"$pgStatus"}"""
    }

    /** [body]가 있으면 [contentType]으로 싣고, 없으면 본문 없이 HTTP 상태 [httpStatus]만 답한다. */
    private fun response(httpStatus: Int, body: String?, contentType: String = JSON_CONTENT_TYPE): ResponseDefinitionBuilder {
        if (body == null) {
            return aResponse().withStatus(httpStatus)
        }

        return aResponse()
            .withStatus(httpStatus)
            .withHeader("Content-Type", contentType)
            .withBody(body)
    }

    private fun json(httpStatus: Int, body: String): ResponseDefinitionBuilder {
        return response(httpStatus, body)
    }

    private fun createRequest(pgOrderId: String, amount: Long, card: CreditCard): MappingBuilder {
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
