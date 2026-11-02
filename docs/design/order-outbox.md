# 주문 아웃박스 설계 — 확정과 함께 남기는 주문 확정 이벤트

7주차 과제의 설계 문서다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)의 아웃박스, 중계, 누락 구간, 발행하다를 따른다. 결정은 [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md)에 있다. 6주차의 계약은 [주문 이벤트 설계](./order-events.md) 2절이고 바뀌지 않는다.

상태: 2026-11-02 1단계(`feat/order-outbox`)를 인터뷰하고 썼다. 중계(2단계, `feat/outbox-relay`)와 복구와 E2E(3단계, `feat/outbox-recovery`)는 저마다의 인터뷰 뒤에 절을 더한다. 같은 날 1단계([명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191))를 마치고, 구현이 바꾼 것을 날짜 메모로 달고 1.2절과 12절을 더했다.

## 1. 근거와 범위

- 과제: [7주차 실습 · Outbox와 재처리로 중단 구간 닫기](https://app.notion.com/p/7-Outbox-4095f76d265480ae8bbdd208dd009d17). 1단계의 근거는 그 과제의 Outbox 모델과 쓰기 규칙, 그리고 중단 장면 가운데 앞의 둘(insert 전후의 실패, 다시 반영)이다.
- 6주차의 결과에서 시작한다. 주문, 결제, 예약의 규칙은 바뀌지 않는다.
- 측정 기록: [payload 열 측정](../research/week7-outbox-payload-column-probe.md).

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | TX B에서 아웃박스 행을 남긴다. 6주차의 전송 경로를 지운다. insert 전후의 실패와 다시 반영. | `feat/order-outbox` | 인터뷰 끝남(2026-11-02). 구현 끝남(2026-11-02, 1.2절). [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191) |
| 2 | 단일 중계. 시도의 기록, 재시도, 영구 실패, 적체, 로컬 재개. 커밋 뒤, 중계 재생성, broker 확인 뒤 PUBLISHED 전의 중단 장면. | `feat/outbox-relay` | 인터뷰 전 |
| 3 | 앱을 건너는 실행. 6주차의 두 사례 재현과 복구, 실제 소비자까지의 E2E, 끝의 검사. | `feat/outbox-recovery` | 인터뷰 전 |

셋은 모두 `week7`로 PR한다.

### 1.2 1단계가 만든 것

2026-11-02 [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 티켓이다. 명세가 이름 지은 것(`OrderEventOutbox`, `order_event_outbox`, `OrderEventOutboxRepository`, `OrderEventSerializer`, `SerializedOrderEvent`, `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`, `CK_ORDER_EVENT_OUTBOX_PAYLOAD`, `OrderEventOutboxTest`, `OrderEventOutboxAtomicityTest`)은 모두 그대로 최종 이름이 됐다. (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: `CK_ORDER_EVENT_OUTBOX_PAYLOAD`만은 그렇지 않다. 닫는 검토에서 `CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID`로 고쳤다. 2절의 메모.) 명세가 이름 짓지 않은 것은 이렇게 정해졌다. 상태 enum은 `OrderEventOutboxStatus`(`PENDING`, `PUBLISHED`), 직렬화 구현은 `JacksonOrderEventSerializer`, 쓰는 코드는 `applyResult`가 부르는 private `leaveInOutbox`, 저장소 테스트는 `OrderEventOutboxRepositoryTest`와 `OrderEventOutboxImmutabilityTest`, fixture는 `createOrderEventOutbox`다. 저장소 포트의 메서드는 `save`, `findByEventId`(없으면 null), `count`다. `BLOCKED`를 둘지는 여전히 2단계가 정한다.

| 티켓 | 만든 것 |
| --- | --- |
| [192](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/192) | 엔티티와 상태 enum, 저장소 포트, 직렬화 포트와 구현, `applyResult`의 쓰기. 첫 확정의 장면 `OrderEventOutboxTest`(5), 제약의 거절 `OrderEventOutboxRepositoryTest`(4), 사건 열의 불변 `OrderEventOutboxImmutabilityTest`(1). `CODING_STANDARDS.md`에 커밋하는 저장소 테스트의 조항. |
| [193](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/193) | 첫 확정이 아닌 길과 행이 없는 결과의 장면 11개(`OrderEventOutboxTest` 5 → 16). 운영 코드는 바뀌지 않았다. |
| [194](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/194) | 저장 전후의 실패와 다시 반영 `OrderEventOutboxAtomicityTest`(2). 운영 코드는 바뀌지 않았다. |
| [195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195) | 6주차의 전송 경로를 지웠다(7절). 남은 KDoc의 말을 고치고 `CODING_STANDARDS.md`에 날짜 메모 여섯을 달았다. |
| [196](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/196) | 이 문서의 날짜 메모와 이 표, 12절. [주문 이벤트 설계](./order-events.md)의 날짜 메모, `OrderEventConsumedCaseTest`의 KDoc, 글감의 머리말. |

운영 코드에서 행을 쓰는 곳은 `leaveInOutbox` 하나이고, commerce-api의 주 코드에서 `KafkaTemplate`을 부르는 곳은 없다. `OrderPaymentIntegrationTest`(63)는 고치지 않은 채 통과하므로 5주차의 확정, 대사, 알림의 응답은 바뀌지 않았다.

commerce-streamer의 코드와 계약은 바뀌지 않았다. 바뀐 것은 지운 테스트 둘을 현재형으로 가리키던 `OrderEventConsumedCaseTest`의 KDoc뿐이다. 그 밖의 streamer KDoc이 말하는 "commerce-api의 중계"는 2단계에서 돌아오므로 두었다.

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
- 제약은 `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`와 `CK_ORDER_EVENT_OUTBOX_PAYLOAD` 둘이다. `(status, next_attempt_at, id)` 인덱스는 그것을 읽는 2단계가 더한다. (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: 검사의 이름은 `CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID`다. `CODING_STANDARDS.md`의 제약 이름이 한 컬럼의 규칙을 `<컬럼>_<조건>`으로 부른다.)
- (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: 엔티티가 받은 값을 스스로 검사한다. `Order`, `Payment`, `Product`, `Brand`처럼 `init`에서 공백인 `event_id`, `event_type`, `message_key`, `payload`와 0 이하인 `order_id`, `schema_version`을 거절한다. payload가 올바른 JSON인지는 여전히 CHECK가 본다. 예외 `InvalidOrderEventOutboxException`은 이웃과 달리 `RuleViolationException`이 아니다. 웹 어댑터가 그것을 400으로 옮기는데, 이 값들은 요청에서 오지 않으므로 고객의 잘못이 아니고, 5절대로 부르는 쪽의 답은 TX B가 실패했을 때와 같아야 한다. 실패는 직렬화의 실패처럼 TX B를 되돌리는 같은 길이라 원자성 장면을 더하지 않았다. 테스트는 `domain.order.OrderEventOutboxValidationTest`다. 이웃처럼 `OrderEventOutboxTest`로 지으면 12절의 필터에 응용 계층의 장면과 함께 걸린다.)
- (2026-11-02, [issue 192](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/192): `status`는 표대로 varchar이고 `columnDefinition = "varchar(16)"`로 선언했다. 저장소의 다른 상태 열이 모두 MySQL `enum`인 것과 일부러 다르다. `@Enumerated(STRING)`만 두면 Hibernate가 `enum ('PENDING','PUBLISHED')`을 만들어, 2단계가 값을 더할 때 스키마를 바꿔야 한다. `@JdbcTypeCode(SqlTypes.VARCHAR)`는 varchar를 주지만 이름 없는 `check (status in (...))`를 더해 위의 둘에 없는 셋째 제약이 된다. `columnDefinition`은 표의 타입과 제약 둘을 그대로 준다. 필드의 KDoc에도 적었다.)

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
| 끝난 결제에 같은 답을 다시 반영했다(`Unchanged`). 중복 알림, 중복 대사, 늦은 생성 응답. (2026-11-02, [issue 193](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/193): 차례로 오는 중복 알림과 거듭한 대사는 `Unchanged`에 닿지 않는다. 끝난 주문을 본 `OrderPaymentSaga.checkLookupNeeded`에서 조회와 TX B 전에 돌아간다. `Unchanged`에 닿는 것은 경쟁하는 중복 알림과 늦은 생성 응답이다. 8절의 메모.) | 새 행이 없다. |
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

- 지운다: `OrderEventPublisher`, `SpringEventOrderEventPublisher`, `OrderConfirmedKafkaRelay`, `orderEventsExecutor`, `@EnableAsync`, `OrderEventPublishGapTest`, 발행의 INFO 로그 장면과 가상 스레드 장면, 트랜잭션 밖 거절 장면. (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): 그 세 장면이 든 `OrderEventPublishingTest`도 지웠다. 나머지 장면은 8절의 두 클래스로 옮겼다. 지운 두 테스트는 `week6` 판에 남고, 3단계의 재현은 그 판에서 시작한다.)
- 남는다: `OrderConfirmedEvent`, `OrderConfirmedMessage`, `OrderEventsProperties`, 토픽 선언, `OrderEventsTopicTest`, `OverriddenOrderEventsTopicTest`, `OrderEventsProducerTest`, `TopicReader`(2단계가 쓴다). (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): `TopicReader`와 로그를 읽는 도우미 `linesAt`, `awaitLinesAt`은 지금 부르는 곳이 없다. 2단계의 장면이 쓴다. `TopicReader`가 조용히 기다리는 1 s는 2단계의 폴링에 맞춰 다시 본다.)
- 1단계와 2단계 사이의 `week7`은 아무것도 발행하지 않는다.
- (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): **봉투의 빈틈.** 지운 `OrderEventPublishingTest`의 첫 장면은 봉투 전체(`eventId`, `eventType`, `schemaVersion`과 그 아래의 `payload`)와 header가 없는 record도 보았다. 행으로는 볼 수 없는 것이라 옮기지 못했고, 2단계의 bytes 테스트(10절)까지 이것을 못 박는 테스트가 없다. 직렬화 구현이 `OrderConfirmedMessage.from`을 불러 본문만 남기므로 장면들은 나머지를 읽지 않는다. 행의 사건 열과 본문은 못 박혀 있고, 이 단계에는 그 봉투를 보내는 곳이 없어 선 위에서 깨질 것도 없다.)
- (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): **`Executor` 빈.** `orderEventsExecutor`를 지우자 Boot의 `applicationTaskExecutor`(`ThreadPoolTaskExecutor`)가 돌아왔다. 그 빈에 기대는 빈은 없다. 바뀌는 것은 MVC의 비동기 실행기뿐이고 비동기 타입을 돌려주는 처리기가 없어 쓰이지 않는다. 임시 테스트로 컨텍스트를 전후로 읽어 확인했다. 그 임시 테스트가 지나지 않는 길에서 실행기를 찾는 라이브러리는 전체 테스트의 통과가 증거다.)

