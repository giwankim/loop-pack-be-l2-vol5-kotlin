package com.loopers.application.payment.provided

/**
 * PG가 PG 주문 번호 하나에 PG 거래를 둘 이상 말했다(ADR 0028). 어느 것이 결제의 PG 거래인지 모르므로 고르지 않는다. 결제와 맞는지가
 * 아니라 PG의 답만으로 정해지는 충돌이라 결제에 들어가지 않는다. 호출자는 충돌을 WARN으로 남기고 저장된 상태를 돌려준다.
 *
 * 다른 PG 예외처럼 `RuntimeException`을 바로 넓히고 공통 상위 타입이 없다(ADR 0023 규칙 2).
 */
class MultiplePgTransactionsException(
    count: Int,
) : RuntimeException("PG 주문 번호 하나에 PG 거래가 ${count}개다.")
