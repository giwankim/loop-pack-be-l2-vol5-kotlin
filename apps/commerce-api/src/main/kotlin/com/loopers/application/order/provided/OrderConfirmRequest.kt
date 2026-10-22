package com.loopers.application.order.provided

import jakarta.validation.constraints.NotBlank
import org.hibernate.validator.constraints.CreditCardNumber

/**
 * 주문 확정 요청. 금액은 저장된 주문에서 정하므로 카드만 받는다(ADR 0025 규칙 1). 카드는 PG에 넘기고 저장하지 않는다.
 *
 * PG 포트의 생성 요청(`PgTransactionCreateRequest`)과 같은 제약을 단다. 잘못된 카드는 재고를 예약하거나 결제를 만들기 전에 거절된다.
 * 포트의 검사가 TX A 뒤에 처음 실패하면, 보내지 않을 결제가 결과 미확인으로 남아 예약을 영영 쥔다(ADR 0024 규칙 1).
 */
data class OrderConfirmRequest(
    @NotBlank(message = "카드 종류는 공백일 수 없습니다.")
    val cardType: String,
    @NotBlank(message = "카드 번호는 공백일 수 없습니다.")
    @CreditCardNumber(ignoreNonDigitCharacters = true, message = "카드 번호가 올바르지 않습니다.")
    val cardNo: String,
)