## 8. 테스트

- **`OrderEventOutboxTest`.** `OrderEventPublishingTest`를 대신한다. 커밋하는 기반(`BaseCommittingApplicationServiceTest`)에서 테스트 PG로 provided 포트를 부르고, 새 트랜잭션에서 행을 읽는다. 첫 확정, 대사, 중복 알림, 경쟁, 늦은 생성 응답, 행이 없는 결과들, 두 주문의 장면이 그대로 옮겨 온다. payload는 문자열 전체를 리터럴과 견준다. 저장된 값을 `Payload`로 읽어 원래 값과 같은지도 본다. (2026-11-02, [issue 193](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/193): 16개다. 다른 금액의 성공 PG 거래를 찾은 대사, 곧 성공이라 말하는 충돌의 장면을 하나 더했다. 빨간 확인은 명세의 말과 다르다. `Unchanged`에도 행을 쓰는 변이는 넷이 아니라 경쟁하는 중복 알림과 늦은 생성 응답 둘만 빨갛게 한다. 차례로 오는 둘은 3절의 메모대로 TX B에 닿지 않아 초록이고, 끝난 주문도 TX B로 보내는 변이를 더해야 넷이 빨개진다. 실패도 행 2개가 아니라 유일 제약이 거절한 둘째 TX B의 `DataIntegrityViolationException`이다. 변이 다섯은 글감의 "Issue 193" 절에 있다.)
- **`OrderEventOutboxAtomicityTest`.** 과제의 검사 필터 `*OutboxAtomicityTest`에 걸린다. 두 장면이다. (2026-11-02, [issue 194](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/194): 쓰기를 `REQUIRES_NEW`로 돌리는 변이는 저장 뒤 장면만 빨갛게 한다. 행이 따로 커밋되어 0개가 아니라 1개다. 저장 전 장면은 stub이 아무것도 쓰지 않고 던진 예외가 그대로 TX B를 되돌려 초록이다. 저장 전 장면을 빨갛게 하는 것은 그 실패를 `runCatching`으로 삼키는 변이까지 더했을 때다. 곧 저장 전 장면은 삼킨 실패를, 저장 뒤 장면은 따로 커밋된 행을 잡는다.)
  - 저장 전: 저장소의 `save`를 springmockk spy로 던지게 한다.
  - 저장 뒤: `failTxBOnceAfterItsWrites`. flush에 insert가 들어 있다. (2026-11-02, [issue 194](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/194): 틀렸다. ID가 `IDENTITY`라 행의 `INSERT`는 그 flush가 아니라 `save`에서 나간다. [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 같은 말도 그렇다. 장면이 insert 뒤에 실패한다는 것은 그대로이고, 장면마다 `captureSql`로 보낸 insert를 세어 저장 전 0, 저장 뒤 1을 단언한다.)
  - 둘 다 새 트랜잭션에서 주문이 결제 중, 결제가 결과 미확인, 재고가 예약된 그대로, 행 0개, PG 생성 1회임을 본다. 이어서 대사하면 CONFIRMED, 예약 소비 한 번, 행 1개, PG 생성은 여전히 1회다.
  - 직렬화의 실패는 저장 전 장면과 같은 길이라 따로 두지 않는다.
- **저장소 테스트.** `BaseRepositoryTest`에서 DB 제약을 본다. 같은 `event_id`와 올바르지 않은 JSON은 거절되고, 관리되는 엔티티의 사건 열을 리플렉션으로 바꿔 flush해도 다시 읽은 값은 그대로다. (2026-11-02, [issue 192](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/192): 두 클래스다. `OrderEventOutboxRepositoryTest`는 두 거절을 제약의 이름까지 보고, 시각의 마이크로초 절단과 지운 행이 없는 행임을 더 본다. 불변은 새 트랜잭션에서 읽으려면 커밋해야 해서 `OrderEventOutboxImmutabilityTest`로 나눴다. 기반은 같고, 클래스 수준에서 테스트 트랜잭션을 벗어나며, 커밋한 것을 스스로 지운다.) (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: 두 클래스 모두 행을 `BaseRepositoryTest`의 `prepareOrderEventOutbox`로 준비하고, `save`는 거절을 보는 두 장면에만 드러난다. 그 도우미는 주문 ID를 받는다. 행이 외래 키 없이 ID로만 주문을 가리키므로, 커밋하는 불변 장면이 주문을 남기지 않고 ID를 적는다.)
- 끝의 확인은 commerce-api의 `ktlintCheck`와 `check`다. (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): commerce-streamer의 것도 함께 돌린다. `scripts/final-check`이고 12절에 있다.)

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

