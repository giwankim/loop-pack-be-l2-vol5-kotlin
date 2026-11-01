---
status: accepted
date: 2026-10-23
---

# 알림은 본문을 믿지 않고, 그 PG 주문 번호의 조회로 대사한다

과제 3절은 `POST /api/v1/payments/callback`이 PG의 결과 알림을 받아 "해당 거래를 권위 조회한 뒤 같은 결과 적용 경계"를 쓰라고 한다. 5절은 알림의 본문이 `providerOrderId`, `transactionKey`, `status`를 전하는 알림일 뿐이고, 성공 문자열만으로 주문을 확정하지 않으며, 고정한 PG 어댑터의 조회 결과에서 제공자 계정, 주문, 거래 키, 저장 금액을 확인한 뒤 적용하라고 한다. callback-first와 lookup-first는 같은 끝 상태로 모이고, 중복 알림은 효과가 없으며, 끝난 결제는 늦은 답이 되돌리지 않는다. 6절은 알림의 경로도 실제 컨트롤러를 거쳐, 꾸민 본문만으로 성공하지 않는지 확인하라고 한다.

[ADR 0028](./0028-order-slice-orchestrates-the-payment.md) 규칙 6이 길을 정했다. 결제의 웹 어댑터가 PG 주문 번호로 결제를 찾아 그 주문의 대사 유스케이스를 부른다. 남은 것은 무엇으로 조회할지, 요청자가 없는 알림이 무엇으로 대사할지, PG에 무엇으로 답할지다.

