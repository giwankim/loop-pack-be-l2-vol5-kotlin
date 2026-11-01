---
status: accepted
date: 2026-10-22
---

# 주문 슬라이스가 결제를 조율하고, 조율은 트랜잭션 밖에서 PG의 예외를 타입마다 잡는다

[ADR 0021](./0021-payment-is-its-own-aggregate-and-slice.md) 규칙 4는 결제와 주문을 응용 서비스의 트랜잭션이 함께 바꾸되, 어느 슬라이스가 조율할지는 2단계에 남겼다. 주문이 결제를 부를 수도, 결제가 주문을 부를 수도 있고, 어느 쪽이든 고리를 끊는 인터페이스가 하나 든다. [ADR 0024](./0024-confirm-calls-the-pg-between-two-transactions.md)의 TX B는 결제, 주문, 예약을 함께 바꾸고, PG 호출은 어느 트랜잭션에도 들지 않는다.

1. **주문 슬라이스의 확정과 대사가 조율한다.** 결제 슬라이스는 다음 provided 포트를 내주고, 주문 슬라이스를 모른다.
   - 결제를 만든다. TX A에 참여한다.
   - 결제를 찾는다. 주문 하나의 결제(`findByOrder`, 없으면 데이터 불일치로 500 `PAYMENT_MISSING`)와 목록의 결제(`findAllByOrders`)다. 주문의 결제수단이 PG일 때만 부른다. (2026-10-22: 목록의 결제도 받은 주문 가운데 결제가 없는 것이 있으면 `PAYMENT_MISSING`이다. 그 주문만 빼고 돌려주면 목록이 결제수단이 PG인데 결제 상태가 없는 주문을 실어 불일치를 감춘다. 목록은 한 조각에 있는, 결제수단이 PG인 주문의 결제를 한 문장으로 읽고, 그런 주문이 없는 조각은 결제를 읽지 않는다. 관리자 상세도 고객 상세처럼 `findByOrder`를 부른다.)
   - 트랜잭션 밖에서 PG 거래를 만들고, PG 주문 번호로 조회한다. (2026-10-23: 둘은 provided 포트 `PgTransactionClient` 하나의 `create`와 `retrieve`다. 처음에는 `PgTransactionCreator`와 `PgTransactionRetriever`로 나눴으나, 구현, 부르는 조율 서비스, 트랜잭션 밖이라는 조건이 같아 명세 147의 검토에서 합쳤다.)
   - PG 거래나 생성 전 거절을 결제에 반영하고 `ReconcileResult`를 돌려준다. TX B에 참여한다.

   사가의 조율자는 그 절차의 주인 곁에 둔다. Eventuate Tram의 예제에서 `CreateOrderSaga`는 주문 서비스에 있고, 참여자는 주문을 식별자로만 안다. 결제는 일반 하위 도메인이라 나중에 포인트 충전을 카드로 받더라도 의존은 결제를 향한다. 지금의 확정도 이미 `StockDeductor`와 `PointDeductor`를 조율한다.