## 12. 1단계의 명령

```bash
# 첫 확정과 그 밖의 길의 행: OrderEventOutboxTest(16)
./gradlew :apps:commerce-api:test --tests '*OrderEventOutboxTest'

# 저장 전후의 실패와 다시 반영: OrderEventOutboxAtomicityTest(2). 과제의 필터다. 장면마다 실행 값을 INFO 한 줄로 남긴다(scripts/scene-lines).
./gradlew :apps:commerce-api:test --tests '*OutboxAtomicityTest'

# DB가 지키는 것: OrderEventOutboxRepositoryTest(4), OrderEventOutboxImmutabilityTest(1)
./gradlew :apps:commerce-api:test --tests '*OrderEventOutboxRepositoryTest' --tests '*OrderEventOutboxImmutabilityTest'

# 엔티티가 받은 값의 검사: OrderEventOutboxValidationTest(18)
./gradlew :apps:commerce-api:test --tests '*OrderEventOutboxValidationTest'

# 남은 토픽과 producer 설정: OrderEventsTopicTest(1), OverriddenOrderEventsTopicTest(1), OrderEventsProducerTest(1). 첫 필터가 앞의 둘을 찾는다.
./gradlew :apps:commerce-api:test --tests '*OrderEventsTopicTest' --tests '*OrderEventsProducerTest'

# 이벤트: OrderConfirmedEventTest(5)
./gradlew :apps:commerce-api:test --tests '*OrderConfirmedEventTest'

# 5주차의 장면이 그대로인지: OrderPaymentIntegrationTest(63)
./gradlew :apps:commerce-api:test --tests '*PaymentIntegrationTest'

# 끝의 확인. 트리 전체의 ktlint, 두 앱의 cleanTest와 check, 수까지 돌리고 commit과 시각을 함께 적는다.
scripts/final-check
```

