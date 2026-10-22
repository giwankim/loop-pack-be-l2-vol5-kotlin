package com.loopers.application.payment.required

import com.loopers.application.payment.provided.MultiplePgTransactionsException
import com.loopers.application.payment.provided.PgOutcomeUnknownException
import com.loopers.application.payment.provided.PgRejectedException
import com.loopers.application.payment.provided.PgUnavailableException
import com.loopers.domain.payment.PgTransaction
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid

/**
 * PG에 PG 거래를 요청하고 조회하는 포트(ADR 0021). 구현은 `adapter.integration.pg`의 로컬 테스트 PG 어댑터다.
 *
 * PG가 말한 PG 거래만 돌려주고, 나머지 결과는 결과마다 다른 예외로 던진다(ADR 0023). 정확한 생성 전 거절만 [PgRejectedException]이다.
 * 시간 초과, 연결 끊김, 연결 풀의 대기 초과, 정확하지 않은 422를 비롯한 다른 상태 코드, 읽을 수 없는 응답은 모두
 * 생성이면 [PgOutcomeUnknownException], 조회면 [PgUnavailableException]이다. PG 주문 번호 하나에 PG 거래가 둘 이상이면
 * [MultiplePgTransactionsException]이다. 네 예외에는 공통 상위 타입이 없다. 결제 슬라이스의 provided 포트(2단계)가 이 예외들을 주문
 * 슬라이스로 그대로 던지게 되므로, 그 포트보다 먼저 `application.payment.provided`에 두었다(ADR 0028 규칙 3).
 * 요청을 직렬화하지 못한 것 같은 프로그래밍 오류는 그 밖의 예외로 그대로 던진다. 다만 구현의 컨버터 설정이 잘못된 것은
 * 결과 미확인이나 조회 불가로 보인다(`LocalPgGateway`).
 *
 * 호출자는 이 호출을 트랜잭션 안에 두지 않는다. 결과 미확인의 예외가 결제를 저장한 트랜잭션을 지나면 그 저장이 되돌아간다(ADR 0023).
 */
interface PaymentGateway {
    /**
     * [request]에 담긴 PG 주문 번호, 금액, 카드로 PG 거래를 만들어 달라고 한 번 요청한다. 다시 보내지 않는다(ADR 0022 규칙 1).
     * 받아들여진 생성의 [PgTransaction]을 돌려주지만 성공이 아니다. PG 상태는 그 PG 거래가 말한다.
     *
     * @throws ConstraintViolationException 입력이 제약에 맞지 않는다. PG에 요청하지 않는다.
     * @throws PgRejectedException 정확한 생성 전 거절. PG 거래가 없다.
     * @throws PgOutcomeUnknownException 그 밖의 모든 응답과 답 없음. 실패가 아니다.
     */
    fun createTransaction(@Valid request: PgTransactionCreateRequest): PgTransaction

    /**
     * 거래 키 [transactionKey]의 PG 거래를 한 번 조회한다. PG가 그 PG 거래를 모르면(404) `null`이다. `null`은 PG 거래가 없다는 증거가 아니다.
     * PG 거래가 아직 보이지 않거나 생성 요청이 아직 오는 중일 수 있다(ADR 0022 규칙 3).
     *
     * @throws PgUnavailableException 그 밖의 모든 응답과 답 없음.
     */
    fun retrieveTransaction(transactionKey: String): PgTransaction?

    /**
     * PG 주문 번호 [pgOrderId]의 PG 거래를 한 번 조회한다. PG가 그 PG 주문 번호의 PG 거래를 하나도 말하지 않으면(빈 배열) `null`이다.
     * `null`은 PG 거래가 없다는 증거가 아니다. PG 거래가 아직 보이지 않거나 생성 요청이 아직 오는 중일 수 있다(ADR 0022 규칙 3).
     * 결제와 맞는지는 결제가 판단한다.
     *
     * @throws MultiplePgTransactionsException PG가 그 PG 주문 번호의 PG 거래를 둘 이상 말했다. 하나를 고르지 않는다.
     * @throws PgUnavailableException 그 밖의 모든 응답과 답 없음.
     */
    fun retrieveTransactionByPgOrderId(pgOrderId: String): PgTransaction?
}
