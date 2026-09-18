package com.loopers.interfaces.api.v1.order

import com.loopers.domain.shared.RuleViolationException
import com.loopers.interfaces.api.ApiResponse
import com.loopers.support.error.ErrorType
import jakarta.validation.ConstraintViolationException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 기존 카탈로그의 JSON·도메인 오류 코드는 바꾸지 않는다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [OrderController::class])
class OrderControllerAdvice {
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        RuleViolationException::class,
    )
    fun invalidRequest(): ResponseEntity<ApiResponse<*>> {
        val error = ErrorType.INVALID_POINT_ORDER_REQUEST
        return ResponseEntity.status(error.status).body(ApiResponse.fail(error.code, error.message))
    }
}