- 끝의 확인은 2026-11-02 `dc9a3ef`의 깨끗한 트리에서 `scripts/final-check`으로 돌렸다(05:04:20Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(53 s). commerce-api는 81개 클래스, 949개 테스트, 실패 0, 오류 0, 건너뜀 0이다(3 min 37 s). `dc9a3ef` 뒤로는 문서만 바뀌었다. 위 블록의 거른 명령도 같은 날 그 트리에서 하나씩 돌려 괄호의 수를 읽었다.
- 1단계의 수는 6주차의 끝(79개 클래스, 943개 테스트)에서 이렇게 왔다. `OrderEventOutboxTest` 16, `OrderEventOutboxRepositoryTest` 4, `OrderEventOutboxAtomicityTest` 2, `OrderEventOutboxImmutabilityTest` 1을 더하고(4개 클래스, 23개), `OrderEventPublishingTest` 15와 `OrderEventPublishGapTest` 2를 뺐다(2개 클래스, 17개). streamer의 수는 그대로다.
- `--tests`로 거른 commerce-api 실행은 언제나 `HexagonalArchitectureTest`의 7개 규칙도 함께 돌고, 위의 수는 그것을 뺀 것이다. 필터가 아무것도 찾지 못해도 실패하지 않으므로 수를 확인한다. 지운 `*OrderEventPublishingTest`로 돌려 보니 그 7개만 돌고 통과했다.
- 거른 실행은 `apps/commerce-api/build/test-results/test`를 갈아 치운다. 끝의 확인의 보고서는 `build/final-check`에 복사되어 남는다.
- 컨텍스트를 띄우는 명령은 Docker가 있어야 한다. 전체 컨텍스트가 MySQL, Redis, Kafka 컨테이너를 가져온다. 이 단계에서 commerce-api는 broker에 보내지 않지만, 토픽 선언과 `fail-fast` 때문에 broker는 여전히 있어야 뜬다([주문 이벤트 설계](./order-events.md) 8절).
