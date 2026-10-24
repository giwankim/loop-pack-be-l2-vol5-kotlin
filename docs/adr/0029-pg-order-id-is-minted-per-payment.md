---
status: accepted
date: 2026-10-22
---

# PG 주문 번호는 결제마다 새로 만들고, 주문 식별자에서 만들지 않는다

1단계의 결제는 PG 주문 번호를 주문 식별자에서 만들었다(`LP-ORD-` 뒤에 10자리가 되도록 0을 채운 주문 식별자). 과제의 예시("내부 주문 42를 LP-ORD-0000000042로 매핑했다면")를 따른 것이다. 과제가 요구하는 것은 6자 이상의 문자열, 생성과 조회에 같은 값, 그리고 시도 키·PG 주문 번호·거래 키의 역할을 나눠 DB에 보존하는 것뿐이다.

주문 식별자에서 만든 번호는 한 DB의 한 생애 안에서만 유일하다. 개발 DB를 비우거나 백업에서 되살리면 주문 42가 다시 생긴다. 그 주문이 같은 금액으로 결제를 시작하고 TX A 뒤 POST 전에 프로세스가 멈추면, 대사는 `LP-ORD-0000000042`를 조회해 이전 생애의 성공한 PG 거래를 찾는다. PG 주문 번호와 금액이 맞으므로 `Payment.reconcile`이 받아들이고, 돈을 받지 않은 주문이 확정된다. 금액이 다르면 충돌이 되어 주문이 결제 중인 채로 묶인다([멘토 질문 C5](../research/week5-mentor-questions.md)).

1. **결제를 만들 때 `PgOrderIdGenerator`가 새 값을 만든다.** `domain.payment`의 인터페이스이고 주문 식별자를 받지 않는다. 운영 구현은 `adapter.integration.pg`에 있으며 UUID 문자열(36자 ASCII)을 돌려준다. PG의 형식 규칙(6자 이상)은 PG를 아는 어댑터가 지킨다. splearn이 `PasswordEncoder`와 그 구현 `SecurePasswordEncoder`를 나눈 모양이다.
2. **`Payment.start(orderId, idempotencyKey, amount, pgOrderIdGenerator)`가 결제를 만드는 유일한 길이다.** 생성자는 private이라 호출자가 PG 주문 번호를 고를 수 없다. splearn의 `Member.register(request, passwordEncoder)` 모양이고, 이 저장소의 첫 애그리거트 팩터리이자 첫 도메인 인터페이스다. 매개변수는 이름 붙인 인자로 넘기고 도메인 DTO를 두지 않는다. Request는 application에 둔다([카탈로그 설계 5.17](../design/catalog.md)).
3. **`pg_order_id`에 유일 제약(`UK_PAYMENT_ON_PG_ORDER_ID`)을 둔다.** 유일성이 이제 `order_id`의 유일성에서 따라 나오지 않는다. 3단계의 알림이 이 인덱스로 결제를 찾는다. (2026-10-23, [issue 161](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/161): 알림이 이 인덱스를 탄다. `PaymentRepository.findByPgOrderId`는 없으면 `null`이고, `PaymentFinder.findByPgOrderId`가 그것을 `PAYMENT_NOT_FOUND`(404)로 던진다([ADR 0031](./0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md) 규칙 2). 스키마는 바뀌지 않았다.)
4. **테스트는 정해 둔 값을 돌려주는 생성기를 넣는다.** 테스트 PG의 스텁이 확정을 부르기 전에 PG 주문 번호를 알아야 한다.
5. **결제마다 새 값이므로 시도마다 다르다.** 실패한 뒤 다시 결제하는 것이 들어와도 그대로 맞는다.

## 고르지 않은 것

- **주문 식별자에서 만들고 환경 접두사를 붙인다.** 환경끼리는 가르지만, 한 환경의 DB를 비우거나 되살리는 것은 막지 못한다.
- **`Payment`가 초기화에서 UUID를 바로 만든다.** 테스트가 값을 미리 알 수 없다.
- **멱등 키를 PG 주문 번호로 쓴다.** 1자 키는 6자 규칙에 맞지 않고, 고객이 준 값이 PG로 나가며, 과제가 두 역할을 나누라고 한다.
- **생성기가 주문 식별자를 받아 읽을 수 있는 접두사를 붙인다.** PG 화면에서 주문을 알아보기 쉽지만, 생성기의 계약에 주문이 들어온다. 짝은 DB의 결제가 가진다.

## 대가

- PG 주문 번호만 보고는 주문을 알 수 없다. 사람은 DB의 결제로 짝을 찾는다.
- 3단계의 알림은 PG 주문 번호를 풀어 주문 식별자를 얻지 못하고, 결제를 조회해야 한다.
- 파생 형식을 보던 `PaymentTest`의 테스트가 형식과 함께 사라진다. JVM 기본 로캘이 `th-TH-u-nu-thai`일 때 ASCII 숫자를 확인하던 `padStart` 테스트도 그렇다.
- 과제의 예시 형식과 다르다.

## 다시 볼 조건

- PG가 PG 주문 번호의 길이나 문자 집합을 제한하면 어댑터의 생성기만 바꾼다. Toss는 6자에서 64자의 영문, 숫자, `-`, `_`이고, UUID 문자열이 그 안에 든다.

## 바꾸는 결정

[결제 설계](../design/payments.md)의 `pgOrderId` 줄과 `String.format` 메모는 구현하는 작업에서 원문을 두고 날짜 메모를 단다. `CONTEXT.md`의 PG 주문 번호가 이 ADR을 따른다.
