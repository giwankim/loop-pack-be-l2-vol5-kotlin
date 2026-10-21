package com.loopers.application.payment.required

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.CreditCardNumber

/** PG 거래 생성 입력. 포트의 `@Valid`와 어댑터의 `@Validated`가 PG에 보내기 전에 검증한다. */
data class PgTransactionCreateRequest(
    @NotBlank(message = "PG 주문 번호는 공백일 수 없습니다.")
    val pgOrderId: String,
    @Positive(message = "금액은 1원 이상이어야 합니다.")
    val amount: Long,
    @NotBlank(message = "카드 종류는 공백일 수 없습니다.")
    val cardType: String,
    @NotBlank(message = "카드 번호는 공백일 수 없습니다.")
    @CreditCardNumber(ignoreNonDigitCharacters = true, message = "카드 번호가 올바르지 않습니다.")
    val cardNumber: String,
)
