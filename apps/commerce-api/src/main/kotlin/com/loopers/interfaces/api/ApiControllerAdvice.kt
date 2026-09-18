package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.loopers.domain.shared.RuleViolationException
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.servlet.resource.NoResourceFoundException

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class ApiControllerAdvice {
    @ExceptionHandler
    fun handleCoreException(e: CoreException): ResponseEntity<ApiResponse<*>> {
        log.warn(e) { "CoreException : ${e.message}" }
        return failureResponse(errorType = e.errorType)
    }

    @ExceptionHandler
    fun handleRuleViolation(e: RuleViolationException): ResponseEntity<ApiResponse<*>> {
        log.warn(e) { "RuleViolationException : ${e.message}" }
        return failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = e.message)
    }

    /** Controller의 `@Valid @RequestBody`가 거른 입력. 필드 오류를 필드 이름 순으로 이어 한 메시지로 준다. */
    @ExceptionHandler
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<*>> {
        val message = e.bindingResult.fieldErrors
            .sortedWith(compareBy({ it.field }, { it.defaultMessage }))
            .joinToString(" ") { it.defaultMessage ?: "필드 '${it.field}'의 값이 잘못되었습니다." }
        log.warn { "MethodArgumentNotValidException : $message" }
        return failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = message.ifBlank { null })
    }

    /**
     * `@Validated` Service의 메서드 검증이 거른 입력. Controller가 Request를 본문으로 바로 받는 카탈로그에서는
     * Controller를 거치지 않은 호출에서만 여기까지 온다. HTTP 입력 DTO가 Request를 만드는 포인트·주문에서는
     * HTTP 요청도 여기로 온다(포인트·주문 설계 12.4).
     */
    @ExceptionHandler
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ApiResponse<*>> {
        val message = e.constraintViolations
            .sortedWith(compareBy({ it.propertyPath.toString() }, { it.message }))
            .joinToString(" ") { it.message }
        log.warn { "ConstraintViolationException : $message" }
        return failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = message.ifBlank { null })
    }

    @ExceptionHandler
    fun handleMethodArgumentTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<*>> {
        val name = e.name
        val type = e.requiredType?.simpleName ?: "unknown"
        val value = e.value ?: "null"
        val message = "요청 파라미터 '$name' (타입: $type)의 값 '$value'이(가) 잘못되었습니다."
        return failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = message)
    }

    @ExceptionHandler
    fun handleMissingServletRequestParameter(e: MissingServletRequestParameterException): ResponseEntity<ApiResponse<*>> {
        val name = e.parameterName
        val type = e.parameterType
        val message = "필수 요청 파라미터 '$name' (타입: $type)가 누락되었습니다."
        return failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = message)
    }

    /**
     * 본문을 읽다 난 오류. 근본 원인이 [CoreException]이면 그 [ErrorType]으로 답한다. 포인트·주문의 HTTP 입력 DTO가
     * JSON 토큰의 종류를 가리며 던진 거절이 Jackson과 Spring에 감싸여 여기까지 오기 때문이다
     * ([StrictLongDeserializer], [com.loopers.interfaces.api.v1.order.OrderCreateRequestDeserializer]).
     */
    @ExceptionHandler
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ApiResponse<*>> {
        val errorMessage = when (val rootCause = e.rootCause) {
            is CoreException -> {
                log.warn { "CoreException in request body : ${rootCause.message}" }
                return failureResponse(errorType = rootCause.errorType)
            }

            is InvalidFormatException -> {
                val fieldName = rootCause.path.joinToString(".") { it.fieldName ?: "?" }

                val valueIndicationMessage = when {
                    rootCause.targetType.isEnum -> {
                        val enumClass = rootCause.targetType
                        val enumValues = enumClass.enumConstants.joinToString(", ") { it.toString() }
                        "사용 가능한 값 : [$enumValues]"
                    }

                    else -> ""
                }

                val expectedType = rootCause.targetType.simpleName
                val value = rootCause.value

                "필드 '$fieldName'의 값 '$value'이(가) 예상 타입($expectedType)과 일치하지 않습니다. $valueIndicationMessage"
            }

            is MismatchedInputException -> {
                val fieldPath = rootCause.path.joinToString(".") { it.fieldName ?: "?" }
                "필수 필드 '$fieldPath'이(가) 누락되었습니다."
            }

            is JsonMappingException -> {
                val fieldPath = rootCause.path.joinToString(".") { it.fieldName ?: "?" }
                "필드 '$fieldPath'에서 JSON 매핑 오류가 발생했습니다: ${rootCause.originalMessage}"
            }

            else -> "요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요."
        }

        return failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = errorMessage)
    }

    @ExceptionHandler
    fun handleServerWebInput(e: ServerWebInputException): ResponseEntity<ApiResponse<*>> {
        fun extractMissingParameter(message: String): String {
            val regex = "'(.+?)'".toRegex()
            return regex.find(message)?.groupValues?.get(1) ?: ""
        }

        val missingParams = extractMissingParameter(e.reason ?: "")
        return if (missingParams.isNotEmpty()) {
            failureResponse(errorType = ErrorType.BAD_REQUEST, errorMessage = "필수 요청 값 \'$missingParams\'가 누락되었습니다.")
        } else {
            failureResponse(errorType = ErrorType.BAD_REQUEST)
        }
    }

    @ExceptionHandler
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<ApiResponse<*>> {
        return failureResponse(errorType = ErrorType.NOT_FOUND)
    }

    @ExceptionHandler
    fun handleUnexpected(e: Exception): ResponseEntity<ApiResponse<*>> {
        log.error(e) { "Exception : ${e.message}" }
        val errorType = ErrorType.INTERNAL_ERROR
        return failureResponse(errorType = errorType)
    }

    private fun failureResponse(errorType: ErrorType, errorMessage: String? = null): ResponseEntity<ApiResponse<*>> =
        ResponseEntity(
            ApiResponse.fail(errorCode = errorType.code, errorMessage = errorMessage ?: errorType.message),
            errorType.status,
        )
}
