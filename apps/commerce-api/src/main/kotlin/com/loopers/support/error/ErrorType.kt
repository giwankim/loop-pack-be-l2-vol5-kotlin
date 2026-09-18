package com.loopers.support.error

import org.springframework.http.HttpStatus

enum class ErrorType(val status: HttpStatus, val code: String, val message: String) {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase, "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.reasonPhrase, "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "이미 존재하는 리소스입니다."),

    /** 카탈로그. 범용 에러와 status·code를 공유하고 message만 다르다. */
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "브랜드를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.reasonPhrase, "상품을 찾을 수 없습니다."),
    BRAND_NAME_DUPLICATED(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "같은 이름의 브랜드가 이미 있습니다."),
    BRAND_HAS_PRODUCTS(HttpStatus.CONFLICT, HttpStatus.CONFLICT.reasonPhrase, "삭제되지 않은 상품이 남아 있는 브랜드는 삭제할 수 없습니다."),
    INVALID_SORT(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.reasonPhrase, "알 수 없는 정렬 값입니다."),

    /** 요청자. 새 status가 필요하므로 code도 새로 갖는다(설계 4 오류 코드). */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED.reasonPhrase, "요청자를 확인할 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, HttpStatus.FORBIDDEN.reasonPhrase, "다른 사용자의 것은 다룰 수 없습니다."),

    /**
     * 포인트·주문. 클라이언트가 구별해 다뤄야 하는 거절은 code도 새로 갖는다(설계 6 오류 코드).
     * 계정 없음은 fixture와 데이터의 불일치라 범용 500과 status·code를 공유한다(설계 6 끝).
     * 키 메시지의 128자는 domain의 `IdempotencyKey.MAX_LENGTH`와 같다. support는 domain을 참조할 수 없어 글자로 적는다.
     */
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."),
    ORDER_PRODUCT_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "ORDER_PRODUCT_NOT_AVAILABLE", "주문할 수 없는 상품입니다."),
    INVALID_IDEMPOTENCY_KEY(
        HttpStatus.BAD_REQUEST,
        "INVALID_IDEMPOTENCY_KEY",
        "Idempotency-Key 헤더는 1자 이상 128자 이하의 영문·숫자·하이픈·밑줄이어야 합니다.",
    ),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "같은 Idempotency-Key로 다른 요청을 보냈습니다."),
    INVALID_POINT_ORDER_REQUEST(
        HttpStatus.BAD_REQUEST,
        "INVALID_POINT_ORDER_REQUEST",
        "요청 본문이 잘못되었습니다. 금액과 수량은 정수 표기의 JSON 숫자여야 하고 빠질 수 없습니다.",
    ),
    POINT_ACCOUNT_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase, "사용자의 포인트 계정이 없습니다."),
}
