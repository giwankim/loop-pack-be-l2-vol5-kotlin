package com.loopers.application.payment.provided

import com.loopers.domain.payment.PgRejection

/**
 * PG가 PG 거래를 만들기 전에 정확히 거절했다. PG 거래가 없다는 유일한 증거다(ADR 0022 규칙 2, ADR 0023).
 * [rejection]이 PG가 되돌려 준 PG 주문 번호와 금액이고, 보낸 것과 같은지는 `Payment.reject`가 본다.
 */
class PgRejectedException(
    val rejection: PgRejection,
) : RuntimeException("PG가 생성 전에 거절했다.")
