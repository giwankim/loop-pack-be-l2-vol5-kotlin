package com.loopers.domain.payment

import com.loopers.domain.shared.Money

/**
 * PG가 그 순간 말한 PG 거래의 사본(ADR 0021). 값 객체이며 커머스의 식별자도, 표도, 생애도 없다.
 * 받아들여진 생성 응답과 조회가 이 값을 만든다. 결제와 맞는지는 결제가 판단한다.
 */
data class PgTransaction(
    val transactionKey: String,
    val pgOrderId: String,
    val amount: Money,
    val status: PgStatus,
)
