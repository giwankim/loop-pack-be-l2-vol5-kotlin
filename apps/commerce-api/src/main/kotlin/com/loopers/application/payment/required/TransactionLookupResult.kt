package com.loopers.application.payment.required

import com.loopers.domain.payment.PgTransaction

/** [PaymentGateway]의 두 조회의 결과. PG에게서 답을 얻지 못한 것도 예외가 아니라 이 값이다(ADR 0022 규칙 4). */
sealed interface TransactionLookupResult {
    /**
     * PG가 답한 PG 거래들. 없을 수도, 둘 이상일 수도 있다. 0건은 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3).
     * PG 거래가 아직 보이지 않거나 생성 요청이 아직 오는 중일 수 있다. 결제와 맞는지는 결제가 판단한다.
     */
    data class Found(val transactions: List<PgTransaction>) : TransactionLookupResult

    /**
     * PG에게서 읽을 수 있는 답을 얻지 못했다. 시간 초과, 연결 끊김, 다른 상태 코드, 읽을 수 없는 응답이다(ADR 0022 규칙 3).
     * [cause]는 로그와 테스트의 단언에만 쓴다(HTTP 상태나 실패의 종류). 호출자는 모든 조회 불가를 같게 다룬다.
     */
    data class Unavailable(val cause: String) : TransactionLookupResult
}
