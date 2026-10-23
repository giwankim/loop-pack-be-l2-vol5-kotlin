package com.loopers.adapter.webapi.v1.payment

import jakarta.validation.constraints.NotBlank

/**
 * PG의 알림 본문(`CONTEXT.md` 알림). 필드 이름은 PG의 것이라 PG 주문 번호를 `orderId`라 부른다(ADR 0031 규칙 1).
 *
 * 다른 요청과 달리 application이 아니라 웹 어댑터에 둔다. 알림이 부르는 포트는 이 본문이 아니라 PG 주문 번호 하나만 받는다. 본문과 유스케이스의
 * 입력이 갈리면 웹에 요청 DTO를 두는 것이 카탈로그 설계 5.17이 적어 둔 대가다.
 *
 * [transactionKey]와 [status]는 읽지 않는다. 있으면 받지만 값을 검사하지 않고 어느 판단에도 쓰지 않는다. 결과는 PG 주문 번호의 조회로만
 * 안다(ADR 0031 규칙 5). PG 상태의 enum으로 읽지 않으므로, PG가 그 필드를 빼거나 바꿔도 알림을 잃지 않는다.
 */
data class PaymentCallbackRequest(
    @NotBlank(message = "PG 주문 번호는 공백일 수 없습니다.")
    val orderId: String,
    /** 읽지 않는다. */
    val transactionKey: String? = null,
    /** 읽지 않는다. */
    val status: String? = null,
)
