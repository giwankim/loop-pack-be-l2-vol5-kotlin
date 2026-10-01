package com.loopers.interfaces.api

import com.loopers.config.security.AdminSecurityConfig
import com.loopers.support.error.ErrorType
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/**
 * 본문을 읽다 난 오류(`HttpMessageNotReadableException`)가 [ApiControllerAdvice]의 분기마다 내는 응답을 고정한다.
 * Jackson 3으로 옮겨도 status, 오류 타입, 메시지가 그대로여야 한다. 메시지는 글자 하나까지 계약으로 본다.
 *
 * 카탈로그 분기는 상품 등록 본문으로 친다. 그 Request를 Controller가 본문으로 바로 받기 때문이다.
 * 본문 안의 도메인 오류는 주문 생성 본문으로 친다. 주문 Request를 만드는 역직렬화기가 [com.loopers.support.error.CoreException]을
 * 던진다. 본문은 요청자 확인보다 먼저 읽히므로 사용자를 등록하지 않는다.
 *
 * enum 값 불일치와 그 밖의 매핑 오류 분기는 지금 어느 본문에도 enum 필드가 없고 매핑 오류를 낼 길이 없어 HTTP로 닿지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
@Transactional
class RequestBodyErrorMockMvcTest(
    private val mockMvc: MockMvc,
) {
    companion object {
        private const val PRODUCTS = "/api-admin/v1/products"
        private const val ORDERS = "/api/v1/orders"
        private val ADMIN = user("admin").roles("ADMIN")
    }

    @Test
    fun `malformed JSON returns 400 with the general body-format message`() {
        postProduct(body = """{"brandId": 1, "name": "티셔츠",""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.") }
        }
    }

    @Test
    fun `content after the JSON body returns 400 with the general body-format message`() {
        postProduct(body = """{"brandId": 1, "name": "티셔츠", "price": 1000, "stock": 7}xyz""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.") }
        }
    }

    @Test
    fun `two JSON objects in a row return 400 with the general body-format message`() {
        postProduct(
            body = """{"brandId": 1, "name": "티셔츠", "price": 1000, "stock": 7}{"brandId": 1, "name": "바지"}""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.") }
        }
    }

    @Test
    fun `an array body returns 400 with the general body-format message`() {
        postProduct(body = """[]""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.") }
        }
    }

    @Test
    fun `a string body returns 400 with the general body-format message`() {
        postProduct(body = """"티셔츠"""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.") }
        }
    }

    /** enum이 아닌 타입에는 허용 값 안내가 없어 메시지가 공백으로 끝난다. 지금의 응답이 그렇다. */
    @Test
    fun `a value of the wrong type returns 400 naming the field, the value and the expected type`() {
        postProduct(body = """{"brandId": 1, "name": "티셔츠", "price": "abc", "stock": 7}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("필드 'price'의 값 'abc'이(가) 예상 타입(long)과 일치하지 않습니다. ") }
        }
    }

    @Test
    fun `a missing required field returns 400 naming the field`() {
        postProduct(body = """{"brandId": 1, "name": "티셔츠", "stock": 7}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("Bad Request") }
            jsonPath("$.meta.message") { value("필수 필드 'price'이(가) 누락되었습니다.") }
        }
    }

    @Test
    fun `a domain error raised while the body is read returns that error's own type and message`() {
        mockMvc.post(ORDERS) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"items": "티셔츠"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.meta.result") { value("FAIL") }
            jsonPath("$.meta.errorCode") { value("INVALID_POINT_ORDER_REQUEST") }
            jsonPath("$.meta.message") { value(ErrorType.INVALID_POINT_ORDER_REQUEST.message) }
        }
    }

    private fun postProduct(body: String): ResultActionsDsl =
        mockMvc.post(PRODUCTS) {
            with(ADMIN)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
}
