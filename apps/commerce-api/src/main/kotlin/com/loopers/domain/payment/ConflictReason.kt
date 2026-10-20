package com.loopers.domain.payment

/** 결제와 맞지 않는 PG 거래나 생성 전 거절의 까닭(`CONTEXT.md` 충돌). 결제는 마지막 충돌의 까닭만 남긴다. */
enum class ConflictReason {
    /** PG 주문 번호 하나에 PG 거래가 둘 이상이다. */
    MULTIPLE_TRANSACTIONS,

    /** 답의 PG 주문 번호가 결제의 것과 다르다. */
    PG_ORDER_ID_MISMATCH,

    /** 답의 금액이 결제의 것과 다르다. */
    AMOUNT_MISMATCH,

    /** PG 거래의 거래 키가 결제가 이미 아는 것과 다르다. */
    TRANSACTION_KEY_MISMATCH,

    /** 답이 결제가 아는 결과와 반대다. 끝난 결제에 반대의 결과이거나, PG 거래가 있는 결제에 생성 전 거절이다. */
    RESULT_MISMATCH,

    /** 생성 전 거절로 실패한 결제에 맞는 PG 거래가 왔다. 거절은 PG 거래가 없다는 증거였으므로(ADR 0022) PG 상태가 무엇이든 모순이다. */
    TRANSACTION_AFTER_REJECTION,
}
