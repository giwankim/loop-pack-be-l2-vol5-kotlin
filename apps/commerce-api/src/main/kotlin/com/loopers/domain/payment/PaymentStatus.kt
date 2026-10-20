package com.loopers.domain.payment

/**
 * 결제가 PG 거래에 대해 아는 정도(`CONTEXT.md` 결제 상태). 결과 미확인으로 시작해 앞으로만 간다. 결과 미확인에서 셋 모두로,
 * 처리 중에서 성공·실패로만 가고, 성공과 실패는 끝난 상태라 다시 바뀌지 않는다(ADR 0021). PG 거래의 상태는 [PgStatus]다.
 */
enum class PaymentStatus {
    UNKNOWN,
    PENDING,
    SUCCESS,
    FAILED,
    ;

    /** 성공과 실패. 다시 바뀌지 않는다. */
    val isFinished: Boolean
        get() = this == SUCCESS || this == FAILED
}