Toss의 카드 결제 알림(`PAYMENT_STATUS_CHANGED`)은 `data`에 결제 객체 전체를 싣지만 서명이 없다. 서명은 정산(`payout.changed`)과 셀러(`seller.changed`) 알림에만 있고, 가상계좌 입금 알림은 승인 응답의 `secret`과 견주는 값을 싣는다. 그러니 카드 결제의 알림을 믿는 길은 결제를 다시 조회하는 것뿐이다(추론). Toss는 10초 안의 200만 받은 것으로 보고, 그렇지 않으면 1, 4, 16, 64, 256, 1024, 4096분 간격으로 최대 7번 다시 보낸다([웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events), [웹훅 연결하기](https://docs.tosspayments.com/guides/v2/webhook)).

1. **본문은 `{transactionKey, orderId, status}`이고 `orderId`만 쓴다.** PG의 다른 메시지처럼 PG 주문 번호를 `orderId`라 부른다. 과제의 `providerOrderId`는 개념의 이름이고, PG와 주고받는 이름은 PG의 것이다. `orderId`가 없거나 비었으면 400이다. `transactionKey`와 `status`는 있으면 받지만 값을 검사하지 않고 어느 판단에도 쓰지 않는다. 읽지 않는 필드 때문에 알림을 잃지 않게 한다. 요청 DTO는 두 필드를 두고, KDoc에 읽지 않는다고 적는다.
2. **결제를 PG 주문 번호로 찾는다.** 결제 슬라이스에 `PaymentFinder.findByPgOrderId(pgOrderId)`를 더한다. 인덱스는 `UK_PAYMENT_ON_PG_ORDER_ID`다([ADR 0029](./0029-pg-order-id-is-minted-per-payment.md) 규칙 3). 이 저장소의 Finder처럼 없으면 던지고, 웹은 404 `PAYMENT_NOT_FOUND`로 답한다. PG 주문 번호는 UUID라 404가 알려 주는 것이 없다.
3. **PG는 자기 대사 포트를 부른다.** 주문 슬라이스에 `OrderPaymentReconciler.reconcile(orderId): Unit`을 두고 `OrderPaymentSaga`가 구현한다. 고객의 `OrderConfirmer.reconcile`과 본문이 같고 소유를 보지 않는다. 같은 PG 주문 번호 조회(`PgTransactionClient.retrieve`)와 같은 TX B다. 알림의 주문에서 사용자를 읽어 고객의 대사를 부르지 않는다. 요청자는 웹 경계의 헤더에서만 오고([ADR 0015](./0015-web-boundary-accepts-the-requester.md)), 구현하는 Service와 부르는 쪽마다 쓰기 포트를 하나 두는 규칙([ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md))에서 PG는 새 부르는 쪽이다. PG에 돌려줄 주문 정보가 없어 `Unit`이고, 그래서 답하는 때에 다시 읽는 일도 없다.
4. **거래 키로 조회하지 않는다.** 알림도 고객의 대사와 같은 PG 주문 번호 조회를 쓴다. 길이 하나라 callback-first와 lookup-first가 구조로 같아진다. 생성 응답을 잃어 거래 키를 모르는 결제에 온 알림도 따로 다룰 것 없이 대사된다. 거래 키 조회는 같은 PG 주문 번호의 두 번째 PG 거래를 가린다. 그래서 `PaymentGateway.retrieveTransaction(transactionKey)`는 여전히 부르는 곳이 없고 `transaction_key`의 인덱스도 두지 않는다([결제 설계](../design/payments.md) 17절의 항목을 닫는다).
5. **확인은 조회의 답으로 한다.** 어댑터가 모든 조회에 제공자 계정(`X-USER-ID: 135135`)을 보내고, `Payment.reconcile`이 조회한 PG 거래를 결제의 PG 주문 번호, 금액, 이미 아는 거래 키와 견준다. 다르면 충돌로 남고 결제는 그대로다. 본문의 거래 키와 PG 상태는 어느 판단에도 들지 않으므로, 꾸민 알림이 할 수 있는 것은 실제 결제 하나를 정직하게 한 번 조회하게 하는 것뿐이다.
6. **PG에 답하는 것은 다시 보낼 값이 있는지다.**

   | 경우 | 답 | 까닭 |
   | --- | --- | --- |
   | 본문의 형식이 틀렸다(`orderId` 없음, 빔, JSON 아님) | 400 | 고칠 것은 PG 쪽이다. |
   | 모르는 PG 주문 번호 | 404 `PAYMENT_NOT_FOUND` | 규칙 2. 다시 받아도 DB를 한 번 읽을 뿐 PG에 묻지 않는다. |
   | 조회할 수 없다(열린 브레이커 포함) | 503 `PG_UNAVAILABLE` | 묻지 못했으니 다시 받으면 답이 바뀔 수 있다. PG 대사 포트가 `CoreException(ErrorType.PG_UNAVAILABLE)`을 던진다. 고객의 대사는 지금처럼 저장된 상태로 답한다. |
   | 반영, 그대로, 충돌, PG 거래 없음, 여럿, 이미 끝난 결제 | 200, 빈 `ApiResponse` | 알림은 대사할 이유였고 대사는 끝났다. 다시 받아도 같은 답이다. |

   열린 브레이커의 기다림(60 s, [ADR 0030](./0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md))은 Toss가 처음 다시 보내는 1분보다 짧다. 조회 하나의 위 끝 3.5 s는 Toss의 10초 안이다.
7. **알림은 인증하지 않는다.** 요청자 헤더도 서명도 없다. 계약에 서명이 없고, `135135`는 커머스가 PG를 부를 때의 계정이다. 규칙 5의 조회가 유일한 방어다.
8. **순서와 중복은 TX B가 맡는다.** 알림, 고객의 대사, 확정의 TX B는 모두 주문을 잠근 뒤 결제를 다시 읽고 `Payment.reconcile`로 앞으로만 간다. 같은 답을 함께 반영해도 주문과 예약은 한 번만 바뀌고([ADR 0024](./0024-confirm-calls-the-pg-between-two-transactions.md) 규칙 5), 알림이 끝낸 뒤 늦게 돌아온 생성 응답은 `Unchanged`나 충돌일 뿐 처리 중이나 결과 미확인으로 되돌리지 않는다. 그래서 중복을 거르는 열쇠(Toss의 `tosspayments-webhook-transmission-id` 같은 것)를 저장하지 않는다. 장면 6의 알림 쪽 절반은 알림이 먼저(확정의 생성이 아직 오가는 동안), 대사가 먼저, 알림과 대사가 함께인 세 장면이다.

## 고르지 않은 것

- **본문의 PG 상태로 반영한다.** 과제가 막고, 꾸민 본문이 주문을 확정한다.
- **본문의 거래 키로 조회한다.** "해당 거래"를 글자 그대로 따르지만 길이 둘이 되고, `transaction_key` 인덱스가 필요하고, 같은 PG 주문 번호의 두 번째 PG 거래를 가린다.
- **주문의 사용자로 고객의 대사를 부른다.** 포트가 늘지 않지만 고객인 척하는 것이다.
- **모든 알림에 200.** 열린 브레이커 동안 온 알림을 다시 받을 길이 없어, 그 결제는 고객의 대사를 기다린다.
- **모르는 PG 주문 번호에 200과 WARN.** 어댑터가 `ErrorType`을 보고 `CoreException`을 잡아야 한다. 웹 층의 다른 곳은 그러지 않는다.
- **본문의 모든 필드를 엄격하게 검사한다.** 읽지 않는 필드가 바뀌면 알림을 잃는다.
- **서명이나 비밀 값을 확인한다.** 계약에 없다.

## 대가

- 끝점에 닿는 누구나 실제 결제의 PG 조회를 부르게 할 수 있다. 브레이커가 그 피해를 묶는다. PR에 알려진 한계로 적는다.
- 본문이 맞아도 알림마다 조회 한 번을 치르고, 그동안 요청 스레드를 최대 3.5 s 쥔다.
- 조회가 0건이면 200이라 다시 보내는 PG도 그 알림을 다시 보내지 않는다. 그 결제는 고객의 대사나 다음 알림을 기다린다.
- 테스트 PG는 알림을 보내지도 다시 보내지도 않는다. 테스트는 실제 컨트롤러에 알림을 직접 보내고, 다시 보내기는 시험하지 않는다.

## 다시 볼 조건

- PG가 알림에 서명을 붙이면 서명을 먼저 확인한다. 그래도 조회는 한다.
- PG가 거래 키로만 결제를 가리키게 되면 그때 `transaction_key` 인덱스와 거래 키 조회를 더한다.
- 알림이 많아 조회가 부담이 되면, 본문의 PG 상태가 처리 중인 알림은 조회를 건너뛰는 것을 생각한다. 그래도 끝난 상태는 조회로만 반영한다.

## 바꾸는 결정

ADR 0028 규칙 6을 채운다. ADR 0028 규칙 6, ADR 0029 규칙 3과 [결제 설계](../design/payments.md) 17절의 날짜 메모는 그 코드를 바꾸는 작업이 단다. `CONTEXT.md`의 알림이 이 ADR을 따른다.
