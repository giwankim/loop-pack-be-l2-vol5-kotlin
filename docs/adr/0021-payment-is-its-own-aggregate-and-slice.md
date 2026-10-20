---
status: accepted
date: 2026-10-19
---

# 결제는 주문과 다른 애그리거트이고, PG 포트는 결제 슬라이스가 가진다

5주차 과제는 확정 전 주문의 포인트 결제를 로컬 테스트 PG의 카드 결제로 바꾼다. 과제 4절의 표는 PG의 답, 내부 결제·주문, 재고 처리를 나란히 두고, 내부 결제에 주문과 다른 상태(처리 중, UNKNOWN, SUCCESS, FAILED)를 준다. 커머스의 기록과 PG의 기록(PG 거래)은 과제가 시험하는 바로 그 장면에서 갈린다.

| 장면 | 커머스의 결제 | PG 거래 |
| --- | --- | --- |
| 생성 응답을 잃었다 | 결과 미확인 | 있고, 이미 성공했을 수도 있다 |
| 생성 전에 거절됐다 | 실패 | 없다 |
| 알림이 먼저 왔다 | 대사하기 전까지 끝나지 않았다 | 성공 |
| 발송을 기록한 뒤 프로세스가 멈췄다 | 결과 미확인 | 아무도 모른다 |

이 기록은 주문의 자식 엔티티로 둘 수도, 모델 없이 어댑터의 응답으로만 둘 수도, 따로 애그리거트로 둘 수도 있다.

1. **결제(`Payment`)는 자기 애그리거트이고 `domain.payment`와 `application.payment` 슬라이스를 가진다.** 주문 하나에 결제 하나다. 주문 식별자와 멱등 키는 각각 유일하다. 결제는 주문을 식별자로만 가리킨다. 실패한 결제 뒤에 새 결제를 만들지 않는다(과제).
   - 결제 상태는 결과 미확인으로 시작해 앞으로만 간다(`CONTEXT.md` 결제 상태). 끝난 결제는 바뀌지 않고, 처리 중은 결과 미확인으로 돌아가지 않는다.
   - 충돌은 상태가 아니다. 마지막 충돌의 이유와 시각을 결제에 남기고 WARN 로그를 한 줄 쓴다. 결제 상태와 거래 키는 바뀌지 않는다.
2. **PG 거래와 맞는지는 결제가 판단하고, 들어오는 길은 둘이다.**
   - `reconcile(transactions: List<PgTransaction>)`: 알림, 대사 API, 받아들여진 생성 응답이 모두 이 메서드를 부른다. 0건이면 그대로, 2건 이상이면 충돌이다. PG 주문 번호, 금액, 이미 아는 거래 키가 다르거나 끝난 결제에 다른 결과를 말하면 충돌이다. 맞으면 거래 키를 받고 PG 상태를 따라 앞으로 간다.
   - `reject(rejection: PgRejection)`: 생성 전 거절만 받는다. PG 주문 번호와 금액이 맞고 결과 미확인이면 실패가 된다. 과제 3절이 어댑터에 맡긴 "요청 orderId·amount 일치" 확인도 여기서 한다.
   - 둘 다 `ReconcileResult`(`Changed(from, to)`, `Unchanged`, `Conflicted(reason)`)를 돌려준다. 주문과 예약은 `Changed(to = SUCCESS 또는 FAILED)`에서만 바뀐다(2단계).
   - PG에게서 답을 얻지 못한 것은 도메인에 들어오지 않는다. 응용 계층이 거기서 멈추고 저장된 상태를 돌려준다.
   - `PgTransaction`은 PG가 그 순간 말한 PG 거래의 사본이며 값 객체다. 커머스의 식별자도, 표도, 생애도 없다.
3. **PG 포트는 결제 슬라이스의 required 포트다.** `application.payment.required.PaymentGateway`가 `requestPayment`, `retrieveTransaction`, `listTransactions`를 가지고, 결과를 값으로 돌려준다([ADR 0022](./0022-only-the-pre-create-rejection-proves-no-pg-transaction.md)). 구현은 새 어댑터 층 `adapter.integration.pg`의 `LocalPgGateway`다. (2026-10-20: `requestPayment`를 `createTransaction`으로 바꿨다. 이 호출은 PG 거래를 만든다. Stripe와 Toss의 `confirm`은 PG가 이미 만든 객체를 그 키로 승인하는 일이고, 이 맥락에서 `confirm`은 주문을 확정하다의 이름이다. 같은 날 [ADR 0023](./0023-pg-port-returns-transactions-and-throws-the-rest.md)이 결과를 값 대신 예외로 바꿨다. 포트는 PG 거래만 돌려주고 나머지는 결과마다 다른 예외로 던진다. [결제 설계 3.2](../design/payments.md).)
   - 어댑터는 HTTP를 위의 값으로 옮길 뿐 커머스의 기록과 견주지 않는다(부패 방지 계층). 주문 슬라이스는 거래 키, PG 상태, 카드를 보지 않는다.
   - `HexagonalArchitectureTest`는 `adapter.integration`을 다른 두 어댑터와 같은 규칙의 층으로 더한다. 어느 층도 그것을 쓰지 않고, 그것도 다른 어댑터를 쓰지 않는다.
