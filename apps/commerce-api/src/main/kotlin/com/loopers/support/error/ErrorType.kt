package com.loopers.support.error

import org.springframework.http.HttpStatus

enum class ErrorType(val status: HttpStatus, val code: String, val message: String) {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase, "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.reasonPhrase, "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "이미 존재하는 리소스입니다."),

    /** 카탈로그. 범용 에러와 status·code를 공유하고 message만 다르다. */
    INVALID_NAME(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.reasonPhrase, "이름은 공백일 수 없고 100자 이하여야 합니다."),
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "브랜드를 찾을 수 없습니다."),
    BRAND_NAME_DUPLICATED(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "같은 이름의 브랜드가 이미 있습니다."),
}
