package com.loopers.interfaces.api

import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.domain.product.InvalidStockException
import com.loopers.interfaces.api.v1.brand.BrandAdminController
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

/**
 * 검증 예외 두 종류와 도메인 규칙 위반이 같은 400 응답으로 옮겨지는지 본다. Controller 경로는 MockMvc 테스트가 덮고,
 * Service 경로(`ConstraintViolationException`)는 Controller가 먼저 거르므로 HTTP로는 닿지 않아 advice를 직접 부른다.
 * 도메인 경로(`RuleViolationException`)도 요청 검증이 같은 규칙을 먼저 거르므로(설계 5.18) 마찬가지로 직접 부른다.
 */
class ApiControllerAdviceTest {
    private val advice = ApiControllerAdvice()

    @Test
    fun `constraint violations from a validated service become one 400 message ordered by property`() {
        val validator = Validation.buildDefaultValidatorFactory().validator
        val violations = validator.validate(ProductAdminRegisterRequest(brandId = 1L, name = " ", price = 0, stock = -1))
        val exception = ConstraintViolationException(violations)

        val response = advice.handleConstraintViolation(exception)

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
            { assertThat(response.body?.meta?.errorCode).isEqualTo("Bad Request") },
            {
                assertThat(response.body?.meta?.message)
                    .isEqualTo("상품 이름은 공백일 수 없습니다. 상품 가격은 1원 이상이어야 합니다. 재고는 0 이상이어야 합니다.")
            },
        )
    }

    @Test
    fun `rule violations from the domain become a 400 carrying the rule's message`() {
        val exception = InvalidStockException("재고는 0 이상이어야 합니다.")

        val response = advice.handleRuleViolation(exception)

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
            { assertThat(response.body?.meta?.errorCode).isEqualTo("Bad Request") },
            { assertThat(response.body?.meta?.message).isEqualTo("재고는 0 이상이어야 합니다.") },
        )
    }

    @Test
    fun `field errors from a controller body become one 400 message ordered by field`() {
        val bindingResult = BeanPropertyBindingResult(Any(), "request").apply {
            addError(FieldError("request", "stock", "재고는 0 이상이어야 합니다."))
            addError(FieldError("request", "name", "이름은 공백일 수 없습니다."))
        }
        val registerMethod = BrandAdminController::class.java.getMethod("register", BrandAdminRegisterRequest::class.java)
        val exception = MethodArgumentNotValidException(MethodParameter(registerMethod, 0), bindingResult)

        val response = advice.handleMethodArgumentNotValid(exception)

        assertAll(
            { assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST) },
            { assertThat(response.body?.meta?.errorCode).isEqualTo("Bad Request") },
            { assertThat(response.body?.meta?.message).isEqualTo("이름은 공백일 수 없습니다. 재고는 0 이상이어야 합니다.") },
        )
    }
}
