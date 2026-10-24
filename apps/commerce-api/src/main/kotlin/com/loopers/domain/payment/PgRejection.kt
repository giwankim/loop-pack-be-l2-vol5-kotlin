package com.loopers.domain.payment

import com.loopers.domain.shared.Money

/**
 * 생성 전 거절(`CONTEXT.md`). PG가 PG 거래를 만들기 전에 거절했고 그 요청으로 앞으로도 만들지 않는다고 명시한 응답에서,
 * PG가 되돌려 준 PG 주문 번호와 금액이다. PG 거래가 없다는 유일한 증거다(ADR 0022). 보낸 값과 같은지는 결제가 본다.
 */
data class PgRejection(
    val pgOrderId: String,
    val amount: Money,
)