2. **조율하는 서비스는 트랜잭션 밖에서만 돈다.** `@ApplicationService`에 전파를 고르는 별칭을 더해 `NEVER`로 연다. 트랜잭션 안에서 부르면 Spring이 `IllegalTransactionStateException`으로 거절하므로, PG 호출이 트랜잭션에 드는 실수([ADR 0023](./0023-pg-port-returns-transactions-and-throws-the-rest.md) 규칙 4)를 KDoc 대신 실행이 막는다. 결제 슬라이스의 PG 호출도 `NEVER`다. TX A와 TX B는 다른 빈의 트랜잭션 메서드다. 프록시를 지나므로 선언한 트랜잭션이 걸리고, 장면 테스트가 TX B만 실패시킬 수 있다.
3. **PG의 결과는 예외로 두고, 조율하는 서비스가 트랜잭션 밖에서 타입마다 잡는다.**

   | 호출 | 결과 | 조율하는 서비스 |
   | --- | --- | --- |
   | 생성 | `PgTransaction` | TX B에서 반영한다. |
   | 생성 | `PgRejectedException` | TX B에서 거절을 반영한다. 결제는 실패, 주문은 결제에 실패한 주문이 되고, 예약을 돌려준다. |
   | 생성 | `PgOutcomeUnknownException` | 저장된 상태(결제 중인 주문)로 답한다. |
   | 조회 | `PgTransaction` | TX B에서 반영한다. |
   | 조회 | `null` | 저장된 상태로 답한다. |
   | 조회 | `PgUnavailableException` | 저장된 상태로 답한다. |
   | 조회 | `MultiplePgTransactionsException` | 충돌을 WARN으로 남기고 저장된 상태로 답한다. |

   - PG의 답을 하나로 감싸는 결과 타입을 두지 않는다. TX B의 입구가 PG 거래의 반영과 거절의 반영 둘이라, 부른 자리에서 곧바로 갈린다.
   - PG의 예외는 이제 결제 슬라이스의 provided 포트가 던지는 것이므로 `application.payment.provided`로 옮긴다.
   - TX B 안의 충돌은 예외가 아니라 `ReconcileResult.Conflicted`다. 결제의 반영은 TX B에 참여하므로, 거기서 던진 예외는 주문 쪽이 잡아도 트랜잭션을 rollback-only로 만든다. TX B가 WARN을 남기고 저장된 상태로 답한다.
   - (2026-10-23, [issue 156](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156): 표의 "저장된 상태"는 PG를 부르기 전에 읽은 것이 아니라 답하는 때에 다시 읽은 주문과 결제 상태다. 생성의 `PgOutcomeUnknownException`, 조회의 `null`, `PgUnavailableException`, `MultiplePgTransactionsException`이 모두 그렇다. 대사가 생기자 PG 호출 동안 다른 대사가 결제를 끝낼 수 있게 됐고, TX A의 사본으로 답하면 확정된 주문을 202로 알린다. 그래서 생성 행의 "(결제 중인 주문)"은 대개 그렇다는 뜻이다. 다시 읽는 값이 바뀌는 일은 드물고, 읽기 두 문장이 PG의 답이 주문을 바꾸지 못한 길에만 든다. 3단계의 알림도 이 대사로 오므로 같은 창이 생긴다.)
4. **결제를 바꾸는 트랜잭션은 모두 주문을 먼저 잠근다. 그래서 결제 행은 잠그지 않는다.** 주문 잠금 뒤의 일반 읽기는 늘 가장 최근에 커밋된 결제를 본다. 포인트 계정은 확정에서 빠진다([ADR 0027](./0027-point-payment-path-is-deleted.md)).

   | 경로 | 잠그는 차례 |
   | --- | --- |
   | 확정의 TX A | 주문 → 상품(id 오름차순) → revision(품절이 바뀌면) |
   | 확정과 대사의 TX B | 주문 → 상품(실패일 때만, id 오름차순) → revision(품절이 바뀌면) |

   결제는 TX A에서 새 행으로 들어가고, 그 뒤로는 잠그지 않고 읽는다.
5. **주문 슬라이스는 결제의 값을 옮기되 읽지 않는다.** 카드, PG 거래, 생성 전 거절을 결제 슬라이스의 부름 사이에서 넘기고, 읽는 것은 결제 상태뿐이다. 거래 키, PG 상태, 카드 번호를 보지 않는다. 주문 조회는 주문, 결제수단, 결제 상태를 담은, 애그리거트를 건너는 Info를 돌려준다([ADR 0014](./0014-finders-load-whole-aggregates.md)).
6. **3단계의 알림도 이 길을 쓴다.** 결제의 웹 어댑터가 PG 주문 번호로 결제를 찾아 그 주문의 대사 유스케이스를 부른다. 어댑터가 응용 계층을 부르므로 고리가 없다. (2026-10-23, [issue 161](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/161): 웹 어댑터는 `PaymentApi`의 `POST /api/v1/payments/callback`이다. 결제를 `PaymentFinder.findByPgOrderId`로 찾고, 부르는 대사 유스케이스는 고객의 `OrderConfirmer.reconcile`이 아니라 PG가 부르는 provided 포트 `OrderPaymentReconciler.reconcile(orderId)`다([ADR 0031](./0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md) 규칙 3). 그 포트도 `OrderPaymentSaga`가 구현하며, 주문을 사용자 없이 읽고 고객의 대사와 같은 조회와 같은 TX B를 쓴다. 다른 것은 답하는 때에 다시 읽지 않는 것과, 조회할 수 없으면 `PG_UNAVAILABLE`(503)을 던지는 것이다. 규칙 3의 표에서 조회 불가 행의 "저장된 상태로 답한다"는 고객의 대사에만 맞다.)

## 고르지 않은 것

