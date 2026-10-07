package com.loopers.adapter.webapi

import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * 본문을 읽다 난 오류(`HttpMessageNotReadableException`)가 [com.loopers.adapter.ApiControllerAdvice]의 분기마다 내는 응답을 고정한다.
 * Jackson 3으로 옮겨도 status, 오류 타입, 메시지가 그대로여야 한다. 메시지는 글자 하나까지 계약으로 본다.
 *
 * 분기는 상품 등록 본문으로 친다. 그 Request를 Controller가 본문으로 바로 받기 때문이다.
 *
 * enum 값 불일치와 그 밖의 매핑 오류 분기는 지금 어느 본문에도 enum 필드가 없고 매핑 오류를 낼 길이 없어 HTTP로 닿지 않는다.
 */
class RequestBodyErrorMockMvcTest : BaseWebApiAdapterTest() {
    companion object {
        private const val PRODUCTS = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")
    }

    @Test
    fun `malformed JSON returns 400 with the general body-format message`() {
        val body = assertThat(
            postProduct(json = """{"brandId": 1, "name": "티셔츠","""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.")
    }

    @Test
    fun `content after the JSON body returns 400 with the general body-format message`() {
        val body = assertThat(
            postProduct(json = """{"brandId": 1, "name": "티셔츠", "price": 1000, "stock": 7}xyz"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.")
    }

    @Test
    fun `two JSON objects in a row return 400 with the general body-format message`() {
        val body = assertThat(
            postProduct(json = """{"brandId": 1, "name": "티셔츠", "price": 1000, "stock": 7}{"brandId": 1, "name": "바지"}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.")
    }

    @Test
    fun `an array body returns 400 with the general body-format message`() {
        val body = assertThat(postProduct(json = """[]""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.")
    }

    @Test
    fun `a string body returns 400 with the general body-format message`() {
        val body = assertThat(postProduct(json = """"티셔츠"""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.")
    }

    /** enum이 아닌 타입에는 허용 값 안내가 없어 메시지가 공백으로 끝난다. 지금의 응답이 그렇다. */
    @Test
    fun `a value of the wrong type returns 400 naming the field, the value and the expected type`() {
        val body = assertThat(
            postProduct(json = """{"brandId": 1, "name": "티셔츠", "price": "abc", "stock": 7}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("필드 'price'의 값 'abc'이(가) 예상 타입(long)과 일치하지 않습니다. ")
    }

    @Test
    fun `a missing required field returns 400 naming the field`() {
        val body = assertThat(
            postProduct(json = """{"brandId": 1, "name": "티셔츠", "stock": 7}"""),
        ).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").isEqualTo("필수 필드 'price'이(가) 누락되었습니다.")
    }

    private fun postProduct(json: String): MvcTestResult =
        mvc.post().uri(PRODUCTS)
            .with(ADMIN)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()
}
