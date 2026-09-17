package com.loopers.interfaces.api.brand

import com.jayway.jsonpath.JsonPath
import com.loopers.config.security.AdminSecurityConfig
import com.loopers.domain.brand.Brand
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminSecurityConfig::class)
class BrandAdminV1ApiE2ETest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/brands"
        private val ADMIN = user("admin").roles("ADMIN")
        private val CUSTOMER = user("customer").roles("USER")
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    /** 쓰기 요청이므로 거절 경로에서도 csrf 토큰을 넣는다. [principal]이 null이면 식별 없는 요청이다. */
    private fun postBrand(name: String, principal: RequestPostProcessor? = ADMIN) = mockMvc.post(ENDPOINT) {
        principal?.let { with(it) }
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": "$name"}"""
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    inner class Register {
        @DisplayName("관리자가 이름을 주면, 201과 등록된 브랜드를 돌려주고 같은 브랜드가 다시 조회된다.")
        @Test
        fun returnsCreatedBrand_whenAdminRegisters() {
            // act
            val result = postBrand(name = " 루퍼스 ").andExpect {
                status { isCreated() }
                jsonPath("$.meta.result") { value("SUCCESS") }
                jsonPath("$.data.id") { isNumber() }
                jsonPath("$.data.name") { value("루퍼스") }
                jsonPath("$.data.createdAt") { value(notNullValue()) }
                jsonPath("$.data.updatedAt") { value(notNullValue()) }
            }.andReturn()

            // assert
            val id = JsonPath.read<Number>(result.response.contentAsString, "$.data.id").toLong()
            mockMvc.get("$ENDPOINT/$id") { with(ADMIN) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.data.id") { value(id) }
                    jsonPath("$.data.name") { value("루퍼스") }
                }
        }

        @DisplayName("이름이 공백뿐이면, 400 BAD_REQUEST를 돌려주고 저장하지 않는다.")
        @Test
        fun returnsBadRequest_whenNameIsBlank() {
            // act
            postBrand(name = "   ").andExpect {
                status { isBadRequest() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Bad Request") }
                jsonPath("$.meta.message") { value(containsString("브랜드 이름")) }
            }

            // assert
            assertThat(brandJpaRepository.count()).isZero()
        }

        @DisplayName("살아 있는 브랜드와 같은 이름이면, 409 CONFLICT를 돌려주고 새로 저장하지 않는다.")
        @Test
        fun returnsConflict_whenNameIsTaken() {
            // arrange
            brandJpaRepository.save(Brand("루퍼스"))

            // act
            postBrand(name = "루퍼스").andExpect {
                status { isConflict() }
                jsonPath("$.meta.result") { value("FAIL") }
                jsonPath("$.meta.errorCode") { value("Conflict") }
                jsonPath("$.meta.message") { value(containsString("같은 이름의 브랜드")) }
            }

            // assert
            assertThat(brandJpaRepository.count()).isOne()
        }

        @DisplayName("일반 사용자가 부르면, 403을 돌려주고 저장하지 않는다.")
        @Test
        fun returnsForbidden_whenCallerIsNotAdmin() {
            // act
            postBrand(name = "루퍼스", principal = CUSTOMER).andExpect {
                status { isForbidden() }
            }

            // assert
            assertThat(brandJpaRepository.count()).isZero()
        }

        @DisplayName("식별 없이 부르면, 403을 돌려주고 저장하지 않는다.")
        @Test
        fun returnsForbidden_whenCallerIsAnonymous() {
            // act
            postBrand(name = "루퍼스", principal = null).andExpect {
                status { isForbidden() }
            }

            // assert
            assertThat(brandJpaRepository.count()).isZero()
        }
    }

    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    @Nested
    inner class GetBrand {
        @DisplayName("없는 식별자면, 404 NOT_FOUND를 돌려준다.")
        @Test
        fun returnsNotFound_whenBrandDoesNotExist() {
            mockMvc.get("$ENDPOINT/999") { with(ADMIN) }
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.meta.result") { value("FAIL") }
                    jsonPath("$.meta.errorCode") { value("Not Found") }
                    jsonPath("$.meta.message") { value(containsString("브랜드")) }
                }
        }

        @DisplayName("일반 사용자가 부르면, 403을 돌려준다.")
        @Test
        fun returnsForbidden_whenCallerIsNotAdmin() {
            // arrange
            val brand = brandJpaRepository.save(Brand("루퍼스"))

            // act & assert
            mockMvc.get("$ENDPOINT/${brand.id}") { with(CUSTOMER) }
                .andExpect { status { isForbidden() } }
        }

        @DisplayName("식별 없이 부르면, 403을 돌려준다.")
        @Test
        fun returnsForbidden_whenCallerIsAnonymous() {
            // arrange
            val brand = brandJpaRepository.save(Brand("루퍼스"))

            // act & assert
            mockMvc.get("$ENDPOINT/${brand.id}")
                .andExpect { status { isForbidden() } }
        }
    }
}
