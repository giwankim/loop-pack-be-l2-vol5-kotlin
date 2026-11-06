# 주문 아웃박스 설계 — 확정과 함께 남기는 주문 확정 이벤트

7주차 과제의 설계 문서다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)의 아웃박스, 중계, 누락 구간, 발행하다를 따른다. 결정은 [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md)에 있다. 6주차의 계약은 [주문 이벤트 설계](./order-events.md) 2절이고 바뀌지 않는다.

상태: 2026-11-02 1단계(`feat/order-outbox`)를 인터뷰하고 썼다. 중계(2단계, `feat/outbox-relay`)와 복구와 E2E(3단계, `feat/outbox-recovery`)는 저마다의 인터뷰 뒤에 절을 더한다. 같은 날 1단계([명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191))를 마치고, 구현이 바꾼 것을 날짜 메모로 달고 1.2절과 12절을 더했다. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 2단계를 인터뷰해 13절을 더했다. 결정은 [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md)에 있다.) (2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 2단계를 마치고 13.11절부터 13.13절까지를 더했다.) (2026-11-06, [명세 209](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/209): 3단계를 인터뷰해 14절을 더했다. 결정은 [ADR 0036](../adr/0036-missing-outbox-rows-are-recovered-for-named-orders-from-stored-facts.md)에 있다.) (2026-11-06, [issue 217](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/217): 3단계를 마치고 14.7절과 14.8절을 더했다.)

## 1. 근거와 범위

- 과제: [7주차 실습 · Outbox와 재처리로 중단 구간 닫기](https://app.notion.com/p/7-Outbox-4095f76d265480ae8bbdd208dd009d17). 1단계의 근거는 그 과제의 Outbox 모델과 쓰기 규칙, 그리고 중단 장면 가운데 앞의 둘(insert 전후의 실패, 다시 반영)이다.
- 6주차의 결과에서 시작한다. 주문, 결제, 예약의 규칙은 바뀌지 않는다.
- 측정 기록: [payload 열 측정](../research/week7-outbox-payload-column-probe.md).

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | TX B에서 아웃박스 행을 남긴다. 6주차의 전송 경로를 지운다. insert 전후의 실패와 다시 반영. | `feat/order-outbox` | 인터뷰 끝남(2026-11-02). 구현 끝남(2026-11-02, 1.2절). [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191) |
| 2 | 단일 중계. 시도의 기록, 재시도, 영구 실패, 적체, 로컬 재개. 커밋 뒤, 중계 재생성, broker 확인 뒤 PUBLISHED 전의 중단 장면. | `feat/outbox-relay` | 인터뷰 끝남(2026-11-04, 13절). 구현 끝남(2026-11-04, 13.12절). [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198) |
| 3 | 앱을 건너는 실행. 6주차의 두 사례 재현과 복구, 실제 소비자까지의 E2E, 끝의 검사. | `feat/outbox-recovery` | 인터뷰 끝남(2026-11-06, 14절). 구현 끝남(2026-11-06, 14.7절). [명세 209](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/209) |

셋은 모두 `week7`로 PR한다.

### 1.2 1단계가 만든 것

2026-11-02 [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 티켓이다. 명세가 이름 지은 것(`OrderEventOutbox`, `order_event_outbox`, `OrderEventOutboxRepository`, `OrderEventSerializer`, `SerializedOrderEvent`, `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`, `CK_ORDER_EVENT_OUTBOX_PAYLOAD`, `OrderEventOutboxTest`, `OrderEventOutboxAtomicityTest`)은 모두 그대로 최종 이름이 됐다. (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: `CK_ORDER_EVENT_OUTBOX_PAYLOAD`만은 그렇지 않다. 닫는 검토에서 `CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID`로 고쳤다. 2절의 메모.) 명세가 이름 짓지 않은 것은 이렇게 정해졌다. 상태 enum은 `OrderEventOutboxStatus`(`PENDING`, `PUBLISHED`), 직렬화 구현은 `JacksonOrderEventSerializer`, 쓰는 코드는 `applyResult`가 부르는 private `leaveInOutbox`, 저장소 테스트는 `OrderEventOutboxRepositoryTest`와 `OrderEventOutboxImmutabilityTest`, fixture는 `createOrderEventOutbox`다. 저장소 포트의 메서드는 `save`, `findByEventId`(없으면 null), `count`다. `BLOCKED`를 둘지는 여전히 2단계가 정한다. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 둔다. 13.3절이다.)

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
- 제약은 `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`와 `CK_ORDER_EVENT_OUTBOX_PAYLOAD` 둘이다. `(status, next_attempt_at, id)` 인덱스는 그것을 읽는 2단계가 더한다. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 이 인덱스는 빠른 조회가 아니라 올바른 동작을 위한 것이다. 13.2절이다.) (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: 검사의 이름은 `CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID`다. `CODING_STANDARDS.md`의 제약 이름이 한 컬럼의 규칙을 `<컬럼>_<조건>`으로 부른다.)
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

이 모양을 고른 까닭은 ADR 0034의 고르지 않은 것에 있다. 포트 하나 뒤에 아웃박스를 숨기지 않는다. 어댑터는 선 위의 꼴과 전송만 맡고, 아웃박스의 상태와 재시도는 응용 계층과 도메인의 말로 쓴다. 중계는 2단계에서 `OrderPaymentSaga`와 `OrderPaymentSagaSteps`처럼 트랜잭션 밖의 조율과 트랜잭션 단계로 나뉜다. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 트랜잭션 밖과 안으로 나뉘는 것은 그대로이지만 사가도 단계도 아니다. 상태의 규칙은 엔티티에 있다. 13.5절이다.)

## 5. 실패

직렬화와 저장의 실패는 TX B의 실패다. 부르는 쪽의 답은 5주차에 TX B가 실패했을 때와 같다.

- 주문은 결제 중, 결제는 결과 미확인, 재고는 예약된 그대로, 행은 없다. PG 거래를 새로 만들지 않는다.
- 뒤따르는 대사가 조회로 같은 PG 거래를 찾아 반영한다. 주문은 CONFIRMED, 예약은 한 번 소비되고 행은 하나다.
- 표가 계속 고장 나면 주문은 결제 중에 머문다(9절).

## 6. payload 열

`TEXT`에 `json_valid` 검사다. 비교와 수치는 [측정 기록](../research/week7-outbox-payload-column-probe.md)에, 판단은 ADR 0034 규칙 6에 있다. 처음에는 저장된 bytes를 그대로 보내려고 `TEXT`를 골랐다. 타입이 있는 전송으로 그 까닭이 사라져 `JSON`을 다시 보았고, 재 본 결과로 `TEXT`에 남았다.

## 7. 지우는 것과 남는 것

- 지운다: `OrderEventPublisher`, `SpringEventOrderEventPublisher`, `OrderConfirmedKafkaRelay`, `orderEventsExecutor`, `@EnableAsync`, `OrderEventPublishGapTest`, 발행의 INFO 로그 장면과 가상 스레드 장면, 트랜잭션 밖 거절 장면. (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): 그 세 장면이 든 `OrderEventPublishingTest`도 지웠다. 나머지 장면은 8절의 두 클래스로 옮겼다. 지운 두 테스트는 `week6` 판에 남고, 3단계의 재현은 그 판에서 시작한다.)
- 남는다: `OrderConfirmedEvent`, `OrderConfirmedMessage`, `OrderEventsProperties`, 토픽 선언, `OrderEventsTopicTest`, `OverriddenOrderEventsTopicTest`, `OrderEventsProducerTest`, `TopicReader`(2단계가 쓴다). (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): `TopicReader`와 로그를 읽는 도우미 `linesAt`, `awaitLinesAt`은 지금 부르는 곳이 없다. 2단계의 장면이 쓴다. `TopicReader`가 조용히 기다리는 1 s는 2단계의 폴링에 맞춰 다시 본다.) (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 그 값은 2단계의 마지막 티켓이 본다.) (2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 1 s로 둔다. 까닭은 13.11절이다.)
- 1단계와 2단계 사이의 `week7`은 아무것도 발행하지 않는다.
- (2026-11-02, [issue 195](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/195): **봉투의 빈틈.** 지운 `OrderEventPublishingTest`의 첫 장면은 봉투 전체(`eventId`, `eventType`, `schemaVersion`과 그 아래의 `payload`)와 header가 없는 record도 보았다. 행으로는 볼 수 없는 것이라 옮기지 못했고, 2단계의 bytes 테스트(10절)까지 이것을 못 박는 테스트가 없다. 직렬화 구현이 `OrderConfirmedMessage.from`을 불러 본문만 남기므로 장면들은 나머지를 읽지 않는다. 행의 사건 열과 본문은 못 박혀 있고, 이 단계에는 그 봉투를 보내는 곳이 없어 선 위에서 깨질 것도 없다.) (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 이 빈틈은 2단계의 첫 코드 티켓이 record 전체를 문자열로 못 박아 닫는다. 13.9절이다.)
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
  - `OrderEventPublisher`가 돌아온다. 트랜잭션 밖에서만 돌고, 어댑터가 행의 값으로 `OrderConfirmedMessage`를 만들어 보낸다. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 돌아오되 행의 목록을 받아 행마다 결과를 돌려준다. 13.5절이다.)
  - `KafkaTemplate<String, OrderConfirmedMessage>` 전용 빈. 공용 빈은 `<Any, Any>`라 주입되지 않는다. 직렬화기는 `kafka.yml`을 따른다. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 전용 빈을 두지 않는다. 공용 `KafkaTemplate<Any, Any>`를 그대로 주입한다. 덮어쓸 설정이 없으면 까닭이 없다. 13.5절이다.)
  - record의 bytes를 문자열 전체로 못 박는 테스트. 6주차의 `isStrictlyEqualTo`는 키의 차례를 보지 않는다.
  - 상태의 변화, 재시도 간격, `(status, next_attempt_at, id)` 인덱스, 발행의 INFO와 WARN 로그. (2026-11-04, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198): 13절이다.)
  - 변하지 않는 열이 상태 변화 뒤에도 그대로인지 다시 본다.
