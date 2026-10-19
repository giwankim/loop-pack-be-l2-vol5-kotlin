package com.loopers.application.payment.required

import com.loopers.domain.payment.PgRejection
import com.loopers.domain.payment.PgTransaction

/** [PaymentGateway.requestPayment]의 결과. PG에게서 답을 얻지 못한 것도 예외가 아니라 이 값이다(ADR 0022 규칙 4). */
sealed interface PaymentRequestResult {
    /** PG가 생성을 받아들였다. 거래의 PG 상태가 무엇이든 그렇다. */
    data class Accepted(val transaction: PgTransaction) : PaymentRequestResult

    /** 정확한 생성 전 거절. PG 거래가 없다는 유일한 증거다(ADR 0022 규칙 2). */
    data class Rejected(val rejection: PgRejection) : PaymentRequestResult

    /**
     * PG에서 어떻게 되었는지 모른다. PG 거래가 있을 수도, 이미 성공했을 수도 있다(ADR 0022 규칙 3).
     * [cause]는 로그와 테스트의 단언에만 쓴다(HTTP 상태나 실패의 종류). 호출자는 모든 결과 미확인을 같게 다룬다.
     */
    data class Unknown(val cause: String) : PaymentRequestResult
}
