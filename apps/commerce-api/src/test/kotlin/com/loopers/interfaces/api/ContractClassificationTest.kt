package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.example.ExampleModel
import com.loopers.infrastructure.example.ExampleJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.context.TestConstructor
import org.springframework.test.http.HttpMessageContentConverter
import org.springframework.test.json.JsonContent
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

/**
 * 1주차 관찰 테스트.
 *
 * 새 기능을 만들지 않고, 이미 존재하는 GET /api/v1/examples/{id} 가 네 종류의 입력에
 * 실제로 어떤 외부 계약(HTTP status / meta.result / meta.errorCode / data 유무)을 돌려주는지 고정한다.
 * 여기서 확인한 값은 docs/week1/order-discount-contract.md 의 관찰 표와 같아야 한다.
 *
 * 응답 body 는 ApiResponse 로 역직렬화하지 않고 raw JSON 문자열을 JsonPath 로 읽는다.
 * 미매핑 URL 처럼 body 모양을 미리 가정할 수 없는 입력도 같은 방식으로 관찰하기 위해서다.
 * JacksonConfig 가 NON_NULL 로 직렬화하므로 "값이 null" 은 wire 에서 "키 없음" 으로 나타난다.
 * ApiResponse 로 역직렬화하면 "키 없음" 과 "null" 이 같은 값으로 합쳐져 이 차이를 관찰할 수 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ContractClassificationTest(
    restClientBuilder: RestClient.Builder,
    @LocalServerPort port: Int,
    private val exampleJpaRepository: ExampleJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
    objectMapper: ObjectMapper,
) {
    companion object {
        private const val EXAMPLES_PATH = "/api/v1/examples"
        private const val UNMAPPED_PATH = "/api/v1/no-such-resource/1"

        private const val RESULT_SUCCESS = "SUCCESS"
        private const val RESULT_FAIL = "FAIL"

        // 요청자가 실제로 보게 되는 errorCode 문자열. ErrorType 의 내부 이름이 아니라 wire 값을 그대로 적는다.
        private const val ERROR_CODE_BAD_REQUEST = "Bad Request"
        private const val ERROR_CODE_NOT_FOUND = "Not Found"
    }

    // 클라이언트는 Spring 기본값(4xx/5xx 에서 예외)을 유지한다. 에러 허용은 get() 호출 단위로 선언한다.
    private val restClient: RestClient = restClientBuilder
        .baseUrl("http://localhost:$port")
        .build()

    // JsonContent.convertTo() 가 JsonPath 값을 원하는 타입으로 바꿀 때 쓰는 converter. 앱과 같은 ObjectMapper 를 사용한다.
    private val jsonConverter: HttpMessageContentConverter =
        HttpMessageContentConverter.of(MappingJackson2HttpMessageConverter(objectMapper))

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    /**
     * 이 테스트는 에러 응답의 status 와 body 자체를 관찰 대상으로 삼으므로,
     * 이 호출에 한해 4xx/5xx 를 예외로 바꾸지 않고 항상 ResponseEntity 로 돌려받는다.
     */
    private fun get(path: String): ResponseEntity<String> =
        restClient.get().uri(path).retrieve()
            .onStatus(HttpStatusCode::isError) { _, _ -> }
            .toEntity<String>()

    private fun jsonOf(response: ResponseEntity<String>): JsonContent =
        JsonContent(response.body ?: "{}", jsonConverter)

    @DisplayName("GET /api/v1/examples/{id} 계약 분류")
    @Nested
    inner class ExampleApi {
        @DisplayName("존재하는 숫자 ID: 200 OK, meta.result=SUCCESS, errorCode 없음, data 있음")
        @Test
        fun existingNumericId_returnsSuccessWithData() {
            // arrange
            val saved = exampleJpaRepository.save(ExampleModel(name = "예시 제목", description = "예시 설명"))

            // act
            val response = get("$EXAMPLES_PATH/${saved.id}")
            val json = jsonOf(response)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(json).extractingPath("$.meta.result").isEqualTo(RESULT_SUCCESS) },
                { assertThat(json).doesNotHavePath("$.meta.errorCode") },
                { assertThat(json).hasPath("$.data") },
                { assertThat(json).extractingPath("$.data.id").convertTo(Long::class.java).isEqualTo(saved.id) },
            )
        }

        @DisplayName("숫자가 아닌 ID(abc): 400 BAD_REQUEST, meta.result=FAIL, errorCode=Bad Request, data 없음")
        @Test
        fun nonNumericId_returnsBadRequestWithoutData() {
            // act
            val response = get("$EXAMPLES_PATH/abc")
            val json = jsonOf(response)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
                { assertThat(json).extractingPath("$.meta.result").isEqualTo(RESULT_FAIL) },
                { assertThat(json).extractingPath("$.meta.errorCode").isEqualTo(ERROR_CODE_BAD_REQUEST) },
                { assertThat(json).doesNotHavePath("$.data") },
            )
        }

        @DisplayName("존재하지 않는 숫자 ID: 404 NOT_FOUND, meta.result=FAIL, errorCode=Not Found, data 없음")
        @Test
        fun missingNumericId_returnsNotFoundWithoutData() {
            // arrange
            val missingId = 999_999L

            // act
            val response = get("$EXAMPLES_PATH/$missingId")
            val json = jsonOf(response)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(json).extractingPath("$.meta.result").isEqualTo(RESULT_FAIL) },
                { assertThat(json).extractingPath("$.meta.errorCode").isEqualTo(ERROR_CODE_NOT_FOUND) },
                { assertThat(json).doesNotHavePath("$.data") },
            )
        }
    }

    @DisplayName("미매핑 URL 계약 분류")
    @Nested
    inner class UnmappedUrl {
        @DisplayName("연결된 handler 가 없는 URL: 404 NOT_FOUND, meta.result=FAIL, errorCode=Not Found, data 없음")
        @Test
        fun unmappedUrl_returnsNotFoundWithoutData() {
            // act
            val response = get(UNMAPPED_PATH)
            val json = jsonOf(response)

            // assert
            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND) },
                { assertThat(json).extractingPath("$.meta.result").isEqualTo(RESULT_FAIL) },
                { assertThat(json).extractingPath("$.meta.errorCode").isEqualTo(ERROR_CODE_NOT_FOUND) },
                { assertThat(json).doesNotHavePath("$.data") },
            )
        }
    }
}