- **복구와 E2E(3단계).** 6주차 경로를 `week6` 판에서 다시 만들어 두 사례를 재현한다. 저장된 사실로 행을 더하는 코드가 TX B의 코드를 되풀이하면 뽑는다. (2026-11-06, [명세 209](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/209): `week6` 판을 띄우지 않는다. 두 경로가 다른 TX B의 한 곳만 테스트에서 6주차처럼 바꾼다. ADR 0036 규칙 5와 14.3절이다. 행 만들기는 `OrderEventOutboxFactory`로 뽑아 TX B와 누락 복구가 나눠 쓴다. 14.2절이다.)

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
- (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: 검토의 수정 뒤 끝의 확인을 `8ceda35`의 깨끗한 트리에서 `scripts/final-check`으로 다시 돌렸다(05:24:19Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(52 s). commerce-api는 82개 클래스, 969개 테스트, 실패 0, 오류 0, 건너뜀 0이다(3 min 16 s). 위의 949개에서 `OrderEventOutboxValidationTest` 18과 `OrderFixturesTest`의 2가 늘었다. `8ceda35` 뒤로는 문서만 바뀌었다.)
- `--tests`로 거른 commerce-api 실행은 언제나 `HexagonalArchitectureTest`의 7개 규칙도 함께 돌고, 위의 수는 그것을 뺀 것이다. 필터가 아무것도 찾지 못해도 실패하지 않으므로 수를 확인한다. 지운 `*OrderEventPublishingTest`로 돌려 보니 그 7개만 돌고 통과했다.
- 거른 실행은 `apps/commerce-api/build/test-results/test`를 갈아 치운다. 끝의 확인의 보고서는 `build/final-check`에 복사되어 남는다.
- 컨텍스트를 띄우는 명령은 Docker가 있어야 한다. 전체 컨텍스트가 MySQL, Redis, Kafka 컨테이너를 가져온다. 이 단계에서 commerce-api는 broker에 보내지 않지만, 토픽 선언과 `fail-fast` 때문에 broker는 여전히 있어야 뜬다([주문 이벤트 설계](./order-events.md) 8절).

## 13. 중계

2026-11-04 2단계를 인터뷰해 [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)로 정했다. 까닭과 고르지 않은 것은 [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md)에, 수치는 [중계 측정 기록](../research/week7-outbox-relay-probe.md)에 있다. 이 절은 구현 전에 썼고, 구현이 바꾼 것은 날짜 메모로 단다.

### 13.1 범위

과제의 단일 relay와 재시도·운영 재개, 그리고 중단 장면의 뒤 셋이다. 장면은 broker까지 본다.

| 과제의 장면 | 이 단계가 보는 것 | 3단계가 보는 것 |
| --- | --- | --- |
| commit 후 relay 실행 전 중단 | 주문 CONFIRMED, 행 `PENDING`, 토픽에 record 없음 | 구매 활동 없음 |
| relay 재생성 후 실행 | 새 중계가 저장된 행을 찾아 발행함 | 정상 구매 활동 |
| broker 확인 뒤 `PUBLISHED` 저장 전 중단 | 행 `PENDING`, 같은 이벤트의 record가 다른 offset에 하나 더 | 구매 활동이 늘지 않음 |

### 13.2 한 회차

1. **가져간다.** 한 트랜잭션에서 차례가 된 행을 20개까지 `FOR UPDATE SKIP LOCKED`로 읽고, 행마다 시도를 시작한다.
2. **발행한다.** 트랜잭션 없이 모두 보낸다.
3. **기다린다.** future가 모두 끝나기를 기다린다. 로컬 대기 시간은 없고 `delivery.timeout.ms`가 끝을 보장한다.
4. **결과를 남긴다.** 행마다 자기 트랜잭션에서 행을 잠그고 다시 읽어 바꾼다.
5. **되풀이한다.** 가져온 행이 20개보다 적으면 끝내고 1 s 쉰다.

차례가 된 행은 `status = 'PENDING'`이고 `next_attempt_at`이 `Clock` 빈의 지금 이전인 행이다. 차례는 `next_attempt_at`, `id` 순이다. (2026-11-04, [issue 200](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/200): Hibernate가 내보내는 잠금은 `for update of <별칭> skip locked`다. 표가 하나라 `for update skip locked`와 같은 행을 잠근다.)

인덱스 `IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID`는 빠른 조회가 아니라 **올바른 동작**을 위한 것이다. 없으면 첫 가져가기가 훑은 행을 모두 잠가 둘째가 0행을 받는다. (2026-11-04, [issue 204](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/204): 작은 표에서는 인덱스가 있어도 그럴 수 있다. 이 쿼리에서 MySQL은 150행까지 표를 훑었고, 45행에서 filesort를 고르면 둘째가 0행을 받았다. 둘째의 가져가기가 일찍 끝날 뿐이다. [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md) 규칙 10에 적었다.)

잠금은 가져가는 트랜잭션과 함께 끝나므로 보내는 동안 행을 지키는 것은 리스다. 시도를 시작하면 `next_attempt_at`이 60 s 뒤로 밀려 차례가 아니다.

### 13.3 쓰기와 상태

| 쓰기 | 바꾸는 열 |
| --- | --- |
| 시도 시작 | `attempt_count + 1`, `next_attempt_at = 시작 + 60 s` |
| 발행 | `status = PUBLISHED`, `published_at` |
| 실패 | `last_error`, `next_attempt_at = 실패 + 간격` |
| 20번째 시도의 실패 | `last_error`, `status = BLOCKED` |

`attempt_count`와 `last_error`는 성공 뒤에도 남는다. `status`의 값이 하나 늘지만 varchar라 스키마는 그대로다(2절).

(2026-11-04, [issue 201](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/201): `last_error`와 WARN의 문구는 근본 원인의 타입과 메시지다. 다만 예외 사슬에 Jackson의 예외가 있으면 그 타입과 JSON 경로만 적는다. Jackson의 메시지와 그 원인의 메시지(`DateTimeParseException`)가 거절한 값을 그대로 인용해, 그대로 적으면 payload의 조각이 남기 때문이다. 원인을 따라 내려가기만 해도 이 값이 드러나는 것을 장면이 잡았다.)

| 메서드 | `PENDING` | `PUBLISHED` | `BLOCKED` | 까닭 |
| --- | --- | --- | --- | --- |
| `startPublishAttempt` | 실행 | 예외 | 예외 | 쿼리가 거른 값이므로 닿으면 코드의 잘못이다. `ensurePending`, `InvalidOrderEventOutboxException` |
| `markPublished` | 실행 | 그대로 | 실행 | broker에 record가 있다는 사실은 거절할 수 없다 |
| `markFailed` | 실행 | 그대로 | 그대로 | 리스를 넘긴 중계에서만 닿는 길이고, 던지면 회차가 끝난다 |

### 13.4 값

| 값 | 크기 | 까닭 |
| --- | --- | --- |
| `delivery.timeout.ms` | 21 s, `kafka.yml`에 더한다 | `kill -9` 재시작(약 16 s)을 넘긴다. Kafka가 `linger.ms + request.timeout.ms`(20,005 ms)보다 작은 값을 거절한다 |
| `max.block.ms` | 1 s, `kafka.yml`에 더한다 | 여기서 막힌 send는 받아들여지지 않았으므로 일찍 실패해도 중복이 없다. 건강한 첫 send는 16–22 ms |
| `retries`, `request.timeout.ms`, `linger.ms` | 그대로 | 재시작은 재시도 수를 쓰지 않는다. 빠른 오류는 중계의 재시도로 넘어온다 |
| 리스 | 60 s | 묶음이 행을 쥐는 최대 41 s(20 × 1 s + 21 s)보다 길다 |
| 간격 | 5 s에서 두 배씩, 최대 5분 | 판단이다. 멈춘 행의 WARN을 하루 약 288줄로 묶는다 |
| 한도 | 20번 | 판단이다. 전면 장애를 약 77분 스스로 넘긴다 |
| 묶음 | 20 | 약 40 ms에 끝난다. 회차가 다 비우므로 처리량을 막지 않는다 |
| 쉬는 간격 | 1 s | 지연의 요구가 없다. 새 행은 평균 약 0.5 s 기다린다 |

리스 뒤의 값은 코드의 상수이고 `@ConfigurationProperties`가 아니다. `TestClock`이 시간을 옮기므로 테스트가 바꿀 일이 없다. 이름은 `CLAIM_BATCH_SIZE`, `PUBLISH_ATTEMPT_LEASE`, `MAX_PUBLISH_ATTEMPTS`, `retryBackoffAfter`다.

(2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 묶음의 "약 40 ms"는 맨 SQL의 값이다. 실제 jar에서는 약 60 ms다. 13.11절이다.)

(2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토: `max.block.ms`의 1 s는 건강한 첫 send로 정한 값이다. broker가 재시작하는 동안에는 실패를 낼 수 있고, 그것을 받아들였다. 13.10절이다.)

### 13.5 어디에 무엇이 있는가

| 조각 | 자리 | 하는 일 |
| --- | --- | --- |
| `OrderEventOutbox` | `domain.order` | 상태의 변화, 재시도 규칙, 상수 |
| `OrderEventRelayer` | `application.order.provided` | 트리거가 부르는 `publishDueEvents()` |
| `OrderEventRelay` | `application.order` | `NEVER`. 회차의 차례만 잇는다 |
| `OrderEventOutboxModifyService` | `application.order` | `claimDueOutboxes`, `markPublished`, `markFailed`. 중계만 부른다 |
| `OrderEventOutboxRepository` | `application.order.required` | `findDueForUpdate`와 잠그고 읽는 메서드를 더한다 |
| `OrderEventPublisher`, `OrderEventPublishResult` | `application.order.required` | `publish(outboxes)`가 행마다 결과를 돌려준다 |
| `KafkaOrderEventPublisher` | `adapter.integration.kafka` | 행으로 메시지를 다시 만들어 공용 템플릿으로 보낸다. 발행의 INFO와 WARN |
| `OrderEventRelayScheduler` | `adapter.scheduling`, 새 층 | `fixedDelay`로 `OrderEventRelayer`만 부른다 |

중계는 저장의 예외를 잡지 않는다. DB가 실패하면 예외가 회차 밖으로 나가고, 남기지 못한 행은 리스가 끝난 뒤 다시 발행된다. 과제의 "broker 성공 뒤 상태 TX 실패"가 이 길이다.

(2026-11-04, [issue 202](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/202): 행이 `BLOCKED`로 넘어갈 때의 WARN은 `OrderEventOutboxModifyService.markFailed`에 있다. 실패의 앞뒤로 행의 상태를 보는 곳이 거기뿐이다. 어댑터의 WARN은 20번 모두 그대로 나오고, 이 줄은 넘어갈 때 한 번 나온다.)

### 13.6 켜기

`order-events.relay.enabled`는 기본 꺼짐, `local`에서 켜짐, `test`에서 꺼짐이다. 한 인스턴스에서만 켠다. 이것은 설정이지 소유권이 아니다. 둘째 인스턴스가 켜져도 리스가 살아 있는 동안 같은 행을 두 번 보내지 않지만 지원하는 구성이 아니다.

(2026-11-04, [issue 205](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/205): 앱을 멈출 때의 회차를 compose의 broker로 쟀다. SIGTERM은 진행 중인 회차가 끝나기를 기다린다. broker가 멈춘 채면 행은 `PENDING`, 시도 1, 리스 그대로 남고 39.9 s 뒤에 끝난다(만료 21 s, producer를 닫는 데 20 s). 다음 실행은 리스 뒤에 다시 발행하고, 그 record는 먼저 소켓에 있던 것과 함께 offset 0과 1에 둘 있었다. 종료 중에 broker가 돌아오면 행은 `PUBLISHED`, 시도 1, record 1개다. 이것은 실패가 아직 회차 밖으로 나가는 [issue 201](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/201) 전의 측정이다. 201 뒤에는 broker가 멈춘 경우의 행이 실패의 간격을 가질 것으로 보이고, 다시 재 봐야 한다. `kill -9`는 재지 않았고 리스 뒤의 재발행은 설계의 예상이다. [글감](../research/week7-outbox-relay-writing-material.md)에 수와 조건이 있다.) (2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 201 뒤에 다시 쟀다. 행은 예상대로 실패의 간격과 `last_error`를 갖는다. `kill -9`와 적체 중의 멈춤도 쟀다. 13.11절이다.)

### 13.7 적체와 재개의 SQL

```sql
-- 상태마다의 행 수, 시도 수, 가장 오래된 행
SELECT status, COUNT(*) AS row_count, SUM(attempt_count) AS attempts, MIN(created_at) AS oldest_created_at
FROM order_event_outbox
WHERE deleted_at IS NULL
GROUP BY status;

-- 미해결 행
SELECT event_id, order_id, attempt_count, last_error, updated_at
FROM order_event_outbox
WHERE status = 'BLOCKED' AND deleted_at IS NULL
ORDER BY id;

-- 한 행의 재개
UPDATE order_event_outbox
SET status = 'PENDING', next_attempt_at = UTC_TIMESTAMP(6)
WHERE event_id = ? AND status = 'BLOCKED';
```

(2026-11-04, [issue 202](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/202): 재개의 시각이 `NOW(6)`에서 `UTC_TIMESTAMP(6)`로 바뀌었다. 열은 UTC를 담는데(`hibernate.jdbc.time_zone`) `NOW(6)`은 세션의 시간대를 따른다. `+09:00` 세션에서 돌린 장면에서 행의 다음 시도가 539분 뒤로 저장됐다. 위의 문장들은 `OrderEventRelayFailedSendTest`가 이 문서에서 그대로 읽어 돌리므로, 문장을 고치면 장면이 함께 확인한다. 주석 줄은 장면이 문장을 찾는 이름이다. 장면의 값은 [글감](../research/week7-outbox-relay-writing-material.md)에 있다.)

- `oldest_created_at`은 기다림이 시작된 UTC 시각이다. 기다린 시간은 `NOW()`가 아니라 `UTC_TIMESTAMP(6)`와 견주어 잰다.
- 재개는 `attempt_count`를 두므로 다시 실패하면 곧바로 `BLOCKED`로 돌아간다.
- 전면 장애가 약 77분을 넘겨 `BLOCKED`가 됐다면 행의 잘못이 아니다. broker가 돌아온 뒤 재개한다.
- 과제의 "상태를 직접 `PENDING`으로 덮어쓰지 말라"는 중단 장면의 복구 조건을 두고 하는 말이다. 재개의 장면은 운영의 절차를 그대로 돌리는 것이다.

### 13.8 `PUBLISHED`, 소비, 보존

`PUBLISHED`는 broker가 확인했다는 뜻이다. 소비 완료가 아니다. 소비는 consumer lag, 오류, 구매 활동으로 따로 본다.

행은 지우지 않는다. 나중에 정리한다면 `PUBLISHED`만 대상이고, 재발행될 수 있는 동안의 행을 지우면 안 된다. 받는 쪽의 멱등 기록은 같은 이벤트가 다시 올 수 있는 동안 남아 있어야 하므로, 그 보존 기간은 재발행의 창보다 길어야 한다. 미발행과 `BLOCKED` 행은 정리의 대상이 아니다.

(2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 셋을 이렇게 가른다. 구현과 맞는다. 받는 쪽의 멱등 기록은 streamer의 처리한 주문 확정이고 [보관 기한이 없으므로](../../apps/commerce-streamer/CONTEXT.md) 위의 조건은 지금 지켜진다. 중계는 `PUBLISHED` 행을 가져가지 않으므로 다시 발행하는 창은 행이 `PENDING`이나 `BLOCKED`인 동안과 리스를 넘긴 중계의 send가 끝날 때까지이고, `BLOCKED`는 사람이 돌릴 때까지 끝이 없다. 다시 전달은 행의 상태와 상관없이 토픽에 record가 있는 동안 생긴다. 그 기록에 기한을 두게 되면 가장 오래된 미해결 행과 토픽의 보관 기간 모두보다 길어야 한다. 3단계의 복구가 없는 행을 저장된 사실로 더하므로, `PUBLISHED` 행을 지우는 정리는 3단계 뒤에 그 복구와 함께 다시 본다. 이 관계는 코드에서 읽은 것이고 장면으로 돌리지 않았다.) (2026-11-06, [issue 217](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/217): 복구와 함께 다시 봤고 정리는 만들지 않는다. 누락 복구는 `event_id`의 행이 없으면 더하므로, 지운 `PUBLISHED` 행의 주문을 누군가 지정하면 같은 이벤트 ID의 행이 다시 생기고 중계가 다시 발행한다. 그 record는 streamer가 처리한 주문 확정으로 `ALREADY_RECORDED`가 되고 구매 활동은 늘지 않는다. 그 장면은 `OrderEventRecoveryE2ETest`의 B다. 다만 B는 행 없이 발행된 경우이고 지운 행을 다시 더한 경우를 돌린 장면은 없다. 그래서 정리를 만든다면 받는 쪽의 기록이 그 주문을 지정할 수 있는 동안, 곧 끝없이 남아야 한다는 조건이 하나 는다. 지금은 둘 다 지우지 않으므로 지켜진다. 이것도 코드에서 읽은 것이다.)

| 말 | 뜻 | 그때 broker에는 | 어디서 보는가 |
| --- | --- | --- | --- |
| `PENDING` | 확인을 저장하지 못했다. 차례가 되면 다시 발행한다 | 없거나, 이미 있다(확인 뒤 저장 전의 멈춤, 소켓에 있던 시간 초과) | 13.7절의 SQL |
| `PUBLISHED` | broker가 확인했고 저장했다 | 있다. 둘 이상일 수 있다 | 13.7절의 SQL, 발행의 INFO |
| 구매 활동으로 기록됨 | streamer가 기록했고 읽었다고 표시했다. 행의 상태로는 알 수 없다 | 있다 | consumer lag, 구매 활동, 처리한 주문 확정 |

### 13.9 테스트

- **provided 포트(`BaseCommittingApplicationServiceTest`, 실제 MySQL과 Kafka).** 13.1절의 장면, 행마다의 결과, `BLOCKED`까지의 걸음, 재개, 다 비우기, 트랜잭션 안의 호출 거절, 13.7절의 SQL. send 호출 수가 아니라 broker의 record를 센다.
- **저장소(`BaseRepositoryTest`).** 열린 가져가기 둘이 겹치지 않음, 내보낸 SQL의 `for update skip locked`, 차례가 아닌 행들, 상태 변화 뒤에도 그대로인 사건의 열. 앞의 것이 인덱스를 지킨다.
- **도메인.** 13.3절의 표의 모든 칸, 간격의 수열, 20번째의 `BLOCKED`.
- **어댑터.** record의 토픽, key, 값을 문자열 전체로 못 박고 header가 없음을 본다. 7절의 봉투의 빈틈이 여기서 닫힌다. (2026-11-04, [issue 200](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/200): 이 장면은 어댑터만의 클래스가 아니라 `OrderEventRelayerTest`에 있다. 실제 확정이 남긴 행에서 시작하므로 컨텍스트가 하나 덜 뜬다. 7절의 빈틈은 여기서 닫혔다.)
- **멈춤.** `stopRelayAfterBrokerAckBeforeMarkingPublished`는 `OrderEventOutboxModifyService`의 spy에 거는 일회성 stub이다. 첫 `markPublished`만 던진다. JVM은 살아 있고 프로세스 강제 종료가 아니다. 재시작은 같은 DB와 토픽의 새 `OrderEventRelay`이고 시간은 `TestClock`이 옮긴다. 테스트가 `status`를 쓰지 않는다. (2026-11-04, [issue 203](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/203): 13.1절의 세 장면은 `OrderEventRelayerTest`에 있고, 실행 값을 장면마다 INFO 한 줄로 남긴다(`scripts/scene-lines`). `PUBLISHED`를 send보다 먼저 저장하도록 고치면 멈춤 장면이 빨개지고, 그 사이에서 멈춘 이벤트는 사라진다. 실행 값은 [글감](../research/week7-outbox-relay-writing-material.md)에 있다.)

### 13.10 받아들인 한계

- 단일 중계는 설정이다. `application.yml`의 기본 프로필이 `local`이므로 프로필을 잊은 배포는 중계를 켠다.
- 발행은 최소 한 번이다. broker 확인 뒤 저장 전에 멈추거나 리스를 넘기면 같은 이벤트가 다른 offset에 다시 있다.
- 단일 broker에서 `acks=all`은 복제를 증명하지 않는다(6주차의 한계 그대로다).
- 약 77분을 넘는 장애의 행은 사람의 SQL을 기다린다.
- 수치는 노트북의 Docker에서 거의 빈 로그로 쟀다. Hibernate의 `SKIP LOCKED`는 이 절을 쓰는 때에 확인하지 못했다. (2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 확인했고(13.2절) 실제 jar의 처리량도 쟀다(13.11절).)
- (2026-11-04, [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206): 앱을 멈춰도 회차는 스스로 서지 않는다. 적체 중의 SIGTERM은 30 s 동안 계속 발행하고 끊기며, 확인을 저장하지 못한 행은 리스 뒤에 다시 발행된다. 13.11절이다.)
- (2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토: 명세의 이야기 31, 짧은 재시작에 실패한 시도가 없다는 약속은 지금 값에서 지켜지지 않는다. 이미 받아들여진 record는 재시작을 넘긴다. 그러나 하나뿐인 broker가 내려가면 Kafka 클라이언트가 rebootstrap하며 토픽의 메타데이터를 버리고, 그사이에 온 `send()`는 `max.block.ms`인 1 s 뒤에 실패한다. 실제 jar로 재 보니 graceful 18번 중 5번(14행), `kill -9` 6번 중 2번(28행)에서 실패했다. 그 행들은 둘째 시도로 발행됐고 잃거나 겹친 record는 없다. 실패한 행은 WARN과 아래의 ERROR, 5 s의 간격, 한도 20번 중 한 번을 쓴다. `metadata.recovery.strategy: none`이면 15번 모두 실패가 없었지만 넣지 않는다. broker가 하나인 구성에만 필요한 설정이기 때문이다. broker가 여럿이면 하나의 재시작으로 아는 broker가 모두 사라지지 않으므로 닿지 않는다고 Kafka의 문서에서 읽었고, 재지는 않았다. 조건과 원시 값은 [측정 기록](../research/week7-outbox-relay-probe.md#a-broker-restart-at-the-shipped-limits)에 있다.)
- (2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토: 재시작 측정에서 send가 실패할 때마다 Spring Kafka의 `LoggingProducerListener`도 ERROR를 한 줄 남겼다. 어댑터의 WARN과 같은 실패이고 둘 다 39번 나왔다. 이 줄에는 key와 메시지의 앞 100자가 들어가지만 본문은 그 뒤에 있어 나오지 않는다. 로그 수준에 경보를 건다면 이 ERROR는 다시 시도되는 실패로 읽어야 한다.)
- (2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토: `markFailed`는 상태만 본다. 리스를 넘긴 중계의 실패가 다른 중계가 다시 가져간 `PENDING` 행에 닿으면 `last_error`를 덮어쓰고 `next_attempt_at`을 앞당겨 그 중계의 리스를 줄인다. 20번째 시도라면 `BLOCKED`로 만들지만, 그 중계가 성공하면 `markPublished`가 받는다. 최악은 같은 이벤트의 재발행이고 받는 쪽의 멱등성이 받는다. 중계 둘이 함께 돌 때만 닿으므로 고치지 않는다. 코드에서 읽은 것이고 장면으로 돌리지 않았다.)
- (2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토: `BLOCKED`로 넘어갈 때의 WARN은 커밋 전에 나온다. 그 커밋이 실패하면 로그는 `BLOCKED`라고 하는데 행은 `PENDING`이고, 리스 뒤에 다시 시도된다. 그때도 실패하면 WARN이 한 번 더 나온다. DB가 그 순간에 실패할 때만 닿으므로 그대로 둔다.)

### 13.11 구현이 재 본 것

2026-11-04 [issue 206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206). 명세와 앞의 티켓들이 구현으로 넘긴 측정이다. 조건과 원시 값, 도구의 소스는 [측정 기록](../research/week7-outbox-relay-probe.md#through-the-real-jar)에 있다. `dc6b59e`의 jar를 `local` 프로필로 compose의 `bitnamilegacy/kafka:3.5`, `mysql:8.4`에 붙여 노트북의 Docker에서 조건마다 한 번 돌렸다. 행은 `INSERT`로 넣었고 확정을 거치지 않았다. 멈춤은 실제 프로세스에 보낸 신호다.

**처리량.** 약 336 rows/s다. 맨 SQL의 480 rows/s보다 약 30% 낮고, 묶음 하나가 약 60 ms다. 이 차이가 Hibernate만의 값은 아니다. 행마다의 INFO 로그와 프레임워크가 함께 들어 있고 따로 재지 않았다.

| 실행 | 행 | rows/s |
| --- | --- | --- |
| 맨 SQL, 한 중계, `linger.ms` 5 | 3,000 | 480 |
| jar, 막 뜬 JVM, `show-sql` 끔 | 3,000 | 271 |
| jar, 막 뜬 JVM, `local` 그대로(`show-sql` 켬) | 3,000 | 293 |
| jar, 긴 적체, `show-sql` 끔 | 48,948 (145.8 s) | 336 |

`show-sql`을 켠 쪽이 빠른 것은 한 번씩 돌린 값의 차이이고 뜻이 없다. 묶음의 크기나 저장 방식을 바꿀 까닭은 아니다. [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md)의 "지속해서 수백 rows/s"는 그대로 맞다.

**앱을 멈출 때.**

| 경우 | 끝날 때까지 | 끝난 때의 행 | 다시 띄운 뒤 |
| --- | --- | --- | --- |
| 가져간 뒤 broker가 멈춘 채 SIGTERM | 40.1 s | `PENDING`, 시도 1, `last_error`에 `TimeoutException`, 다음 시도는 실패 + 5 s | 곧바로 `PUBLISHED`, 시도 2. 행 2개에 record 3개 |
| 같은 경우에 3 s 뒤 broker가 돌아옴 | 3.1 s | `PUBLISHED`, 시도 1 | 그대로. 행 2개에 record 2개 |
| 적체 60,000행을 비우는 중에 SIGTERM | 30.1 s | 1,034행에서 11,052행까지 `PUBLISHED`. 8행이 `PENDING`, 시도 1. record 11,060개 | 리스 뒤에 8행이 시도 2로 `PUBLISHED`. 60,000행에 record 60,008개 |
| 적체 3,000행을 비우는 중에 SIGKILL | 즉시 | 20행이 `PENDING`, 시도 1. record 1,020개로 `PUBLISHED` 수와 같음 | 약 52 s 뒤 모두 `PUBLISHED`. 20행이 시도 2. 3,000행에 record 3,000개 |

- **첫 줄은 13.6절의 예상대로다.** 실패가 저장되므로 행은 리스가 아니라 실패의 간격을 갖고, 다시 띄운 앱은 리스를 기다리지 않았다. 40 s는 만료까지 약 20 s와 producer를 닫는 20 s다. 클라이언트가 포기한 record가 소켓에 남아 있다 풀려 record가 하나 더 있는 것도 전과 같다.
- **회차의 되풀이는 종료를 보지 않는다.** SIGTERM 뒤에도 Boot의 종료 단계 한도인 30 s 동안 계속 가져가 10,018행을 더 발행했다. 한도가 지나면 로그에 `Shutdown phase … ends with 1 bean still running after timeout of 30000ms: [taskScheduler]`가 남고, producer와 JPA가 닫히며, 그다음 저장이 던진 예외가 회차를 끝낸다. 그 사이의 8행이 확인을 받고도 저장되지 못해 다시 발행됐다. 끊기는 자리가 결과 저장이므로 코드로 보면 한 번에 묶음 하나, 20행까지다. 측정한 것은 8행이다.
- **`kill -9` 뒤의 재발행을 프로세스 수준에서 봤다.** 가져간 20행은 리스 뒤에 돌아왔다. 이 실행에서는 묶음이 broker에 닿기 전에 멈춰 record가 늘지 않았다. 다른 때에 멈추면 첫 줄이나 셋째 줄처럼 늘 수 있다.
- **바꾸지 않는다.** 어느 경우든 다시 발행보다 나쁜 일은 없었고, 받는 쪽의 멱등성이 받는다. 종료를 보는 검사를 넣으면 30 s와 다시 발행되는 몇 행이 줄지만 지금 그 값이 필요하지 않다.
- **재지 않았다.** 적체가 20행을 넘는 채 broker가 멈춘 때의 멈춤이다. 코드로 보면 묶음마다 21 s라 둘째 묶음에서 30 s 한도에 끊긴다. 한도보다 긴 유예를 주지 않는 감독자는 `kill -9`의 길로 간다.

**`TopicReader`의 1 s.** 7절이 넘긴 값이다. 그대로 둔다. 중계의 폴링은 이 값과 상관이 없다. 장면은 회차를 손으로 돌리고, 회차는 send를 모두 기다린 뒤 돌아온다. 스케줄러를 켠 장면 하나는 행이 `PUBLISHED`인 것을 본 뒤에 읽으므로 다음 tick이 그 행을 발행할 수 없다. 그래서 이 값은 이미 있는 record를 읽는 시간에 대한 여유다. 중계의 세 클래스에서 재 보니 22번 읽었고, 기대한 record를 읽는 데 중앙값 2 ms, 최대 20 ms가 걸렸고, 조용히 기다리는 동안 더 온 record는 0개였으며, 합쳐서 22 s를 썼다. 줄이면 빨라지지만, 너무 짧을 때의 실패는 record가 없다고 기대한 장면이 바쁜 기계에서 조용히 통과하는 것이다. 22 s보다 그쪽이 비싸다. broker의 끝 offset까지만 읽는 방식은 대기를 없애지만, 버그로 기다리지 않은 send를 놓친다.

### 13.12 2단계가 만든 것

2026-11-04 [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 티켓이다. 명세가 이름 지은 것(`OrderEventOutbox`, `OrderEventOutboxStatus.BLOCKED`, `OrderEventRelayer`, `publishDueEvents`, `OrderEventRelay`, `OrderEventOutboxModifyService`, `claimDueOutboxes`, `markPublished`, `markFailed`, `OrderEventPublisher`, `OrderEventPublishResult`, `KafkaOrderEventPublisher`, `OrderEventRelayScheduler`, `adapter.scheduling`, `findDueForUpdate`, `CLAIM_BATCH_SIZE`, `PUBLISH_ATTEMPT_LEASE`, `MAX_PUBLISH_ATTEMPTS`, `retryBackoffAfter`, `IDX_ORDER_EVENT_OUTBOX_ON_STATUS_AND_NEXT_ATTEMPT_AT_AND_ID`, `order-events.relay.enabled`, `stopRelayAfterBrokerAckBeforeMarkingPublished`)은 모두 그대로 최종 이름이 됐다. 명세가 이름 짓지 않은 것은 이렇게 정해졌다. 잠그고 읽는 메서드는 `findForUpdateById`, 테스트 클래스는 표의 이름이다.

| 티켓 | 만든 것 |
| --- | --- |
| [199](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/199) | [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md), 13절, [측정 기록](../research/week7-outbox-relay-probe.md), 글감의 머리말, `CONTEXT.md`의 막힌 이벤트. |
| [200](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/200) | 포트와 중계, 수정 서비스, 어댑터, 인덱스, 한 묶음의 회차. `OrderEventRelayerTest`, `OrderEventOutboxStateTest`. 저장소와 불변 테스트에 장면을 더했다. record 전체를 문자열로 못 박아 7절의 빈틈을 닫았다. |
| [205](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/205) | `OrderEventRelayScheduler`, `adapter.scheduling`과 그 아키텍처 규칙, `kafka.yml`의 두 줄. `OrderEventRelaySchedulerTest`, `OrderEventRelaySchedulerDisabledTest`, `OrderEventRelaySchedulerTicksTest`, `OrderEventRelayEnabledPropertyTest`. |
| [201](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/201) | 재구성, `send()`, future의 실패를 그 행의 결과로 옮겼다. 간격과 `last_error`, payload가 없는 실패의 문구. `OrderEventRelayFailedSendTest`. 기준이 요구해 `BLOCKED`와 20번째의 넘어감도 여기서 들어왔다. |
| [203](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/203) | 13.1절의 세 멈춤 장면. 운영 코드는 바뀌지 않았다. |
| [204](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/204) | 짧은 묶음까지 되풀이하는 회차. `OrderEventOutboxClaimTest`의 열린 가져가기 둘, `OrderEventRelayerTest`의 두 중계와 늦은 실패, 저장소 테스트의 차례의 장면. |
| [202](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/202) | `BLOCKED`로 넘어갈 때의 WARN, 13.7절의 SQL을 문서에서 읽어 돌리는 장면, 그것을 위한 Gradle의 입력 선언. |
| [206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206) | 13.11절의 측정, 이 표와 13.13절, `CONTEXT.md`의 중계와 발행하다, 글감의 머리말과 PR 본문 초안, 명세의 닫는 글. |

명세와 티켓에서 달라진 것은 일곱이다. 여섯은 각 절의 날짜 메모에, 일곱째는 `OrderEventRelay`의 KDoc에 있다. 잠금의 문장은 `for update of <별칭> skip locked`다(13.2절). 인덱스는 필요하지만 충분하지 않다(13.2절). 실패의 문구는 Jackson이 사슬에 있으면 타입과 JSON 경로다(13.3절). `BLOCKED`의 WARN은 어댑터가 아니라 수정 서비스에 있다(13.5절). 재개의 시각은 `UTC_TIMESTAMP(6)`다(13.7절). record를 못 박는 장면은 어댑터만의 클래스가 아니다(13.9절). 큰 적체가 있는 장애에서는 회차가 1 s를 쉬지 못한다. 명세가 맨 SQL로 재고 넘긴 처리량은 13.11절이 답했다.

(2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토가 바꾼 것: 동작은 바뀌지 않았다. `claimDueOutboxes`가 `saveAll`로 저장하고 포트에 그 메서드가 생겼다. `markFailed`가 `save`가 돌려준 값을 읽는다. `retryBackoffAfter`의 `coerceAtLeast(0)`과 그것만 못 박던 장면을 지웠다. 시도가 0인 행은 운영의 길로 `markFailed`에 닿지 않고, 닿는다면 `ensurePending`처럼 던진다. `BaseCommittingApplicationServiceTest`의 도우미는 `prepareOrderEventOutboxWithoutOrder`가 됐고 `orderEventOutbox` 필드를 채운다. `OrderEventRelaySchedulerTest`는 기반의 `committedOrderEventOutbox`를 쓴다. `findDueForUpdate`가 `Slice`가 아니라 `List`인 까닭은 KDoc과 [측정 기록](../research/week7-outbox-relay-probe.md#list-and-slice-on-the-claim)에 있다. 달라진 것에 이야기 31과 이야기 16이 더해진다(13.10절).)

3단계로 넘긴다. 구매 활동까지의 경로, 앱을 건너는 실행, 6주차 사례의 복구다. 이 단계의 장면은 broker까지만 본다(13.1절).

### 13.13 2단계의 명령

```bash
# 회차, record, 로그, 재구성의 실패, 멈춤, 다 비우기, 두 중계: OrderEventRelayerTest(18). 멈춤의 실행 값은 scripts/scene-lines
./gradlew :apps:commerce-api:test --tests '*OrderEventRelayerTest'

# 실패한 send, BLOCKED, 재개와 적체의 SQL: OrderEventRelayFailedSendTest(12)
./gradlew :apps:commerce-api:test --tests '*OrderEventRelayFailedSendTest'

# DB가 지키는 것: OrderEventOutboxClaimTest(1), OrderEventOutboxRepositoryTest(8), OrderEventOutboxImmutabilityTest(2)
./gradlew :apps:commerce-api:test --tests '*OrderEventOutboxClaimTest' --tests '*OrderEventOutboxRepositoryTest' --tests '*OrderEventOutboxImmutabilityTest'

# 상태 표, 간격, 한도: OrderEventOutboxStateTest(17)
./gradlew :apps:commerce-api:test --tests '*OrderEventOutboxStateTest'

# 트리거: OrderEventRelaySchedulerTest(1), OrderEventRelaySchedulerDisabledTest(2), OrderEventRelaySchedulerTicksTest(2), OrderEventRelayEnabledPropertyTest(1)
./gradlew :apps:commerce-api:test --tests '*OrderEventRelayScheduler*' --tests '*OrderEventRelayEnabledPropertyTest'

# producer의 설정: OrderEventsProducerTest(4)
./gradlew :apps:commerce-api:test --tests '*OrderEventsProducerTest'

# 끝의 확인. 트리 전체의 ktlint, 두 앱의 cleanTest와 check, 수까지 돌리고 commit과 시각을 함께 적는다.
scripts/final-check
```

- 끝의 확인은 2026-11-04 `8ee98a2`의 깨끗한 트리에서 `scripts/final-check`으로 돌렸다(06:05:08Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(50 s). commerce-api는 90개 클래스, 1032개 테스트, 실패 0, 오류 0, 건너뜀 0이다(3 min 45 s). `8ee98a2` 뒤로는 문서만 바뀌었다. 위 블록의 거른 명령도 같은 날 그 트리에서 하나씩 돌려 괄호의 수를 읽었다.
- (2026-11-05, [명세 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)의 닫는 검토: 검토의 수정 뒤 끝의 확인을 `7ec8227`의 깨끗한 트리에서 `scripts/final-check`으로 다시 돌렸다(05:49:08Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(48 s). commerce-api는 90개 클래스, 1031개 테스트, 실패 0, 오류 0, 건너뜀 0이다(3 min 45 s). 위의 1032개에서 `coerceAtLeast(0)`만 못 박던 장면이 빠져 `OrderEventOutboxStateTest`는 16개다. 다른 클래스의 수는 위 블록의 괄호 그대로이고, 거른 명령을 다시 돌리지 않고 이 실행의 보고서에서 읽었다. `7ec8227` 뒤로는 문서만 바뀌었다.)
- 2단계의 수는 1단계의 끝(82개 클래스, 969개 테스트)에서 이렇게 왔다. 새 클래스 8개가 54개다(`OrderEventRelayerTest` 18, `OrderEventOutboxStateTest` 17, `OrderEventRelayFailedSendTest` 12, 트리거의 넷 6, `OrderEventOutboxClaimTest` 1). 있던 클래스에 9개가 늘었다(`OrderEventOutboxRepositoryTest` 4 → 8, `OrderEventsProducerTest` 1 → 4, `OrderEventOutboxImmutabilityTest` 1 → 2, `HexagonalArchitectureTest` 7 → 8). streamer의 수는 그대로다.
- `--tests`로 거른 실행은 여전히 `HexagonalArchitectureTest`도 함께 돌고, 이제 그 수는 8개다. 12절의 메모 그대로 위의 수는 그것을 뺀 것이다.
- 13.7절의 문장은 `OrderEventRelayFailedSendTest`가 이 문서에서 읽는다. 그래서 이 문서를 고치면 commerce-api의 `test`가 다시 돈다.

## 14. 누락 복구와 앱을 건너는 실행

2026-11-06 3단계를 인터뷰해 정했다. 누락 복구의 까닭과 고르지 않은 것은 [ADR 0036](../adr/0036-missing-outbox-rows-are-recovered-for-named-orders-from-stored-facts.md)에 있다. 이 절은 구현 전에 썼고, 구현이 바꾼 것은 날짜 메모로 단다.

### 14.1 범위

과제의 5절(6주차 누락 상태의 재현과 복구), 7절(주문부터 구매 활동까지 한 번), 8절(회귀와 제출), 그리고 13.1절이 넘긴 구매 활동의 칸이다.

| 과제 | 이 단계의 테스트 |
| --- | --- |
| 5절: W6 누락·소비 완료 재현, 같은 실행 안의 복구, 두 번째 복구 | `OrderEventRecoveryE2ETest` |
| 7절: 7×2와 8×1인 주문, broker 확인 뒤 `PUBLISHED` 저장 전 멈춤, 재발행 | `OutboxDeliveryE2ETest` |
| 6절의 셋째와 넷째 칸: commit 뒤 중계 전 멈춤, 중계 재생성 뒤 구매 활동 | `OutboxDeliveryE2ETest` |

### 14.2 누락 복구

- 애플리케이션 서비스가 지정한 주문마다 자기 트랜잭션에서 주문을 읽고, `OrderConfirmedEvent.from(order)`와 `OrderEventSerializer`로 행을 만든다. TX B의 행 만들기(`leaveInOutbox`)를 뽑아 둘이 나눠 쓴다(10절의 예고).
- 진입점은 한 클래스의 `ApplicationRunner`이고 `order-events.recovery.order-ids`가 비면(기본) 아무것도 하지 않는다. 엔드포인트가 없다. 앱을 그 값으로 다시 띄운다.
- 결과는 ADR 0036 규칙 3의 표다. 거절은 시작을 실패시킨다. 첫 거절에서 멈추고, 앞서 더한 행은 남는다. 다시 돌려도 같은 행이면 아무것도 하지 않는다. 주문마다 INFO 한 줄이다.
- 잠그지 않는다. 동시의 두 실행은 유일한 `event_id`에서 하나가 실패한다.
- (2026-11-06, [issue 212](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/212): 거절은 봉인된 `OrderEventRecoveryRefusedException`의 넷이다. 없는 주문은 `OrderNotFound`이고 `ORDER_NOT_FOUND`가 아니다(`findForRecovery`가 던진다). `confirmedAt`이 없으면 `OrderNotConfirmed`, PG가 아니면 `OrderNotPaidThroughPg`, 행이 다르면 `OutboxRowDiffers`다. 마지막은 이벤트 ID와 다른 열 이름을 모두 표의 차례로 싣고 payload는 싣지 않는다. 있는 행은 그대로 `event_id`로 찾는다. 찾기와 유일 키가 열의 collation(`utf8mb4_general_ci`)을 나눠 쓰므로, 새 행이 부딪힐 행이 견줄 행이다. 그래서 대소문자만 다른 `event_id`도 찾혀 `event_id`가 다르다고 거절된다. 견주기는 Kotlin의 문자열 그대로다. 거절은 그 주문의 트랜잭션이 되돌아간 뒤 `OrderEventRecovery`가 WARN 한 줄(예외의 메시지와 `orderId=`)로 남기고 다시 던진다. 동시의 두 실행에서 진 쪽은 `DataIntegrityViolationException`으로 실패하거나, 먼저 커밋된 행을 같은 행으로 찾는다.)
- (2026-11-06, [issue 217](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/217): [issue 211](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/211)이 정한 이름은 14.7절에 있다. 진입점은 [issue 213](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/213)이 새 `adapter.startup` 층에 둔 `OrderEventRecoveryRunner`다. 그 층은 다른 어댑터가 부르지 못하고, 스케줄링처럼 제공 포트만 부른다는 규칙을 진다. 값은 `Binder`로 읽어 쉼표로 이은 값과 YAML 목록을 모두 받는다. 티켓은 거절을 원인으로 실은 시작의 실패를 요구했지만, 거절은 싸이지 않고 그 자체로 나온다. Boot 4.1은 러너의 checked 예외만 싸고 거절은 unchecked라, 컨텍스트가 닫힌 뒤 `SpringApplication.run`에서 그대로 던져진다. 장면은 그것을 단언한다.)

### 14.3 5절의 준비: 보존한 W6 테스트 구성

6주차와 지금의 확정 경로는 TX B의 한 곳만 다르다(`publish` 대신 `leaveInOutbox`). 그래서 W6 revision을 띄우지 않고 그 한 곳만 테스트에서 6주차처럼 바꾼다.

1. 첫 컨텍스트는 `OrderEventOutboxRepository`의 spy를 둔다. `save`는 행을 쓰지 않고 커밋 뒤의 훅을 건다. A의 훅은 보내지 않는다(보내기 직전의 멈춤, 프로세스 강제 종료가 아니다). B의 훅은 받은 행을 지금의 `KafkaOrderEventPublisher`로 보낸다. record의 bytes는 6주차와 같다고 못 박혀 있다(10절).
2. 주문은 제공 포트로 만들고 확정한다. PG는 테스트 PG다. A와 B 모두 `CONFIRMED`·예약 소비·행 0이고, B만 구매 활동이 있다. 두 주문의 `orderId`, 이벤트 ID, `confirmedAt`, 품목, PG 거래 식별자와 PG 생성 누적 C, 외부 거래 수 T를 남긴다(C=2, T=2).
3. 첫 컨텍스트를 닫고 spy 없는 둘째 컨텍스트를 같은 컨테이너에서 띄운다. `ddl-auto=none`, 중계 켜짐, `order-events.recovery.order-ids`는 A와 B다(`OrderPaymentIntegrationTest.restarted()`의 선례).
4. A에는 빠졌던 구매 활동이 생기고 B의 구매 활동은 그대로다. PG 생성은 C, PG 조회와 callback은 0번 더, 외부 거래는 T, 주문의 재확정과 예약의 추가 소비는 0이다.
5. 같은 ID로 셋째 컨텍스트를 띄운다. 두 주문 모두 같은 행이라 아무것도 하지 않고, 행·구매 활동·PG 호출이 늘지 않는다.

모두 한 `@Test` 안이라 사이에 정리가 돌지 않는다. 컨테이너는 이 JVM의 Testcontainers이고, 이것을 과제의 "새 격리 실행"으로 본다. 행을 지우거나 `SUCCESS`를 넣거나 상태를 덮어쓰지 않는다. 6주차의 원본 DB는 없으므로 이것은 재현이고 원본 사건의 복구라 부르지 않는다.

(2026-11-06, [issue 216](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/216): 첫 컨텍스트는 닫지 않는다. JUnit의 테스트 컨텍스트라 장면 안에서 닫을 수 없고, test 프로필이라 중계가 꺼져 있어 발행하지 않는다. 그 컨텍스트의 다른 빈이 움직였다면 PG 조회 수나 주문·결제의 `updated_at`이 달라졌을 것이다. 둘째와 셋째는 `com.loopers.support`의 `restartedCommerceApi`로 띄우고, 셋째도 중계를 켠 채 2 s 동안 행이 그대로인지 본다. T는 테스트 PG가 202로 답한 생성의 거래 키이고 두 결제에 저장된 거래 키와 같다. C는 결제마다 1이다. callback은 세지 못한다. 테스트 PG는 callback을 보내지 않으므로, PG 조회 0번 더와 결제의 상태·거래 키·`updated_at`이 그대로인 것으로 본다.)

받아들인 것: 도는 6주차 코드는 실제 판이 아니라 지금의 TX B와 테스트의 훅이다. 표가 처음부터 있으므로 W6 DB에 아웃박스 표를 더하는 단계는 테스트되지 않는다. 2026-11-05 W6의 실제 jar로 한 번 쟀다([측정 기록](../research/week7-outbox-recovery-probe.md)). `ddl-auto=update`는 표와 키, 검사, 인덱스를 더하고 W6 표의 정의를 바꾸지 않았지만, W6 표의 열 넷에 정의가 그대로인 `modify column`을 냈다. 닿지 않는 broker 주소로 띄운 W6 jar는 확정을 커밋하고 send가 막힌 채 `kill -9`되어 record를 남기지 않았다.

### 14.4 실제 consumer

- commerce-streamer는 HEAD의 bootJar를 자식 프로세스로 띄운다. 클래스패스를 합치면 `application.yml`, `ApplicationService`, `ValidatedApplicationService`, `BaseApplicationServiceTest`가 부딪히고 두 앱이 `com.loopers`를 훑는다. 운영의 commerce-api → commerce-streamer 의존성은 생기지 않는다. `test`가 streamer의 `bootJar`에 기대고 jar의 경로를 시스템 속성으로 받는다. jar가 없으면 건너뛰지 않고 실패한다.
- 인자는 `test` 프로필, 같은 MySQL과 broker의 주소, `ddl-auto=create`(streamer의 두 표만 만든다), 빈 포트, 클래스마다의 토픽과 group이다.
- 테스트 안에서 띄우고, 그 클래스의 `@AfterEach`에서 멈춘다. JUnit은 하위 클래스의 `@AfterEach`를 먼저 돌리므로 기반이 표를 비우기 전에 consumer가 멈춘다.
- 받은 횟수는 streamer의 `Recorded the order confirmed event. eventId=…, offset=…, result=…` 줄로 센다(`RECORDED` 한 번, 뒤는 `ALREADY_RECORDED`). record는 broker에서 읽는다. send 호출 수를 세지 않는다.
- broker 준비, consumer 배정, 결과에는 기한을 두고, 넘으면 그 단계의 실제 상태를 출력한다.
- (2026-11-06, [issue 214](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/214): 테스트 쪽 도구는 `CommerceStreamerProcess`이고 시스템 속성은 `commerce-streamer.jar`다. commerce-api의 `test`는 streamer의 `bootArchives` 구성을 입력으로만 받고 클래스패스에 넣지 않는다. broker 준비는 클래스의 토픽이 파티션 셋으로 보이는 때, 배정은 admin client가 본 group이 `STABLE`이고 멤버마다 파티션이 있으며 합쳐 셋 모두인 때다. 첫 세대는 멤버 하나가 셋을 다 쥘 수 있어 합만으로는 모자란다(streamer의 `BaseConsumerTest`와 같다). 기한은 토픽 30 s, 배정 90 s, 결과 30 s이고, 넘으면 받은 줄 수, group이 읽었다고 표시한 offset과 토픽의 끝 offset, 장면이 넘긴 행, streamer 로그의 마지막 40줄을 낸다. 끝내기는 SIGTERM 뒤 40 s를 기다리고 그래도 살아 있으면 강제로 끝낸 뒤 프로세스가 없음을 확인한다. 이름은 `terminate`이고, streamer의 멈추다(처리할 수 없는 이벤트에서 consumer가 서는 것)와 갈라 쓴다. 로그는 `apps/commerce-api/build/commerce-streamer/`의 파일에 남고 테스트 보고서에 섞이지 않는다. group은 클래스마다 하나라서, 뒤 장면의 streamer는 앞 장면이 읽었다고 표시한 offset부터 읽는다. 한 번 잰 값은 시작 7.4 s, 그 뒤 배정 0.6 s, 끝내기 20 ms이고, 장면 43 s의 나머지는 컨텍스트다. `a5507d5` 위의 커밋 전 작업 트리를 그 장면 하나만 걸러 돌렸고, 노트북의 Docker에서 Testcontainers의 `mysql:8.4`와 `apache/kafka-native:4.4.0`, streamer의 `test` 프로필이었다. jar가 없을 때의 실패는 `CommerceStreamerProcessTest`가 본다.)

### 14.5 7절

| 관찰 | 7×2와 8×1인 한 주문의 기대 | 어디서 보는가 |
| --- | --- | --- |
| 주문·PG 거래 | 확정 주문 1, 외부 거래 1 | DB, 테스트 PG의 기록 |
| 아웃박스 | 같은 이벤트 ID의 행 1, 끝에 `PUBLISHED` | DB |
| Kafka | 같은 이벤트 ID의 record 2개 이상, 다른 offset | broker |
| consumer | 같은 이벤트를 2번 이상 받음 | streamer의 줄 |
| 처리한 주문 확정 | 같은 주문과 이벤트 ID로 1 | DB |
| 구매 활동 | 상품마다 1행, 수량 2와 1 | DB |
| 재고·포인트 | 예약의 추가 소비와 반환 없음, 포인트 차감 없음 | DB |

멈춤은 13.9절의 `stopRelayAfterBrokerAckBeforeMarkingPublished`이고, 재시작은 새 `OrderEventRelay`다.

(2026-11-06, [issue 215](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/215): 장면은 `OutboxDeliveryE2ETest`의 둘째 `@Test`다. 멈춤은 2단계처럼 `OrderEventOutboxModifyService`의 spy에 거는 일회성 stub이고, 두 클래스가 함께 거므로 `OrderPaymentSpies.kt`의 확장 함수로 옮겼다. 이 클래스도 그 spy를 둔다. 과제의 상품 7과 8은 ID가 아니라 두 상품이다. 표를 비우면 ID가 1부터 다시 매겨지므로 두 상품은 1과 2다. 첫 중계가 멈춘 뒤 streamer가 첫 record를 `RECORDED`로 남기기를 기다리고, 임대가 끝난 뒤의 새 중계가 같은 이벤트를 새 record로 다시 발행한다. 둘째 줄은 `ALREADY_RECORDED`다. 행은 시도 2번에 `PUBLISHED`이고, 테스트 PG가 받은 요청은 생성 1번뿐이다. 재고는 확정 뒤 10에서 8과 9가 되고 끝까지 그대로이며, 충전한 포인트 10,000은 그대로다. 클래스의 토픽과 group을 첫 장면과 나눠 쓰므로 offset은 장면의 차례에 따라 다르다. 실행 값은 [글감](../research/week7-outbox-recovery-writing-material.md)에 있다.) (2026-11-06, [issue 217](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/217): 표의 "2개 이상"과 "2번 이상"은 정확히 2로 단언한다. 무엇도 발행하기 전에 group이 `STABLE`이기를 기다리므로, 셋째 record나 셋째 받음은 다시 배정의 소음이 아니라 결함이다. issue 215가 정했다.)

### 14.6 과제의 이름과 이 저장소의 이름

11절의 표에 더한다.

| 과제 | 이 저장소 | 까닭 |
| --- | --- | --- |
| 복구 | 누락 복구, `recovery` | 재개(막힌 이벤트를 되돌림)와 예약을 돌려주다(`복구하다`를 피한다)와 갈라 쓴다. |
| 기존 주문 API로 주문 | 제공 포트(`OrderConfirmer`와 주문의 포트)로 주문 | 컨트롤러가 부르는 포트다. 커밋하는 기반에는 HTTP 클라이언트가 없고, 서버를 띄우면 서버 스레드의 요청이 남긴 것을 따로 정리해야 한다. 6주차의 공백 테스트도 포트로 확정했다. |
| W6 revision 실행물 또는 보존한 W6 테스트 구성 | 보존한 W6 테스트 구성 | ADR 0036 규칙 5. |

### 14.7 3단계가 만든 것

2026-11-06 [명세 209](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/209)의 티켓이다. 명세가 이름 지은 것(`OrderEventRecoverer`, `order-events.recovery.order-ids`, `OrderEventRecoveryE2ETest`, `OutboxDeliveryE2ETest`, `stopRelayAfterBrokerAckBeforeMarkingPublished`, `commerce-streamer`의 `bootJar`)은 모두 그대로 최종 이름이 됐다. 명세가 이름 짓지 않은 것은 이렇게 정해졌다. 구현은 `OrderEventRecovery`(`NEVER`), 결과는 `OrderEventRecoveryResult`(`ADDED`, `ALREADY_PRESENT`), 주문마다의 트랜잭션은 `OrderEventOutboxModifyService.recover`, 주문 읽기는 `OrderFinder.findForRecovery`, 나눠 쓰는 행 만들기는 `OrderEventOutboxFactory`, 거절은 봉인된 `OrderEventRecoveryRefusedException`의 넷, 진입점은 `adapter.startup`의 `OrderEventRecoveryRunner`, 실제 consumer의 도구는 `CommerceStreamerProcess`, 재시작의 도구는 `restartedCommerceApi`다.

| 티켓 | 만든 것 |
| --- | --- |
| [210](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/210) | [ADR 0036](../adr/0036-missing-outbox-rows-are-recovered-for-named-orders-from-stored-facts.md), 14절, `CONTEXT.md`의 누락 복구와 아웃박스·발행하다의 고침, [측정 기록](../research/week7-outbox-recovery-probe.md), 글감의 머리말. |
| [214](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/214) | `CommerceStreamerProcess`, commerce-api의 `test`가 streamer의 `bootArchives`를 입력으로 받는 배선, `OutboxDeliveryE2ETest`의 첫 장면(6절의 셋째와 넷째 칸), `CommerceStreamerProcessTest`. |
| [211](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/211) | `OrderEventOutboxFactory`로 뽑은 행 만들기(먼저, 따로 커밋), 제공 포트와 구현, 주문마다의 트랜잭션, `findForRecovery`. `OrderEventRecovererTest`의 장면 7. |
| [212](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/212) | 넷의 거절, 여섯 열의 견주기(`OrderEventOutbox.eventColumnsDifferingFrom`), 첫 거절에서 멈춤, 거절의 WARN. `OrderEventRecovererTest`에 장면 12. |
| [213](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/213) | `OrderEventRecoveryRunner`, `adapter.startup` 층과 그 아키텍처 규칙, `application.yml`의 설명. `OrderEventRecoveryRunnerTest`. |
| [215](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/215) | 7절의 장면(`OutboxDeliveryE2ETest`의 둘째). 멈춤의 stub을 `OrderPaymentSpies.kt`의 확장 함수로 옮겼다. |
| [216](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/216) | 5절의 장면 `OrderEventRecoveryE2ETest`. 재시작 도우미 `restartedCommerceApi`(세 곳의 복사를 하나로), 커밋하는 기반의 구매 활동 읽기, `build-logic`의 테스트 heap 1 GB. |
| [217](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/217) | 이 표와 14.8절, 날짜 메모(ADR 0036 규칙 4 포함), 13.8절의 정리를 복구와 함께 다시 본 메모, 글감의 실행 값과 PR 본문 초안, 명세의 닫는 글. |

운영 코드는 211, 212, 213만 바꿨다. 나머지는 테스트와 빌드, 문서다.

명세와 티켓에서 달라진 것은 여덟이다. 모두 각 절이나 ADR의 날짜 메모에 있다. 시작의 거절은 원인으로 싸이지 않고 그대로 나온다(14.2절). 거절된 주문은 INFO 줄 대신 WARN 한 줄을 남긴다. 명세의 이야기 15와 ADR 0036 규칙 3은 주문마다 INFO 한 줄을 말했다(14.2절, ADR 0036 규칙 3의 2026-11-06 메모). 동시의 두 복구에서 진 쪽은 실패하거나, 먼저 커밋된 행을 같은 행으로 찾아 `ALREADY_PRESENT`로 끝난다. 명세는 실패만 말했다(14.2절, ADR 0036 규칙 4). 첫 컨텍스트는 닫지 않는다, 알림은 세지 않고 PG 조회와 결제가 그대로인 것으로 본다, 셋째 컨텍스트도 중계를 켠 채 2 s 머문다(14.3절). 상품 7과 8은 두 상품 1과 2다, "2개 이상"은 정확히 2개로 단언한다(14.5절). 명세가 요구하지 않았는데 더한 것은 새 `adapter.startup` 층(그 규칙은 issue 213이 예고했고, 두 트리거의 규칙은 이제 한 도우미 `triggerReachesOnlyProvidedPorts`에서 나온다), 배너를 끈 재시작 도우미, 테스트 heap이다.

명세의 범위 밖으로 남긴다. 확정됐는데 행이 없는 주문을 찾는 일, 포인트로 확정된 이전 주문의 발행, W6 DB에 표를 더하는 단계의 테스트, `PUBLISHED` 행의 정리(13.8절의 메모)다.

### 14.8 3단계의 명령

```bash
# 누락 복구의 규칙: OrderEventRecovererTest(19). 더하기, 같은 행, 넷의 거절, 첫 거절에서 멈춤, 동시의 두 실행
./gradlew :apps:commerce-api:test --tests '*OrderEventRecovererTest'

# 진입점: OrderEventRecoveryRunnerTest(6)
./gradlew :apps:commerce-api:test --tests '*OrderEventRecoveryRunnerTest'

# 5절: OrderEventRecoveryE2ETest(1). 실행 값은 scripts/scene-lines OrderEventRecoveryE2ETest
./gradlew :apps:commerce-api:test --tests '*OrderEventRecoveryE2ETest'

# 6절의 셋째·넷째 칸과 7절: OutboxDeliveryE2ETest(2). 실행 값은 scripts/scene-lines OutboxDeliveryE2ETest
./gradlew :apps:commerce-api:test --tests '*OutboxDeliveryE2ETest'

# 실제 consumer의 jar가 없을 때: CommerceStreamerProcessTest(2)
./gradlew :apps:commerce-api:test --tests '*CommerceStreamerProcessTest'

# TX B의 행이 추출 뒤에도 같은지: OrderEventOutboxTest(16), OrderEventOutboxAtomicityTest(2)
./gradlew :apps:commerce-api:test --tests '*OrderEventOutboxTest' --tests '*OrderEventOutboxAtomicityTest'

# 끝의 확인. 트리 전체의 ktlint, 두 앱의 cleanTest와 check, 수까지 돌리고 commit과 시각을 함께 적는다.
scripts/final-check
```

- 끝의 확인은 2026-11-06 `2b55dd0`의 깨끗한 트리에서 `scripts/final-check`으로 돌렸다(04:20:49Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(51 s). commerce-api는 95개 클래스, 1062개 테스트, 실패 0, 오류 0, 건너뜀 0이다(4 min 38 s). `2b55dd0` 뒤로는 문서만 바뀌었다. 위 블록의 괄호는 거른 명령을 따로 돌리지 않고 이 실행의 보고서에서 `scripts/test-counts`로 읽었다.
- (2026-11-06, [명세 209](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/209)의 닫는 검토: 검토의 수정 뒤 끝의 확인을 `c395d4a`의 깨끗한 트리에서 `scripts/final-check`으로 다시 돌렸다(04:56:46Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다. commerce-streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(48 s). commerce-api는 95개 클래스, 1062개 테스트, 실패 0, 오류 0, 건너뜀 0이다(4 min 47 s). 검토는 테스트를 더하거나 빼지 않아 수가 위와 같다. `c395d4a` 뒤로는 문서만 바뀌었다.)
- 3단계의 수는 2단계의 끝(90개 클래스, 1031개 테스트)에서 이렇게 왔다. 새 클래스 5개가 30개다(`OrderEventRecovererTest` 19, `OrderEventRecoveryRunnerTest` 6, `OutboxDeliveryE2ETest` 2, `CommerceStreamerProcessTest` 2, `OrderEventRecoveryE2ETest` 1). 있던 클래스에 1개가 늘었다(`HexagonalArchitectureTest` 8 → 9, `adapter.startup`의 규칙). streamer의 수는 그대로다.
- `--tests`로 거른 실행은 여전히 `HexagonalArchitectureTest`도 함께 돈다. 12절의 메모 그대로 위의 괄호는 그것을 뺀 것이다.
- 테스트 heap은 [issue 216](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/216)부터 1 GB다(`fc28dbc`). 그 클래스가 컨텍스트 하나와 재시작 둘을 더하자 commerce-api의 전체 실행이 Gradle의 기본 512 MB를 넘었다. 측정은 [글감](../research/week7-outbox-recovery-writing-material.md)에 있다.
- 첫 시작은 commerce-api의 테스트가 도는 동안 이 문서를 고쳐 멈췄다. 이 문서는 그 작업의 입력이라 그 실행은 깨끗한 트리의 것이 아니다. 고친 것을 물리고 처음부터 다시 돌린 위의 실행만 적는다.