4. **결제와 주문은 응용 서비스의 트랜잭션이 함께 바꾼다.** 확정이 주문, 상품, 포인트 계정을 한 트랜잭션에서 잠그고 바꾸듯([ADR 0003](./0003-confirm-order-in-one-transaction.md), [ADR 0019](./0019-order-and-point-account-rows-locked-on-first-read.md)), PG의 결과를 반영하는 트랜잭션(과제의 TX B)이 결제, 주문, 예약을 함께 바꾼다.
   - 주문이 결제를 부르고 결제가 주문을 바꾸면 `applicationSlicesAreFreeOfCycles`가 깨진다. 브랜드 삭제의 `ProductDeleter`처럼 부르는 쪽이 자기 `required` 패키지에 인터페이스를 두고 다른 쪽 서비스가 구현해, 의존을 한 방향으로 둔다.
   - 어느 쪽이 조율하고 어느 쪽이 인터페이스를 두는지는 2단계가 정한다.

## 고르지 않은 것

- **주문의 자식 엔티티.** 둘은 1:1이고 TX B가 둘을 함께 바꾸므로, "결제가 성공한 주문만 확정된다"를 애그리거트 하나가 지킬 수 있다. 그러나 결제의 규칙(끝나면 바뀌지 않는다, 맞는 답만 받는다, 생성은 한 번)은 품목, 수량, 소유를 말하지 않는다. 알림과 대사는 거래 키나 PG 주문 번호로 결제를 먼저 찾는다. 거래 키, PG 상태, 카드가 주문 슬라이스와 주문의 용어로 새어, 부패 방지 계층이 엉뚱한 곳에서 멈춘다. 주문은 고객이 움직일 때 바뀌고, 결제는 PG가 답할 때마다 바뀌며 알림과 대사가 겹치면 동시에 바뀐다. 애그리거트 사이의 규칙을 응용 서비스의 트랜잭션이 지키는 것은 이 저장소가 이미 하는 일이다.
- **모델 없이 어댑터만 둔다.** 1단계의 포트가 PG의 말로 답하고, 2단계의 모델이 그것을 다시 옮겨야 한다. 과제 4절이 내부 결제에 따로 상태를 준다.
- **PG의 답을 하나의 타입으로 받는 `apply` 하나.** 받아들여진 생성, 조회 결과, 생성 전 거절, 답 없음을 한 sealed 타입으로 받으면 규칙이 한 메서드에 모인다. 그러나 도메인이 "답 없음"까지 받게 되고, 그 타입의 이름이 과제의 낱말(PG 관찰)을 옮긴 것에 머문다. 답 없음을 응용 계층에서 멈추자 정확한 두 메서드로 충분했다.

## 대가

- "확정된 주문에는 성공한 결제가 있다"를 애그리거트가 아니라 TX B의 응용 서비스가 지킨다. 서비스의 실수는 엔티티가 막지 않고 장면 테스트가 막는다.
- 표 하나, 슬라이스 하나, 고리를 끊는 인터페이스 하나가 는다.
- 포인트로 결제한 주문에는 결제가 없다. 그 주문의 결제 기록은 확정된 주문의 결제액과 확정 시각이다.
- 포트 하나가 생성과 조회를 함께 가지므로, 대사가 생성을 부르지 않는다는 것을 타입이 막지 않는다. [ADR 0022](./0022-only-the-pre-create-rejection-proves-no-pg-transaction.md)가 테스트로 지킨다.

## 다시 볼 조건

- 실패한 뒤 다시 결제하는 것을 허용하면, 결제 아래에 시도(`PaymentAttempt`)를 자식으로 둔다. Stripe의 PaymentIntent와 Charge, PaymentRecord와 PaymentAttemptRecord가 같은 구조다.
- 부분 취소나 환불이 들어오면 PG 거래 하나가 한 결제를 대신하지 못한다. Toss처럼 결제 아래에 승인과 취소의 거래를 둔다.
- 두 번째 PG가 생기면 `PaymentGateway`의 구현을 고르는 규칙을 정한다.

## 바꾸는 결정

[ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md)의 어댑터 층에 `adapter.integration`이 더해진다. 그 ADR에는 1단계 작업에서 원문을 두고 날짜 메모를 단다. `CONTEXT.md`의 `### 결제`가 이 ADR의 용어를 따른다. 포인트로 결제하는 확정([ADR 0003](./0003-confirm-order-in-one-transaction.md))과 주문의 상태는 2단계가 바꾼다.