- **결제가 조율한다.** 결제가 주문과 재고를 바꾸게 되고, "주문을 먼저 잠근다"는 약속을 포트 너머의 주문 슬라이스에 맡겨야 한다. 일반 하위 도메인이 자기를 쓰는 절차에 기댄다.
- **결제가 required 포트를 두고 주문이 구현한다(브랜드 삭제의 `ProductDeleter` 모양).** 코드의 의존은 주문에서 결제로 가지만, 결제가 주문의 생애를 움직인다. `ProductDeleter`는 호출 하나이고 이것은 절차다.
- **도메인 이벤트.** 3주차에 이벤트를 들이지 않았고, TX B는 동기이며 원자여야 한다. (2026-10-27, [ADR 0032](./0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): 6주차는 TX B가 주문을 처음 확정한 커밋 뒤에 `OrderConfirmed`를 Kafka에 발행한다. 이벤트는 TX B의 단계가 아니라 커밋 뒤에 발행되고, 단계의 빈이 required 포트 `OrderEventPublisher`를 TX B 안에서 부르며 어댑터가 커밋 뒤 비동기로 중계한다. 이 항목의 결정은 그대로다.)
- **PG의 답을 담는 sealed 타입(`PgAnswer`).** 규칙 3의 까닭이다. 부른 자리에서 바로 갈리는 결과는 예외가 단순하다.
- **`TransactionTemplate`으로 한 서비스 안에서 TX A와 TX B를 연다.** main에 프로그램 방식의 트랜잭션이 없고, 다른 빈의 선언적 트랜잭션이 테스트에서 TX B만 실패시키기 쉽다.

## 대가

- 주문 슬라이스가 PG 예외 넷과 결제 슬라이스의 값(카드, PG 거래, 생성 전 거절)을 다룬다. ADR 0021 규칙 3의 "주문 슬라이스는 거래 키, PG 상태, 카드를 보지 않는다"가 "옮기되 읽지 않는다"로 느슨해진다.
- 조율하는 서비스, 트랜잭션 단계의 빈, 결제 슬라이스의 포트 여럿이 는다.
- `NEVER`는 이 저장소에서 처음 쓰는 전파다. 테스트를 트랜잭션으로 감싸는 `BaseApplicationServiceTest`와 `BaseWebApiAdapterTest`에서는 확정과 대사를 부를 수 없다. 응용 계층의 테스트는 커밋하는 기반을 쓰고, 웹 어댑터의 테스트는 `OrderConfirmationApiTest`처럼 클래스에 `@Transactional(propagation = NOT_SUPPORTED)`를 단다. 감싼 트랜잭션 안에서는 어차피 TX A가 커밋되지 않아 발송 기록의 뜻이 사라진다.

## 다시 볼 조건

- 결제가 주문 말고 다른 것도 치르게 되면 그 호출자도 결제 슬라이스의 provided 포트를 부른다. 두 호출자가 다른 모양을 원하면 포트를 다시 본다.
- 주문과 결제가 다른 서비스로 나뉘면 이 조율이 사가가 된다. 조율자를 주문 쪽에 두는 것은 그대로다. (2026-10-26: 지금도 한 프로세스 안의 사가다. 사가는 로컬 트랜잭션의 차례이고 메시징은 흔한 전달 수단일 뿐이다. Richardson의 분류로 TX A는 보상 가능한 트랜잭션(실패하면 예약을 돌려준다), PG 생성은 피벗, TX B는 다시 할 수 있는 트랜잭션이다. 그래서 조율하는 빈을 `OrderPaymentSaga`, 로컬 트랜잭션의 빈을 `OrderPaymentSagaSteps`라 부른다([ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md)의 메모). 서비스가 나뉘면 같은 사가가 분산 사가가 된다.)

## 바꾸는 결정

ADR 0021 규칙 4의 남은 선택을 정하고, 규칙 3의 마지막 줄을 바꾼다. ADR 0021 규칙 2와 ADR 0023 규칙 1은 같은 인터뷰의 다른 결정으로 바뀐다. `Payment.reconcile`은 PG 거래 하나를 받고, PG 주문 번호의 조회는 PG 거래를 하나까지 돌려주며 둘 이상이면 어댑터가 `MultiplePgTransactionsException`을 던진다. 그래서 `ConflictReason.MULTIPLE_TRANSACTIONS`가 도메인을 떠난다. [ADR 0019](./0019-order-and-point-account-rows-locked-on-first-read.md)의 잠금 차례에 규칙 4의 표가 더해진다. 이 날짜 메모들은 구현하는 작업에서 원문을 두고 단다.
