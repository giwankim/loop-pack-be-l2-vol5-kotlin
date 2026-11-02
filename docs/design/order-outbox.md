# 주문 아웃박스 설계 — 확정과 함께 남기는 주문 확정 이벤트

7주차 과제의 설계 문서다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)의 아웃박스, 중계, 누락 구간, 발행하다를 따른다. 결정은 [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md)에 있다. 6주차의 계약은 [주문 이벤트 설계](./order-events.md) 2절이고 바뀌지 않는다.

상태: 2026-11-02 1단계(`feat/order-outbox`)를 인터뷰하고 썼다. 중계(2단계, `feat/outbox-relay`)와 복구와 E2E(3단계, `feat/outbox-recovery`)는 저마다의 인터뷰 뒤에 절을 더한다.

## 1. 근거와 범위

- 과제: [7주차 실습 · Outbox와 재처리로 중단 구간 닫기](https://app.notion.com/p/7-Outbox-4095f76d265480ae8bbdd208dd009d17). 1단계의 근거는 그 과제의 Outbox 모델과 쓰기 규칙, 그리고 중단 장면 가운데 앞의 둘(insert 전후의 실패, 다시 반영)이다.
- 6주차의 결과에서 시작한다. 주문, 결제, 예약의 규칙은 바뀌지 않는다.
- 측정 기록: [payload 열 측정](../research/week7-outbox-payload-column-probe.md).

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | TX B에서 아웃박스 행을 남긴다. 6주차의 전송 경로를 지운다. insert 전후의 실패와 다시 반영. | `feat/order-outbox` | 인터뷰 끝남(2026-11-02). [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191) |
| 2 | 단일 중계. 시도의 기록, 재시도, 영구 실패, 적체, 로컬 재개. 커밋 뒤, 중계 재생성, broker 확인 뒤 PUBLISHED 전의 중단 장면. | `feat/outbox-relay` | 인터뷰 전 |
| 3 | 앱을 건너는 실행. 6주차의 두 사례 재현과 복구, 실제 소비자까지의 E2E, 끝의 검사. | `feat/outbox-recovery` | 인터뷰 전 |

셋은 모두 `week7`로 PR한다.

## 2. 저장 모델

표 `order_event_outbox`, 엔티티 `domain.order.OrderEventOutbox`. `BaseEntity`를 상속한 자기 루트다. 지우는 코드는 없다(과제가 자동 삭제를 막는다).

| 열 | 타입 | 처음 값 | 바뀌는가 |
| --- | --- | --- | --- |
| `event_id` | varchar, 유일 | `order-confirmed:v1:{orderId}` | 아니오 |
| `order_id` | bigint | 주문 식별자 | 아니오 |
| `event_type` | varchar | `OrderConfirmed` | 아니오 |
| `schema_version` | int | 1 | 아니오 |
| `message_key` | varchar | 주문 식별자의 문자열 | 아니오 |
| `payload` | text, `json_valid` 검사 | 본문 JSON | 아니오 |
| `status` | varchar | `PENDING` | 2단계 |
| `attempt_count` | int | 0 | 2단계 |
| `next_attempt_at` | datetime(6) | TX B가 `Clock` 빈에서 읽은 시각 | 2단계 |
| `published_at` | datetime(6), null | 없음 | 2단계 |
| `last_error` | text, null | 없음 | 2단계 |

- `created_at`, `updated_at`, `deleted_at`은 `BaseEntity`의 것이다. `created_at`은 시스템 시계라 시험이 고정한 시계를 따르지 않는다. 그래서 다음 시도의 기준은 `next_attempt_at`이다.
- "바뀌는가"가 아니오인 열은 `val`에 `@Column(updatable = false)`다.
- 제약은 `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`와 `CK_ORDER_EVENT_OUTBOX_PAYLOAD` 둘이다. `(status, next_attempt_at, id)` 인덱스는 그것을 읽는 2단계가 더한다.

### 저장되는 값과 선 위의 값

저장되는 payload는 본문이다.

```json
{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}
```

record의 값은 봉투이고 2단계가 열과 본문으로 다시 만든다. 6주차 경로의 값과 bytes가 같은 것을 한 번 재 보았다.

```json
{"eventId":"order-confirmed:v1:12345","eventType":"OrderConfirmed","schemaVersion":1,"payload":{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":1001,"quantity":1},{"productId":1002,"quantity":2}]}}
```

## 3. 남기는 규칙

| 경우 | 행 |
| --- | --- |
| TX B에서 결제가 SUCCESS로 바뀌어(`ReconcileResult.Changed`) 주문이 처음 CONFIRMED가 됐다. 생성 응답, 고객의 대사, PG의 알림 어느 길이든 같다. | 하나 남긴다. |
| 결제 중, 결과 미확인, 결제에 실패한 주문 | 없다. |
| 끝난 결제에 같은 답을 다시 반영했다(`Unchanged`). 중복 알림, 중복 대사, 늦은 생성 응답. | 새 행이 없다. |
| 충돌(`Conflicted`), 생성 전 거절 | 없다. |
| 포인트로 확정된 이전 주문 | 소급해 남기지 않는다. |
| 직렬화나 저장의 실패 | TX B가 되돌아가므로 없다(5절). |

payload는 잠근 주문의 저장된 품목, 수량, 시각에서 TX B 안에서 만든다.

## 4. 어디에 무엇이 있는가

| 조각 | 자리 |
| --- | --- |
| `OrderEventOutbox`, 상태 enum | `domain.order` |
| `OrderEventOutboxRepository` | `application.order.required` |
| `OrderEventSerializer`, `SerializedOrderEvent` | `application.order.required` |
| `OrderConfirmedEvent` | `application.order.required`, 그대로 |
| 직렬화 구현, `OrderConfirmedMessage` | `adapter.integration.kafka` |
| 행을 쓰는 코드 | `OrderPaymentSagaSteps.applyResult` |

이 모양을 고른 까닭은 ADR 0034의 고르지 않은 것에 있다. 포트 하나 뒤에 아웃박스를 숨기지 않는다. 어댑터는 선 위의 꼴과 전송만 맡고, 아웃박스의 상태와 재시도는 응용 계층과 도메인의 말로 쓴다. 중계는 2단계에서 `OrderPaymentSaga`와 `OrderPaymentSagaSteps`처럼 트랜잭션 밖의 조율과 트랜잭션 단계로 나뉜다.

## 5. 실패

직렬화와 저장의 실패는 TX B의 실패다. 부르는 쪽의 답은 5주차에 TX B가 실패했을 때와 같다.

- 주문은 결제 중, 결제는 결과 미확인, 재고는 예약된 그대로, 행은 없다. PG 거래를 새로 만들지 않는다.
- 뒤따르는 대사가 조회로 같은 PG 거래를 찾아 반영한다. 주문은 CONFIRMED, 예약은 한 번 소비되고 행은 하나다.
- 표가 계속 고장 나면 주문은 결제 중에 머문다(9절).

## 6. payload 열

`TEXT`에 `json_valid` 검사다. 비교와 수치는 [측정 기록](../research/week7-outbox-payload-column-probe.md)에, 판단은 ADR 0034 규칙 6에 있다. 처음에는 저장된 bytes를 그대로 보내려고 `TEXT`를 골랐다. 타입이 있는 전송으로 그 까닭이 사라져 `JSON`을 다시 보았고, 재 본 결과로 `TEXT`에 남았다.

## 7. 지우는 것과 남는 것

- 지운다: `OrderEventPublisher`, `SpringEventOrderEventPublisher`, `OrderConfirmedKafkaRelay`, `orderEventsExecutor`, `@EnableAsync`, `OrderEventPublishGapTest`, 발행의 INFO 로그 장면과 가상 스레드 장면, 트랜잭션 밖 거절 장면.
- 남는다: `OrderConfirmedEvent`, `OrderConfirmedMessage`, `OrderEventsProperties`, 토픽 선언, `OrderEventsTopicTest`, `OverriddenOrderEventsTopicTest`, `OrderEventsProducerTest`, `TopicReader`(2단계가 쓴다).
- 1단계와 2단계 사이의 `week7`은 아무것도 발행하지 않는다.

## 8. 테스트

- **`OrderEventOutboxTest`.** `OrderEventPublishingTest`를 대신한다. 커밋하는 기반(`BaseCommittingApplicationServiceTest`)에서 테스트 PG로 provided 포트를 부르고, 새 트랜잭션에서 행을 읽는다. 첫 확정, 대사, 중복 알림, 경쟁, 늦은 생성 응답, 행이 없는 결과들, 두 주문의 장면이 그대로 옮겨 온다. payload는 문자열 전체를 리터럴과 견준다. 저장된 값을 `Payload`로 읽어 원래 값과 같은지도 본다.
- **`OrderEventOutboxAtomicityTest`.** 과제의 검사 필터 `*OutboxAtomicityTest`에 걸린다. 두 장면이다.
  - 저장 전: 저장소의 `save`를 springmockk spy로 던지게 한다.
  - 저장 뒤: `failTxBOnceAfterItsWrites`. flush에 insert가 들어 있다.
  - 둘 다 새 트랜잭션에서 주문이 결제 중, 결제가 결과 미확인, 재고가 예약된 그대로, 행 0개, PG 생성 1회임을 본다. 이어서 대사하면 CONFIRMED, 예약 소비 한 번, 행 1개, PG 생성은 여전히 1회다.
  - 직렬화의 실패는 저장 전 장면과 같은 길이라 따로 두지 않는다.
- **저장소 테스트.** `BaseRepositoryTest`에서 DB 제약을 본다. 같은 `event_id`와 올바르지 않은 JSON은 거절되고, 관리되는 엔티티의 사건 열을 리플렉션으로 바꿔 flush해도 다시 읽은 값은 그대로다.
- 끝의 확인은 commerce-api의 `ktlintCheck`와 `check`다.

## 9. 받아들인 한계

- 아웃박스 표가 고장 나면 청구된 주문이 결제 중에 머문다. 이벤트 없는 확정을 막는 값이다.
- 1단계만으로는 아무것도 발행되지 않는다.
- 운영의 스키마는 다른 표처럼 엔티티 선언이 문서다. 마이그레이션 도구가 없다.
- 저장된 판을 읽을 책임이 생겼다. v1 하나뿐인 지금은 해가 없다(ADR 0034의 대가).

## 10. 다음 단계로 넘기는 것

- **중계(2단계).**
  - `OrderEventPublisher`가 돌아온다. 트랜잭션 밖에서만 돌고, 어댑터가 행의 값으로 `OrderConfirmedMessage`를 만들어 보낸다.
  - `KafkaTemplate<String, OrderConfirmedMessage>` 전용 빈. 공용 빈은 `<Any, Any>`라 주입되지 않는다. 직렬화기는 `kafka.yml`을 따른다.
  - record의 bytes를 문자열 전체로 못 박는 테스트. 6주차의 `isStrictlyEqualTo`는 키의 차례를 보지 않는다.
  - 상태의 변화, 재시도 간격, `(status, next_attempt_at, id)` 인덱스, 발행의 INFO와 WARN 로그.
  - 변하지 않는 열이 상태 변화 뒤에도 그대로인지 다시 본다.
- **복구와 E2E(3단계).** 6주차 경로를 `week6` 판에서 다시 만들어 두 사례를 재현한다. 저장된 사실로 행을 더하는 코드가 TX B의 코드를 되풀이하면 뽑는다.

## 11. 과제의 이름과 이 저장소의 이름

| 과제 | 이 저장소 | 까닭 |
| --- | --- | --- |
| Outbox | 아웃박스, `OrderEventOutbox`, `order_event_outbox` | 토픽 하나를 맡으므로 이름에 드러낸다. |
| relay | 중계 | 6주차의 말을 이어 쓰되 뜻이 커밋 뒤 직접 전송에서 폴링으로 바뀐다. |
| `eventId`, `orderId`, `eventType`, `schemaVersion`, `messageKey`, `payload`, `status`, `createdAt`, `publishedAt`, `attemptCount`, `nextAttemptAt`, `lastError` | 그대로 | 과제의 필드 이름이 저장소의 관례와 맞는다. |
| 저장된 payload를 그대로 보낸다 | 저장된 사실로 메시지를 다시 만들어 보낸다 | ADR 0034 규칙 9에 벗어남과 까닭을 적었다. |
