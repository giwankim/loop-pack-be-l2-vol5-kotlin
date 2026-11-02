---
status: accepted
date: 2026-11-02
---

# 주문 확정 이벤트는 TX B가 아웃박스 행으로 남기고, 아웃박스는 어댑터가 아니라 도메인과 응용 계층의 것이다

7주차 과제는 6주차의 커밋 뒤 직접 전송을 없애고, 주문을 확정하는 트랜잭션 안에서 Outbox 행을 쓰라고 한다. `REQUIRES_NEW`와 커밋 뒤 저장은 금지다. Outbox 저장이나 그에 필요한 직렬화가 실패하면 확정이 함께 되돌아간다. 결제 중, 결과 미확인, 실패한 결제에는 행이 없고, 중복 알림과 대사에도 행은 하나다. 행의 key와 payload는 변하지 않고, relay는 현재 상품 정보로 payload를 다시 만들지 않는다.

[ADR 0032](./0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md)의 다시 볼 조건은 같은 Spring 이벤트에 `BEFORE_COMMIT` 리스너를 더하는 길을 적어 두었다. 포트의 구현이 행을 직접 쓰는 길도 적었다. 둘 다 아웃박스를 `OrderEventPublisher` 뒤의 어댑터에 숨기는 모양이다. 인터뷰에서 이 전제를 다시 보았고, 이 ADR은 그 모양을 고르지 않는다.

1. **TX B가 행을 직접 쓴다.** `OrderPaymentSagaSteps.applyResult`가 `ReconcileResult.Changed`로 결제가 SUCCESS가 된 그 자리에서, 주문을 저장한 뒤 직렬화 포트를 부르고 저장소에 행을 저장한다. 이것을 감싸는 빈은 두지 않는다. 호출자가 하나이고 몇 줄뿐이라 감싸도 부르는 쪽이 알 것이 줄지 않는다. 트랜잭션은 단계의 것을 그대로 쓴다. 리스너도, 새 트랜잭션도, 커밋 뒤 저장도 없다.
2. **실패하면 확정이 되돌아간다.** 직렬화나 저장의 실패는 TX B의 실패다. 주문은 결제 중, 결제는 결과 미확인, 예약은 그대로이고 행은 없다. 뒤따르는 대사가 같은 PG 거래를 다시 반영해 주문을 확정하고 행을 하나 남긴다. PG 거래를 새로 만들지 않는다.
3. **엔티티는 `domain`에, 저장소는 `required`에 둔다.** `OrderEventOutbox`는 `domain.order`의 엔티티이고 `BaseEntity`를 상속한 자기 루트다. `OrderEventOutboxRepository`는 `application.order.required`의 Spring Data 포트다. 아홉 엔티티가 모두 `domain`에 있고, 순전히 기술적인 행인 `ProductListRevision`도 그렇다. 표는 `order_event_outbox`이고 `order-events` 토픽만 맡는다. 토픽 열은 없다.
4. **어댑터는 선 위의 꼴과 전송만 맡는다.** `adapter.integration`은 밖으로 나가는 통신의 층이고([ADR 0021](./0021-payment-is-its-own-aggregate-and-slice.md)) 엔티티도 저장소도 없다. `application.order.required.OrderEventSerializer`가 `OrderConfirmedEvent`를 `SerializedOrderEvent(eventType, schemaVersion, messageKey, payload)`로 바꾸고, 구현은 `OrderConfirmedMessage` 곁에 있다. `eventType`, `schemaVersion`, record의 key는 이미 어댑터의 지식이다. 구현은 `JsonMapper` 빈으로 쓴다.
5. **payload는 본문만 담는다.** `OrderConfirmedMessage.Payload`(`orderId`, `confirmedAt`, `items`)의 JSON이다. `event_id`, `event_type`, `schema_version`, `message_key`는 저마다 열이다. 봉투를 통째로 담으면 같은 값이 열과 JSON에 두 벌 있어 서로 어긋날 수 있다. 식별·유형·key를 열에 두고 본문을 한 열에 담는 것이 [Debezium](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)과 [Spring Modulith](https://docs.spring.io/spring-modulith/reference/appendix.html)의 공통된 꼴이다.
6. **payload 열은 `TEXT`이고 `json_valid` 검사를 단다.** 제약의 이름은 `CK_ORDER_EVENT_OUTBOX_PAYLOAD`다. (2026-11-02, [명세 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)의 닫는 검토: 코드의 이름은 `CK_ORDER_EVENT_OUTBOX_ON_PAYLOAD_JSON_VALID`다. `CODING_STANDARDS.md`의 제약 이름이 한 컬럼의 규칙을 `<컬럼>_<조건>`으로 부르기 때문이다. 규칙은 그대로다.) MySQL의 `JSON`과 견주어 실제 스택에서 재 보았다([측정 기록](../research/week7-outbox-payload-column-probe.md)). 전송되는 record의 bytes, 값의 보존, JSON 함수, 저장과 읽기의 시간은 가르지 못했다. 가른 것은 셋이다. `JSON`은 값마다 약 15% 크고, `TEXT` → `JSON`은 `ALTER`로 되지만 `JSON` → `TEXT`는 TX B가 쓴 문자열을 되찾지 못하며, MySQL 매뉴얼이 `JSON`의 키 차례는 릴리스 사이에 같다고 보장하지 않는다고 적는다. `JSON`의 이점 가운데 검증은 이 검사가 같게 주고, payload 안을 빠르게 훑는 일은 읽는 쪽이 없다.
7. **DB 제약은 마지막 방어선이다.** `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`와 규칙 6의 검사를 둔다. 평소에는 주문 잠금과 `ReconcileResult.Changed`가 행을 하나로 지키고, 직렬화가 올바른 JSON을 쓴다. `event_id`가 `order_id`에서 유도되므로 `order_id`의 유일 제약은 더하지 않는다. 사건의 열(`event_id`, `order_id`, `event_type`, `schema_version`, `message_key`, `payload`)은 `val`에 `@Column(updatable = false)`다. Hibernate의 `UPDATE`에 들지 않는다.
8. **행은 처음부터 전체 모델이고, 이 단계는 첫 상태만 쓴다.** `status`는 `PENDING`, `attempt_count`는 0, `next_attempt_at`은 TX B가 `Clock` 빈에서 읽은 시각, `published_at`과 `last_error`는 없음이다. 상태의 변화, 재시도, 조회 인덱스는 중계(2단계)의 것이다. `created_at`은 `BaseEntity`의 값이라 `Clock` 빈을 따르지 않으므로 다음 시도의 기준으로 쓰지 않는다.
9. **중계는 행에서 메시지를 다시 만들어 타입이 있는 템플릿으로 보낸다.** 2단계의 어댑터는 열과 payload로 `OrderConfirmedMessage`를 만들고 `KafkaTemplate<String, OrderConfirmedMessage>`로 보낸다. 값 직렬화는 `kafka.yml`의 `JacksonJsonSerializer` 그대로이고, record의 bytes는 6주차와 같다(한 번 측정했다. 2단계가 테스트로 못 박는다). 공용 빈은 `KafkaTemplate<Any, Any>`라 주입되지 않으므로 전용 빈을 둔다. 이것은 과제의 "저장된 identity·payload를 그대로" 보낸다는 말에서 벗어난다. 같은 사실을 다시 인코딩해 보내는 것이지 같은 bytes를 보내는 것이 아니다. 과제가 막는 것은 현재 상품 정보로 다시 만드는 일이고, 이 길은 행만 읽는다.
10. **이 단계는 6주차의 전송 경로를 지운다.** `OrderEventPublisher`, `SpringEventOrderEventPublisher`, `OrderConfirmedKafkaRelay`, `orderEventsExecutor`, `@EnableAsync`, `OrderEventPublishGapTest`가 사라진다. 빠른 길로 남기지 않는다. 토픽 선언과 그 설정은 남는다. `OrderEventPublisher`는 2단계에서 중계가 부르는 포트로 돌아오고, 조건이 거꾸로 된다. 지금은 트랜잭션 밖에서 거절되지만 그때는 트랜잭션 밖에서만 돈다. 발행하다는 토픽에 보내는 일만 뜻하고([`CONTEXT.md`](../../CONTEXT.md)), 행을 쓰는 일은 아웃박스에 남기는 일이다.

## 고르지 않은 것

- **`BEFORE_COMMIT` 리스너(ADR 0032의 계획).** 같은 트랜잭션에서 돌지만 한 번 더 건너가고, 실패가 커밋 안에서 나서 단계의 흐름에서 보이지 않는다. 그것이 지키려던 "한 이벤트에 여러 리스너를 거는 자리"를 쓸 곳이 없다.
- **포트 구현이 행을 쓴다(아웃박스를 어댑터에 숨긴다).** 응용 계층은 "발행한다"만 말하게 된다. 그러나 부르는 쪽은 자기 트랜잭션이 있어야 하고 실패하면 확정이 되돌아간다는 것을 어차피 알아야 한다. 그런 약속을 가진 포트는 이름만 다른 저장소다. 게다가 PENDING → PUBLISHED, 재시도 간격, 배치 크기, 재개처럼 과제와 용어집이 이름 부르는 결정이 어댑터로 들어가 어댑터가 두꺼워진다. 348줄인 `LocalPgGateway`는 PG와 말하는 일로 두껍지만, 이것은 결정으로 두꺼워진다. 엔티티와 저장소가 처음으로 `domain`과 `required` 밖에 놓이는 것이기도 하다.
- **행을 쓰는 작은 빈(`OrderEventOutboxWriter`).** `MANDATORY`로 트랜잭션을 강제할 수 있지만 TX B는 늘 트랜잭션 안이라 막을 것이 없다. 3단계의 복구가 같은 코드를 되풀이하면 그때 뽑는다.
- **저장된 문자열을 그대로 보낸다.** Debezium의 꼴이고 과제의 "그대로"에 가장 가깝다. 그러나 `KafkaTemplate<String, String>`이 따로 필요하고 값의 타입이 사라진다. `JacksonJsonSerializer`로 보내면 문자열이 한 번 더 따옴표에 싸인다. 이벤트 유형 하나, 판 하나인 지금은 타입이 있는 전송을 고른다.
- **`JSON` 열.** 규칙 6. 처음에 `TEXT`를 고른 까닭은 저장된 bytes를 그대로 보낸다는 것이었고, 규칙 9가 그 까닭을 없애 다시 재 보았다. 결론은 같아졌지만 까닭이 다르다.
- **봉투를 통째로 저장한다.** 규칙 5.

## 대가

- 응용 계층이 아웃박스를 안다. 전달 방식을 CDC로 바꾸면 중계는 사라지지만 표와 쓰기는 남으므로 잃는 것은 작다. 응용 계층은 여전히 Kafka를 모른다.
- 직렬화 포트가 하나 는다. 선 위의 꼴을 어댑터에 두는 값이다.
- `week7`은 1단계와 2단계 사이에 아무것도 발행하지 않는다. 6주차의 누락 구간 재현은 3단계가 `week6` 판의 코드로 다시 만든다.
- 아웃박스 표가 계속 고장 나면 PG에 청구된 주문이 표를 고치고 대사가 성공할 때까지 결제 중에 머문다. 과제가 요구한 맞바꿈이다. 이벤트 없는 확정을 막는 대신 확정이 Outbox에 묶인다.
- 중계는 저장된 모든 판을 읽을 수 있어야 한다. 그대로 보내는 길에는 없는 부담이다. `JacksonConfig`가 `FAIL_ON_UNKNOWN_PROPERTIES`를 꺼 두었으므로, 나중의 클래스가 필드를 빼면 옛 행의 그 필드는 다시 인코딩할 때 말없이 사라진다. v1 하나뿐인 지금은 해가 없다.
- `payload`의 문자열은 record의 값과 다르다. 저장된 값은 본문이고 `JsonMapper`가 쓰며, record는 봉투이고 `JacksonJsonSerializer`가 쓴다. 둘을 문자열로 견줄 수 없다.
- 운영의 `ddl-auto`는 `none`이고 마이그레이션 도구가 없다. 다른 표처럼 이 표와 제약도 엔티티 선언이 문서다.

## 다시 볼 조건

- **판이 둘이 되거나 이벤트 유형이 늘면** 규칙 9를 다시 본다. `schema_version`으로 나눠 읽거나 그대로 보내는 길로 옮긴다. `TEXT`라 저장된 값은 그대로 쓸 수 있다.
- 운영에서 payload 안을 SQL로 자주 거르게 되면 그 값을 열로 뽑거나 `JSON`으로 옮긴다.
- 3단계의 복구가 행을 만드는 코드를 되풀이하면 규칙 1의 "감싸는 빈 없음"을 다시 본다.

## 바꾸는 결정

[ADR 0032](./0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md)의 규칙 1, 3, 4, 5(포트와 커밋 뒤 비동기 중계)와 다시 볼 조건의 첫 줄을 대체한다. 규칙 2, 6의 계약, 7, 8, 9는 그대로다. 그 ADR에 날짜 메모를 단다. eventId는 여전히 유도하고, 아웃박스가 그 값을 함께 저장한다.
