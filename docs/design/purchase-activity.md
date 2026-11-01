# 구매 활동 설계 — 주문 확정 이벤트의 기록

6주차 과제 2단계(`feat/purchase-activity`)의 설계 문서다. 용어는 [`apps/commerce-streamer/CONTEXT.md`](../../apps/commerce-streamer/CONTEXT.md)를 따르고, 계약은 [주문 이벤트 설계](./order-events.md) 2절이다. 결정은 [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md)에 있다.

상태: 2026-10-28 소비 쪽(2단계)을 인터뷰하고 썼다. 구현이 바꾸는 것은 날짜 메모로 단다. 전달 실험(3단계, `feat/kafka-delivery`)은 저마다의 인터뷰 뒤에 쓴다. 같은 날 2단계([명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176))의 티켓을 마치고, 구현이 바꾼 것을 날짜 메모와 10절의 새 줄 둘로 달고 1.3절과 11절의 명령과 수를 더했다.

## 1. 근거와 범위

- 과제: [6주차 실습 · Kafka 설정 변화로 전달 상태 설명하기](https://app.notion.com/p/6-Kafka-4025f76d2654800da946cb2b4ad1bc88). 2단계의 근거는 과제의 소비 부분 다섯이다.
  1. **저장.** `order-events`의 `OrderConfirmed`가 오면 처리한 주문 확정 하나와 상품별 구매 활동 행을 한 트랜잭션에, DB 유일 제약 아래 쓴다.
  2. **같은 사실과 충돌.** 같은 사실이 다시 오면(다시 발행되거나 다시 전달되어) 아무것도 바꾸지 않고 읽었다고 표시한다. 같은 eventId의 다른 내용은 충돌이다.
  3. **리스너 설정.** 템플릿의 batch 팩토리와 다른 record 리스너 팩토리, `MANUAL_IMMEDIATE`, 중단형 error handler. 실제 consumer 설정의 `enable.auto.commit`이 false임을 테스트가 읽는다.
  4. **YAML.** `kafka.yml`의 `consumer.value-serializer`는 `value-deserializer`다.
  5. **7주차에 넘기는 사례.** 정상 발행·소비 사례 1건의 기록. 미발행 사례는 1단계가 남겼다(주문 이벤트 설계 6절).
- 1단계의 결과에서 시작한다. 랭킹 API, Outbox, 전달 실험은 이번 단계가 아니다.
- 구현 기록: 티켓마다의 닫는 댓글을 옮길 `docs/research/week6-purchase-activity-writing-material.md`. 첫 티켓이 만든다. (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): [6주차 구매 활동 글감](../research/week6-purchase-activity-writing-material.md)이다. 티켓 177이 만들었고, 7주차에 넘기는 소비 사례의 실행 값(8절), 머리말과 PR 본문 초안도 그 파일에 있다.)

### 1.1 왜 이벤트인가

과제 글의 첫 문단에 멘토가 "왜??"라고 물었다. 확정을 다른 앱에 이벤트로 넘기는 까닭을 적지 않았기 때문이다. 세 길이 있었다.

| 길 | 생기는 일 |
| --- | --- |
| commerce-api가 TX B에서 구매 활동 표를 함께 쓴다 | 랭킹의 쓰기가 결제 확정의 트랜잭션에 들어온다. 그 표의 잠금과 스키마가 확정의 지연과 실패가 된다. 9주차의 집계가 바뀔 때마다 결제 코드가 바뀐다. 두 앱이 한 DB를 나눠 쓸 때만 된다. |
| streamer가 커머스의 `orders`·`order_line_item`을 읽는다 | 남의 표의 스키마가 두 앱의 계약이 된다. 커머스가 컬럼을 바꾸면 streamer가 깨진다. 확정 시점을 알려면 폴링이나 CDC가 필요하고, 커머스의 삭제 필터와 상태 기계를 streamer가 다시 알아야 한다. |
| 커머스가 발행하고 streamer가 기록한다 | 계약은 봉투 하나(주문 이벤트 설계 2절)다. 확정은 한 번 발행되고 streamer는 제 속도로 읽는다. streamer가 멈춰도 커머스는 모른다. 대가는 누락 구간(주문 이벤트 설계 6절)과 결과적 일관성이고, 7주차의 Outbox가 누락 구간을 좁힌다. |

셋째를 골랐다. 그래서 이 맥락은 주문과 상품을 식별자로만 알고(3절), 커머스의 표로 가는 외래 키가 없다. 저장소의 `CONTEXT.md`가 이벤트를 "다른 맥락에 전하는 메시지"라 한 것이 글자 그대로가 되어 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)가 생겼다.

### 1.2 세 단계

주문 이벤트 설계 1.1절의 표다. 이 문서는 둘째 줄이다.

### 1.3 2단계가 만든 것

2026-10-28 [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 티켓이다. 명세가 "브랜치와 단계"에 자리표시로 둔 열세 이름(`PurchaseActivityRecorder`, `PurchaseActivityRecordRequest`, `PurchaseActivityRecordResult`, `PurchaseActivityConflictException`, `UnsupportedOrderEventException`, `MalformedOrderEventException`, `UnprocessableOrderEventException`, `OrderEventsListenerConfig`, `ORDER_EVENTS_LISTENER`, `OrderEventsListener`, `OrderEventMessage`, `OrderConfirmedPayload`, `OrderEventsProperties`)은 모두 그대로 최종 이름이 됐다. 명세의 다른 자리가 적은 `PurchaseActivityRecorderService`만 `PurchaseActivityModifyService`가 됐다(4절의 날짜 메모).

| 티켓 | 만든 것 |
| --- | --- |
| [177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177) | streamer의 정리(이름 `commerce-streamer`, `modules:redis` 뺌, `interfaces.consumer` → `adapter.consumer`, 데모 리스너의 `autoStartup`), `kafka.yml`의 `value-deserializer`, 베낀 stereotype 둘과 표지 타입 `UnprocessableOrderEventException`, `OrderEventsListenerConfig`의 record 팩토리 `ORDER_EVENTS_LISTENER`와 `OrderEventsProperties`, 두 기반과 테스트가 선언하는 토픽 `TestOrderEventsTopicConfig`, 컨텍스트와 설정을 읽는 테스트(5절, 7절). |
| [178](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/178) | 루트 `ProcessedOrderConfirmation`과 `PurchaseActivity`, 저장소 포트 둘, 이름 붙은 유일 키 둘과 CHECK, 확정 시각을 마이크로초로 자르는 생성자, 진짜 생성자를 부르는 fixture(3절). |
| [179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179) | provided 포트 `PurchaseActivityRecorder`와 요청·결과·충돌 예외, 먼저 읽는 구현 `PurchaseActivityModifyService`, `saveAll`, 포트로 준비하는 기반의 `prepareRecordedEvent`(4절). |
| [180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180) | 리스너 `OrderEventsListener`, 봉투 `OrderEventMessage`와 `OrderConfirmedPayload`, `UnsupportedOrderEventException`과 `MalformedOrderEventException`, 경합을 푸는 `recordSettlingARace`, 기본 토픽 `OrderEventsProperties.DEFAULT_TOPIC`, broker에서 표까지의 `OrderEventsListenerTest`, 기반의 배정 기다림(2절, 5절, 7절). |
| [181](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/181) | 처리할 수 없는 이벤트의 ERROR 한 줄, 멈춤 장면 `OrderEventsListenerStopTest`, 기반의 멈춤 기다림과 비운 토픽 위의 재시작, 봉투를 만드는 `OrderEventValues.kt`, 7주차에 넘기는 소비 사례 `OrderEventConsumedCaseTest`(5절, 6절, 8절). |
| [182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182) | 문서의 날짜 메모, 이 표, 11절의 명령과 수, ADR 0033의 날짜 메모, 글감의 머리말과 PR 본문 초안. |
| 닫는 검토 | JSON이 아닌 값의 멈춤 장면(`OrderEventsListenerStopTest`), 엔티티와 팩토리의 KDoc과 `application.yml` 주석의 낱말, 이 문서와 ADR 0033의 날짜 메모(5절, 6절, 7절, 11절). |

운영 코드에서 `PurchaseActivityRecorder`를 부르는 곳은 `OrderEventsListener` 하나다. commerce-api의 코드는 바뀌지 않았고, `modules:kafka`에서 바뀐 것은 `kafka.yml`의 한 줄이다.

## 2. 계약을 읽는 쪽

- **봉투.** `OrderEventMessage(eventId: String, eventType: String, schemaVersion: Int, payload: JsonNode)`. 리스너가 변환기에서 받는 타입이다. `order-events`는 여러 종류의 이벤트를 실을 수 있는 이름이라 `payload`는 `eventType`을 본 뒤에 옮긴다.
- **payload.** `OrderConfirmedPayload(orderId: Long, confirmedAt: Instant, items: List<Item>)`, `Item(productId: Long, quantity: Int)`. `eventType`이 `OrderConfirmed`이고 `schemaVersion`이 1일 때만 옮긴다.
- 두 DTO는 `adapter.consumer`의 것이다. 발행 쪽의 `OrderConfirmedMessage`와 나눠 가진다(주문 이벤트 설계 2절). `confirmedAt`은 문자열이 아니라 `Instant`로 읽는다. 선 위의 꼴은 `ISO_INSTANT`의 셋 가운데 하나(`…03.123456Z`, `…03.100Z`, `…03Z`)이고 값은 마이크로초까지 그대로다.
- **응용의 요청.** 리스너는 payload를 `application.purchaseactivity.provided.PurchaseActivityRecordRequest(eventId, orderId, confirmedAt, items)`로 옮겨 기록하는 포트에 넘긴다. 이름은 카탈로그 설계 5.17의 `<Aggregate><Verb>Request`다. Bean Validation은 이 요청에만 있다(`items` 비어 있지 않음, `quantity` 양수). 엔티티는 같은 것을 스스로 검사한다.
- 모르는 `eventType`은 건너뛴다. 기록하지 않고 읽었다고 표시하며 INFO 한 줄(eventId, eventType, partition, offset)을 남긴다.

## 3. 모델

두 루트다. 처리한 주문 확정은 중복을 가르는 기록이고 구매 활동은 9주차가 읽는 사실이다. 보관 기한과 읽는 쪽이 다르고, 같은 주문을 건드리는 둘째 이벤트가 오면 한 애그리거트는 남의 자식 행을 가져야 한다(ADR 0033 규칙 2). 둘 다 `BaseEntity`를 상속하고 루트로서 `@SQLRestriction("deleted_at is null")`을 단다(ADR 0016). 삭제는 기능이 아니다.

| 표 | 컬럼 | 제약 |
| --- | --- | --- |
| `processed_order_confirmation` | `event_id`, `order_id`, `confirmed_at` | `UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID`, `UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID` |
| `purchase_activity` | `order_id`, `product_id`, `quantity`, `confirmed_at` | `UK_PURCHASE_ACTIVITY_ON_ORDER_ID_AND_PRODUCT_ID`, `CK_PURCHASE_ACTIVITY_ON_QUANTITY_POSITIVE` |

- `order_id`와 `product_id`는 값이다. `orders`·`product`로 가는 외래 키는 없고 streamer는 그 표를 읽지 않는다(1.1절). `scalar-foreign-keys.sql`에도 넣지 않는다.
- `confirmed_at`은 구매 활동에도 둔다. 9주차의 시간 창이 처리한 주문 확정을 거치지 않고 읽는다. 그 인덱스는 9주차가 둔다.
- 엔티티는 `domain.purchaseactivity`, 포트는 `application.purchaseactivity.required.ProcessedOrderConfirmationRepository`와 `PurchaseActivityRepository`(Spring Data, 없으면 null), 구현은 `adapter.persistence.purchaseactivity`다. commerce-api와 같은 헥사고날 구조(ADR 0013)이고, 지금 streamer의 `interfaces.consumer`는 `adapter.consumer`가 된다. (2026-10-28, [티켓 178](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/178): 두 엔티티는 생성자에서 `confirmedAt`을 마이크로초로 자른다. MySQL은 일곱째 자리를 버리지 않고 반올림한다. 자르지 않은 `…03.123456789Z`를 저장하자 `…03.123457Z`로 읽혔다(탐침). 자르지 않으면 다시 온 같은 사실이 저장된 시각과 어긋나 충돌로 보인다. 생성자가 필드를 계산하므로 fixture는 ADR 0010의 기준대로 Instancio가 아니라 진짜 생성자를 부른다. 두 표의 쿼리는 모두 파생 조회라 `adapter.persistence.purchaseactivity`는 아직 없다. 포트에는 테스트가 쓰는 `save`와 조회만 두었고, 4절의 `saveAll`은 그것을 처음 부르는 기록 티켓이 더한다.)
- 스키마는 두 앱이 같은 `loopers`를 쓴다. Hibernate의 `create`는 그 앱이 매핑한 표만 지우고 만들므로 서로의 표를 건드리지 않는다.

## 4. 기록

provided 포트 `application.purchaseactivity.provided.PurchaseActivityRecorder.record(request): PurchaseActivityRecordResult`. 구현은 `@ValidatedApplicationService` 하나이고 트랜잭션도 하나다(ADR 0033 규칙 3). (2026-10-28, [티켓 179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179): 구현의 이름은 `PurchaseActivityModifyService`다. 티켓과 명세 176은 `PurchaseActivityRecorderService`라 했지만, ADR 0013은 슬라이스의 쓰기 포트를 `<X>ModifyService`가 구현한다고 정하고, 3절이 streamer도 그 구조를 따른다고 한다.)

1. eventId로 처리한 주문 확정을 읽는다. 없으면 orderId로 읽는다.
2. 둘 다 없으면 처리한 주문 확정을 저장하고 구매 활동을 `saveAll`한다. `RECORDED`.
3. eventId로 찾았으면 견준다. orderId, 시각으로 읽은 confirmedAt, 상품별 수량의 집합이 모두 같으면 아무것도 쓰지 않는다. `ALREADY_RECORDED`. 하나라도 다르면 `PurchaseActivityConflictException`.
4. orderId로만 찾았으면 다른 eventId다. `PurchaseActivityConflictException`.

- **견줌의 정의.** 속성의 차례와 `items`의 차례는 사실이 아니다. 구매 활동 행을 상품별 `(productId, quantity)`의 집합으로 만들어 요청의 것과 견준다. 지문은 저장하지 않는다. (2026-10-28, [티켓 179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179): 견주는 것은 요청 자체가 아니라 요청으로 만든 엔티티다. 엔티티가 시각을 마이크로초로 자르므로, 요청의 시각을 그대로 견주면 일곱째 자리가 있는 같은 사실이 저장된 자기와 충돌한다. 변이 탐침에서 `…03.123456Z`를 저장한 뒤 `…03.123456789Z`가 충돌이 되었다. 상품은 양쪽에서 한 번씩이므로(구매 활동의 유일 키, 요청의 검증) 집합은 상품에서 수량으로 가는 맵이고, 충돌의 메시지가 그 맵을 상품 차례로 적는다. 메시지는 온 eventId와 orderId, 그리고 다른 값마다 `<이름> recorded <저장된 값>, incoming <온 값>`이다.)
- **겹친 상품.** (2026-10-28, [티켓 179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179)) 요청의 `@AssertTrue`가 `ConstraintViolationException`으로 거절한다. 엔티티는 한 행이라 겹침을 볼 수 없다. 거절이 없으면 처리한 주문 확정을 삽입한 뒤 구매 활동의 유일 키가 둘째 품목을 `DataIntegrityViolationException`으로 거절하고(변이 탐침), 리스너 어댑터가 그것을 경합으로 읽어 다시 부른다. 6절의 운영 모양이 재시도하지 않는 것으로 등록할 예외 쪽에 둔 것이다.
- **경합.** 같은 eventId를 두 consumer가 같은 순간 다루는 일은 key가 orderId라 한 파티션의 record를 둘이 나눠 가질 때, 곧 rebalance를 건너는 순간에만 생긴다. 진 쪽은 유일 제약의 `DataIntegrityViolationException`을 받는다. 리스너 어댑터가 같은 호출을 한 번 다시 하면 읽는 경로가 `ALREADY_RECORDED`나 충돌로 푼다. 이 재시도는 error handler의 재시도가 아니다. (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 한 번 더 부르는 것은 `OrderEventsListener.recordSettlingARace`이고, 두 번째 위반은 리스너 밖으로 나가 컨테이너를 세운다. 이 경로는 티켓 180의 탐침으로만 봤다. 저장소 spy가 진 쪽의 첫 조회를 비워 경합을 흉내 내자 다시 부른 호출이 `ALREADY_RECORDED`로 끝났고, 다시 부르지 않는 변이는 컨테이너를 세웠다. 남은 테스트는 없다.)
- **왜 먼저 읽는가.** 1단계는 "유일 제약이 막고, 위반 뒤 별도 TX에서 다시 본다"로 넘겼다. 그러면 흔한 경로인 다시 온 같은 사실이 예외와 롤백이 되고, 위반 뒤의 JPA 세션은 쓸 수 없어 두 번째 트랜잭션과 steps 빈이 필요하다. 먼저 읽으면 그것은 SELECT 하나이고 제약은 경합의 뒷받침이다.
- **왜 견주는가.** 운영의 inbox 구현은 대개 메시지 ID를 보면 그냥 건너뛴다. 과제와 용어집의 이벤트 ID는 같은 ID의 다른 내용을 충돌이라 하므로 견준다. 발행 쪽의 버그로 바뀐 이벤트가 소리 없이 읽었다고 표시되지 않는다. 비용은 어차피 하는 읽기 한 번의 비교다.
- 저장은 명시적이다(카탈로그 설계 5.37). dirty checking에 기대지 않는다.

## 5. 리스너와 설정

**소유의 경계.** `modules:kafka`는 연결과 직렬화를 갖는다. 주소, 역직렬화 클래스, `KafkaTemplate`, `ByteArrayJacksonJsonMessageConverter`다. 리스너의 약속은 앱이 갖는다. commerce-api의 토픽 빈과 실행기가 `adapter.integration.kafka`에 있는 것과 같은 선이다(ADR 0033 규칙 7). 템플릿의 batch 팩토리는 그대로 두고 쓰지 않는다.

- **팩토리.** `adapter.consumer.OrderEventsListenerConfig`의 빈 `ORDER_EVENTS_LISTENER`. `KafkaProperties.buildConsumerProperties()`에 값 역직렬화 `ByteArrayDeserializer`를 명시해 `DefaultKafkaConsumerFactory`를 만들고, `MANUAL_IMMEDIATE`, `CommonContainerStoppingErrorHandler`, concurrency 3, 공유 변환기를 record 변환기로 둔다. 3은 토픽의 파티션 수와 짝이다(주문 이벤트 설계 7절). (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 빈 이름은 상수 `ORDER_EVENTS_LISTENER`의 값 `orderEventsListenerContainerFactory`다. commerce-api의 `ORDER_EVENTS_EXECUTOR = "orderEventsExecutor"`와 같은 꼴이다.) (2026-10-28, [티켓 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177): 팩토리는 `enable.auto.commit=false`도 명시한다. `kafka.yml`의 `consumer.properties.enable-auto-commit`은 날것의 `properties` 맵에 있어 Kafka에 그 철자대로 넘어가고, Kafka의 키는 `enable.auto.commit`이라 쓰이지 않는다. 컨테이너는 키가 없으면 false로 두지만, `MANUAL_IMMEDIATE`가 기대는 값을 팩토리가 밝힌다. 모듈의 키는 템플릿 정리와 함께 범위 밖이다.)
- **역직렬화.** `ByteArrayDeserializer`는 실패하지 않는다. JSON의 변환은 리스너 어댑터가 메서드의 매개변수 타입으로 하고, 실패는 `MessageConversionException`으로 error handler에 간다. 값 역직렬화기로 `JacksonJsonDeserializer`를 쓰면 변환이 `poll()` 안에서 돌아 실패한 record가 `ErrorHandlingDeserializer` 없이는 되풀이되고, 타입을 header나 기본 타입으로 정해야 한다. 발행 쪽은 타입 header를 끄고 있다. `kafka.yml`의 `consumer.value-serializer`는 `value-deserializer`로 고친다. 지금은 Boot의 기본값 `StringDeserializer`가 들어가 변환기와 어긋난다. (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: 실패의 타입은 `MessageConversionException`이 아니라 spring-kafka의 `ConversionException`이다. `JacksonJsonMessageConverter`가 Jackson의 실패를 그것으로 감싸 던지고, 컨테이너가 다시 `ListenerExecutionFailedException`으로 감싸 error handler에 넘긴다. 멈춤 장면의 JSON이 아닌 값이 이 사슬을 본다([`dc6d03c`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/dc6d03c), 7절의 날짜 메모).)
- **리스너.** `adapter.consumer.OrderEventsListener`. `@KafkaListener(id = "order-events", topics = ["\${order-events.topic}"], containerFactory = ORDER_EVENTS_LISTENER)`. 매개변수는 봉투, `RECEIVED_PARTITION`과 `OFFSET` header, `Acknowledgment`다. 차례는 `eventType` 확인 → payload 변환 → 요청 → 기록 → `acknowledge()`다. `id`가 있어야 테스트와 3단계가 registry로 세우고 다시 띄운다. (2026-10-28, [티켓 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177): `@KafkaListener`의 `idIsGroup`은 기본이 true라 `groupId` 없이 `id`만 주면 그 id가 group이 되어 `spring.kafka.consumer.group-id`를 덮는다. 177의 탐침에서 `id = "probe"`인 리스너가 group `probe`로 들어갔고, `idIsGroup = false`를 주자 `commerce-streamer`로 들어갔다. 리스너는 `idIsGroup = false`를 함께 둔다. 팩토리의 설정을 읽는 테스트는 이것을 잡지 못하므로 리스너 티켓이 뜬 컨테이너의 `groupId`를 단언한다.) (2026-10-28, [티켓 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180): `groupId`를 따로 단언하지 않았다. 소비 장면이 group `commerce-streamer`의 커밋된 offset을 기다리므로, 다른 group에 든 리스너는 그 장면에서 실패한다. 토픽은 `${order-events.topic:order-events}`다. 기본값을 빼면 컨텍스트가 `PlaceholderResolutionException`으로 뜨지 않았다. 자리표시자는 `Environment`를 읽는데 Kotlin의 기본 인자는 binder에만 있다. 기본값은 `OrderEventsProperties.DEFAULT_TOPIC` 한 곳에 두고 둘이 함께 쓴다. payload는 공유 `JsonMapper`의 `treeToValue`로 옮긴다. Jackson의 실패와 JSON `null`인 payload가 `MalformedOrderEventException`이다(탐침: 빠진 `orderId`는 `Missing required creator property 'orderId'`). INFO는 `acknowledge()` 앞에 남긴다.) (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 매개변수에는 `RECEIVED_KEY` header도 있다. 티켓 181이 ERROR 줄의 orderId를 record의 key에서 읽으려고 더했고(아래 로그의 날짜 메모), key가 null인 record에는 그 header가 없어 `required = false`다. 차례는 `eventType` 확인 → `schemaVersion` 확인 → payload 변환 → 요청 → 기록 → INFO → `acknowledge()`이고, 처리할 수 없는 이벤트는 ERROR 한 줄을 남기고 다시 던진다.)
- **설정.** streamer의 `application.yml`에 `spring.kafka.consumer.group-id: commerce-streamer`, `spring.kafka.consumer.properties.auto.offset.reset: earliest`, `spring.kafka.consumer.properties.allow.auto.create.topics: false`. 토픽 이름은 `@ConfigurationProperties("order-events")`의 `topic`(기본 `order-events`)이다. commerce-api의 것과 같은 접두사이고 streamer의 복사본이다. 토픽은 commerce-api가 선언하고 만든다. streamer는 만들지 않고, 없으면 한 파티션짜리가 저절로 생기지 않게 consumer의 자동 생성을 끈다. (2026-10-28, [티켓 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177): 이 셋은 `kafka.yml`을 가져오는 문서 아래의 별도 문서에 둔다. 가져온 파일은 가져오는 문서를 덮으므로, 같은 문서에 둔 `group-id`가 `kafka.yml`의 `loopers-default-consumer`에 졌다. 뒤의 문서는 가져온 파일을 덮는다.)
- **왜 `earliest`인가.** `auto.offset.reset`은 group에 커밋된 offset이 없을 때만 쓰인다. 새 group이거나 broker의 `offsets.retention.minutes`(기본 7일)가 지났을 때다. `latest`는 새 group을 머리에서 시작해 그 전의 확정을 소리 없이 건너뛰고, 보관 기간보다 오래 멈췄다 돌아온 consumer도 그 사이를 건너뛴다. 기록하는 consumer는 멱등하므로 되읽기가 안전하고, 발행 쪽보다 늦게 배포된 streamer가 확정을 놓치지 않는다. 실시간 대시보드처럼 밀린 것이 해로운 consumer가 `latest`를 고른다.
- **MANUAL_IMMEDIATE.** `acknowledge()`를 부르는 그 자리에서 consumer 스레드가 동기로 offset을 커밋한다. `MANUAL`은 poll한 묶음을 다 처리한 뒤에 커밋해, 기록과 표시 사이에 멈추면 이미 표시한 record까지 다시 온다. 중단형 handler와 짝이다. 실패한 record는 표시되지 않으므로 다시 시작하면 정확히 그 record부터 읽는다.
- **로그.** 기록마다 INFO 한 줄(eventId, orderId, partition, offset, 결과). 건너뛸 때 INFO 한 줄. 충돌은 ERROR 한 줄에 eventId, orderId, partition, offset, 저장된 값과 온 값의 차이를 적는다. 사람이 받는 증거가 그 줄뿐이다. (2026-10-28, [티켓 181](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/181): ERROR 줄은 충돌만이 아니라 리스너에 닿은 처리할 수 없는 이벤트마다 하나다. 리스너가 `UnprocessableOrderEventException`과 `ConstraintViolationException`을 잡아 `Refused an order confirmed event that cannot be processed. eventId=…, orderId=…, partition=…, offset=…, cause=<예외>` 한 줄을 남기고 다시 던진다. 둘은 6절의 운영 모양이 재시도하지 않는 것으로 등록할 타입이다. orderId는 record의 key에서 읽는다. 계약이 key를 주문 ID의 문자열로 정하고(주문 이벤트 설계 2절), 읽을 수 없는 payload와 그 schemaVersion에서 읽지 않는 payload에는 읽을 orderId가 없다. 충돌의 차이는 예외의 메시지에 있다. 그 밖의 예외(일시 장애, 둘째 `DataIntegrityViolationException`, 엔티티의 `IllegalArgumentException`)는 이 줄 없이 멈춘다. 읽을 수 없는 본문의 `MessageConversionException`도 그렇다. 변환기가 리스너 메서드를 부르기 전에 던지므로 리스너는 그 record를 보지 못한다. spring-kafka는 어느 멈춤에서든 컨테이너의 로거에 `Error handler threw an exception`을 ERROR로 따로 남긴다.) (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: 읽을 수 없는 본문의 타입은 `ConversionException`이다(위 역직렬화의 날짜 메모). 그 본문에 리스너의 줄이 없고 spring-kafka의 줄만 남는 것을 멈춤 장면이 본다.)
- **템플릿 정리.** `spring.application.name`은 `commerce-streamer`다. `modules:redis`와 그 testFixtures는 쓰는 곳이 없어 뺀다. 테스트 컨텍스트마다 Redis 컨테이너를 띄울 까닭이 없다. webmvc와 actuator는 health를 위해 둔다. `DemoKafkaConsumer`는 두되 `@KafkaListener(autoStartup = "\${demo-kafka.auto-startup:true}")`로 바꾸고 `test` 프로필이 false로 둔다. 빈은 있으나 컨테이너가 뜨지 않아 broker에 닿지 않는다.
- **stereotype.** commerce-api의 `support.stereotype.ApplicationService`와 `ValidatedApplicationService`를 streamer의 같은 패키지에 베낀다. KDoc 한 줄이 원본과 베낀 까닭을 적는다. 따로 배포하는 두 앱을 열 줄짜리 애노테이션의 공유 모듈로 묶지 않는다(ADR 0033 고르지 않은 것).

## 6. 실패와 멈춤

| record | 하는 것 |
| --- | --- |
| 모르는 `eventType` | 건너뛴다. 읽었다고 표시하고 INFO. |
| `OrderConfirmed`에 모르는 `schemaVersion` | `UnsupportedOrderEventException`. 멈춘다. |
| 읽을 수 없는 본문, 옮길 수 없는 payload | `MessageConversionException`, `MalformedOrderEventException`. 멈춘다. |
| 검증에 실패한 요청(빈 품목, 0 이하 수량, 겹친 상품) | `ConstraintViolationException`, 엔티티의 `IllegalArgumentException`. 멈춘다. |
| 충돌 | `PurchaseActivityConflictException`. 멈춘다. |
| 일시 장애(DB 연결, 잠금 대기) | 이번 주에는 멈춘다. handler가 가르지 않는다. 재시작이 낫게 한다. |

streamer가 던지는 셋(`Unsupported…`, `Malformed…`, `…Conflict…`)은 `support.error.UnprocessableOrderEventException`을 상속한다. 응용과 어댑터가 다 상속할 수 있는 자리다. 뒤에 `DefaultErrorHandler`로 바꿀 때 이 하나와 `ConstraintViolationException`을 재시도하지 않는 것으로 등록하면 된다. `MessageConversionException`은 그 handler의 기본값이 이미 그렇게 다룬다. (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: 위 표와 이 문장의 `MessageConversionException`은 spring-kafka의 `ConversionException`이다(5절 역직렬화의 날짜 메모). spring-kafka 4.1.1의 `ExceptionClassifier`가 기본으로 재시도하지 않는 목록에 `MessageConversionException`과 `ConversionException`이 둘 다 있으므로 이 문장의 결론은 그대로다.) (2026-10-29, [issue 187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187): 위 표의 "일시 장애(DB 연결, 잠금 대기)" 행을 3단계의 실패 실험 `OrderEventsListenerFailureTest`가 실제 장애로 봤다. 다른 연결이 커밋하지 않은 둘째 품목의 `purchase_activity` 행을 쥐면 리스너의 트랜잭션은 처리한 주문 확정과 첫 품목을 넣은 뒤 그 유일 키에서 [ADR 0018](../adr/0018-pessimistic-row-locks-in-one-global-order.md)의 3초를 기다리다 MySQL 1205로 실패한다. Spring이 번역한 타입은 `CannotAcquireLockException`이다. Hibernate 7.4가 1205를 `LockTimeoutException`으로 읽고 그것이 `LockAcquisitionException`의 하위이기 때문이다. `DataIntegrityViolationException`이 아니라 `recordSettlingARace`의 두 번째 호출을 타지 않는다. 트랜잭션 전체가 되돌아가 그 주문의 행은 두 표 모두 없고 커밋된 offset은 그 record에 머문다. 막던 연결을 되돌리고 `start()`하면 같은 record가 다시 전달돼 기록된다. 한 실행에서 넣은 뒤 3229 ms에 컨테이너가 서 있었고, 커밋된 offset은 1 → 1 → 2였다. 리스너는 이제 거절 줄의 두 타입 밖의 모든 예외에 ERROR 한 줄 `Could not record an order confirmed event; the consumer stops until it is restarted. eventId=…, orderId=…, partition=…, offset=…, cause=…`를 남기고 다시 던진다. 거절 줄처럼 orderId는 record의 key에서 읽고, 원인은 예외의 `toString`이다. `recordSettlingARace`의 두 번째 위반도 이 줄이고, 변환기의 `ConversionException`은 여전히 spring-kafka의 줄뿐이다. 리스너와 팩토리의 KDoc이 두 줄의 뜻을 적는다.)

**멈춤이 하는 일**(spring-kafka 4.1.1의 소스로 확인했다). 예외가 리스너 메서드를 나가면 consumer 스레드가 잡는다. `acknowledge()`를 부르지 않았으므로 그 record의 offset은 커밋되지 않고, 앞서 표시한 것은 먼저 커밋된다. `CommonContainerStoppingErrorHandler`는 `stopAbnormally`를 다른 스레드에 넘기고 최대 10 s 기다린 뒤 원인을 감싼 `KafkaException("Stopped container")`를 던진다. record 리스너의 error handler가 받는 컨테이너는 `thisOrParentContainer`, 곧 부모 `ConcurrentMessageListenerContainer`라 세 consumer가 모두 선다. `ConsumerStoppedEvent`의 reason은 `ABNORMAL`이다. 저절로 다시 시작하지 않는다. 프로세스를 다시 띄우거나 `KafkaListenerEndpointRegistry`에서 `order-events` 컨테이너를 `start()`하면 같은 record가 다시 와서 다시 선다. (2026-10-28, [티켓 181](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/181): 멈춤 장면이 이것을 본다. 처리할 수 없는 이벤트 넷마다 컨테이너가 서고 그 consumer가 모두 닫히며, `isInExpectedState`가 false이고(비정상 멈춤), group의 커밋된 offset은 그대로다. 충돌로 선 컨테이너를 `start()`하면 같은 partition과 offset의 ERROR 줄이 한 번 더 남고 다시 선다. 멈춤이 시작되면 부모 컨테이너의 `isRunning`이 곧바로 false가 되고, consumer는 그 뒤에 닫히며, 마지막 consumer가 닫힐 때 부모가 자식 컨테이너의 목록을 비운다(spring-kafka 4.1.1의 `childStopped`). 그래서 장면은 `isRunning`과 `isChildRunning`을 함께 기다린다.) (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: JSON이 아닌 값이 더해져 처리할 수 없는 이벤트는 다섯이고, 다시 시작하는 장면까지 멈춤 장면은 여섯이다. 그 장면도 컨테이너가 서고 consumer가 모두 닫히며 `isInExpectedState`가 false이고 커밋된 offset이 그대로다. 다른 점은 ERROR 줄이 리스너가 아니라 spring-kafka의 것 하나라는 것이다(7절의 날짜 메모).)

**다시 흐르게 하는 법.** 저장된 행을 고치거나 지운 뒤 다시 시작하거나, group의 offset을 그 record 다음으로 옮긴다. 이번 주에는 도구가 없다. ERROR 한 줄의 차이가 어느 쪽인지 고르는 근거다. (2026-10-29, [issue 187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187): 일시 장애는 원인이 사라진 뒤 다시 시작하는 것만으로 흐른다. 고칠 행도 옮길 offset도 없다. 거절 줄(`Refused an order confirmed event that cannot be processed`)과 실패 줄(`Could not record an order confirmed event`)의 문구가 그 둘을 가른다.)

**운영의 모양(잠정이라 적는다).** `DefaultErrorHandler`에 `UnprocessableOrderEventException`과 `ConstraintViolationException`을 재시도하지 않는 것으로 등록하고, `DeadLetterPublishingRecoverer`가 첫 실패에 `order-events.DLT`로 보내며 offset을 커밋한다. 일시 장애만 backoff로 재시도한다. DLT에 경보를 건다. 다른 파티션은 계속 흐르고, 충돌의 가시성은 DLT를 보는 사람에게 달린다. 멈춤은 record를 하나도 건너뛰지 않는 대신 하나가 전체를 세운다. 장부라면 멈춤을, 활동 피드라면 DLT를 고른다. 랭킹의 구매 활동은 둘째에 가깝다. 사용자가 이 대안을 따로 보고 있어 지금은 과제가 이름 부른 멈춤을 두고 바꿀 준비만 한다(ADR 0033 규칙 5).

## 7. 테스트 기반

- **기반 클래스.** streamer의 `support.test`에 둘이다(ADR 0012). commerce-api의 기반은 그 포트의 `prepare*`를 품어 나눠 쓸 수 없다.
  - `BaseApplicationServiceTest`: 전체 컨텍스트를 실제 MySQL·Kafka 위에 띄우고 테스트 트랜잭션으로 감싸 롤백한다. `MySqlTestContainersConfig`와 `KafkaTestContainersConfig`를 `@Import`한다. 기록하는 포트의 장면이 쓴다. (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 이 기반에 서는 클래스는 다섯이다. 포트의 `PurchaseActivityRecorderTest`, 저장소 테스트 둘, 설정을 읽는 `OrderEventsListenerConfigTest`와 `DemoKafkaConsumerTest`. `CommerceStreamerContextTest`는 기반을 상속하지 않고 같은 `@Import`를 달아 같은 캐시 컨텍스트를 쓴다. 이 컨텍스트의 리스너는 기본 토픽 `order-events`를 듣는데, 아무도 그 토픽을 만들지 않고 consumer의 자동 생성이 꺼져 있어 아무것도 읽지 않는다.)
  - `BaseConsumerTest`: 커밋한다. 리스너 스레드의 트랜잭션은 테스트 트랜잭션 밖이다. 클래스마다 `@TestPropertySource`로 `order-events.topic`을 덮고, `@TestConfiguration`이 그 이름의 `NewTopic`(파티션 3)을 선언한다. streamer는 토픽을 만들지 않으므로 테스트가 만든다. `@BeforeEach`에서 `ContainerTestUtils.waitForAssignment`로 배정을 기다리고, `@AfterEach`에서 `DatabaseCleanUp`으로 비운다. record는 `modules:kafka`의 `KafkaTemplate`으로 넣고 행은 Awaitility로 기다린다. (2026-10-28, [티켓 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180): 배정은 consumer 셋이 저마다 파티션을 쥘 때까지 기다린다. Testcontainers의 broker는 `group.initial.rebalance.delay.ms`가 0이라 첫 consumer 혼자 첫 세대를 이루고 세 파티션을 다 쥐었다. `waitForAssignment`는 파티션 수를 더해 세므로 그때 돌아왔고, 나머지 둘을 들이는 세대는 3 s쯤 뒤에 장면 안에서 왔다(로그). 멈춘 컨테이너는 곧바로 실패한다. 모든 컨텍스트의 리스너가 같은 group `commerce-streamer`에 드는데, Spring Framework 7이 쓰이지 않는 캐시된 컨텍스트를 멈추므로(`spring.test.context.cache.pause`) 그 consumer는 group을 떠나고 장면의 consumer만 남는다(로그의 `consumer pro-actively leaving the group`). record의 값은 바이트가 아니라 손으로 적은 JSON의 트리다. 템플릿의 `JacksonJsonSerializer`가 바이트 배열을 base64 문자열로 쓰기 때문이다. 장면은 행이 아니라 group의 커밋된 offset을 기다린 뒤 행을 읽는다. 리스너는 기록의 커밋 뒤에 표시하므로 그 뒤에 일어나는 일이 없다.) (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 토픽을 선언하는 것은 기반 안의 `@TestConfiguration`이 아니라 `com.loopers.support`의 최상위 클래스 `TestOrderEventsTopicConfig`이고, 기반이 `@Import`한다. 티켓 177이 중첩해 두자 Spring Framework 7이 테스트 계층의 중첩 설정 클래스를 기본 설정으로 본다고 경고했다(지금은 무시하고 Spring Framework 7.1부터 쓴다). 빈 `testOrderEventsTopic`이 `TopicBuilder`로 그 클래스의 `order-events.topic`을 파티션 3, 복제 1로 선언하고, 컨텍스트의 `KafkaAdmin`이 뜰 때 만든다. 토픽은 클래스마다 하나다. `OrderEventsListenerTest`의 `order-events-listener`, `OrderEventsListenerStopTest`의 `order-events-listener-stop`, `OrderEventConsumedCaseTest`의 `order-events-consumed-case`이고, 셋 다 저마다의 컨텍스트를 띄운다.)
- **테스트 리소스.** `junit-platform.properties`(생성자 주입, 확장 자동 감지)와 `instancio.properties`를 commerce-api에서 베낀다. Gradle은 모듈마다 테스트 classpath가 따로다.
- **장면.**
  - 포트: 새 요청은 두 표에 행을 남긴다. 같은 요청은 `ALREADY_RECORDED`이고 행이 늘지 않는다. 같은 eventId의 다른 수량, 다른 품목, 다른 confirmedAt, 같은 orderId의 다른 eventId는 충돌이다. 품목의 차례가 달라도 같은 사실이다. 빈 품목과 0 수량은 거절된다.
  - 저장소: 두 유일 제약과 CHECK가 이름 그대로 있고 어긴 저장은 실패한다. 삭제된 것은 없는 것이다. (2026-10-28, [티켓 178](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/178): 저장소 테스트는 `BaseApplicationServiceTest`를 상속한다. JPA 슬라이스의 기반을 두지 않았으므로 기반은 여전히 둘이고, 컨텍스트도 하나 더 뜨지 않는다. 표의 제약은 `information_schema.table_constraints`에서 모두 읽어, 외래 키가 없음도 함께 본다. CHECK는 엔티티가 먼저 거절하므로 엔티티를 거치지 않는 UPDATE로 MySQL의 3819를 본다.)
  - 리스너: 발행한 record가 처리한 주문 확정 하나와 상품 수만큼의 구매 활동이 된다. 같은 record를 두 번 넣으면 행은 한 벌이고 두 offset이 모두 커밋된다. 모르는 eventType은 건너뛰고 offset이 커밋된다. 충돌 record는 컨테이너를 세우고(`isRunning == false`), 행은 그대로이며, ERROR 한 줄이 남고, 그 offset은 커밋되지 않는다. (2026-10-28, [티켓 181](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/181): 멈추는 장면은 `OrderEventsListenerStopTest` 한 클래스에 있다. 충돌, `orderId`가 빠진 payload, schemaVersion 2, 빈 품목, 그리고 충돌로 선 컨테이너를 다시 시작하는 장면이다. 장면이 세운 컨테이너는 `BaseConsumerTest`의 `@AfterEach`가 토픽을 비운 뒤(`deleteRecords`) 다시 시작한다. 그 record는 커밋되지 않았으므로 비우지 않고 다시 시작하면 다음 장면 전에 다시 선다(변이 탐침). 그래서 장면마다 컨텍스트를 띄우지 않는다. 넣고 기다리고 읽는 도우미는 세 클래스가 쓰게 되어 기반과 `com.loopers.support`로 옮겼다. `CODING_STANDARDS.md`의 멈춤 장면과 정리.) (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 멈춤은 `isRunning == false` 하나로 보지 않았다. 부모 컨테이너는 멈춤이 시작되자마자 `isRunning`이 false가 되고 consumer는 그 뒤에 닫히므로(6절의 티켓 181 메모), 기반의 `awaitStopped`가 `isRunning`과 `isChildRunning`이 모두 false가 되기를 기다린다. 장면은 그 뒤 `isInExpectedState`가 false임을 본다. 비정상 멈춤 뒤에만 false인 값이고, `ConsumerStoppedEvent`의 `ABNORMAL`과 같은 깃발을 읽는다. 커밋되지 않은 offset은 두 꼴로 본다. 충돌과 재시작의 장면은 그 파티션의 커밋된 offset이 충돌한 record의 offset 그대로임을, 나머지 셋은 group의 커밋된 offset들이 record를 넣기 전에 읽은 것과 같음을 본다.) (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: JSON이 아닌 값의 장면을 더했다([`dc6d03c`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/dc6d03c)). 공유 템플릿은 바이트 배열을 base64 문자열로 쓰므로, 그 클래스의 `publishBytes`가 `ByteArraySerializer`를 준 `KafkaProducer`로 `not json`을 넣는다. 컨테이너가 서고, 커밋된 offset과 두 표는 그대로이며, 리스너의 ERROR 줄은 없다. 그 클래스가 `KafkaMessageListenerContainer`의 로거에 붙인 appender가 `Error handler threw an exception` 한 줄을 읽고, 그 원인 사슬은 `KafkaException` → `ListenerExecutionFailedException` → `ConversionException` → Jackson의 `StreamReadException`이다. 사슬을 `MessageConversionException`으로 단언한 변이는 실패했다. 검토는 `isInExpectedState`도 다시 봤다. spring-kafka 4.1.1의 부모 컨테이너는 다시 시작할 때 `stoppedNormally`를 되돌리지 않지만, 멈출 때마다 `doStop`이 그 값을 정상이면 true로, 아니면 false로 다시 쓴다(`ConcurrentMessageListenerContainer` 380행). 그래서 한 컨텍스트에서 앞 장면이 비정상으로 세운 뒤에도 각 장면의 단언은 그 장면의 멈춤을 읽는다.) (2026-10-29, [티켓 188](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/188): "같은 record를 두 번 넣으면"은 같은 이벤트를 두 번 발행하는 장면이다. 둘째는 다음 offset의 새 record이므로 다시 발행이지 다시 전달이 아니다(streamer의 `CONTEXT.md`). 테스트 이름도 `the same event published twice leaves one set of rows and commits both offsets`로 바꿨다. 같은 record가 같은 파티션과 offset으로 다시 오는 다시 전달은 `RedeliveryAfterCommitTest`가 본다.)
  - 설정: 리스너 컨테이너의 consumer 설정에 `enable.auto.commit=false`가 있다. 팩토리의 ack 모드는 `MANUAL_IMMEDIATE`다. (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: `OrderEventsListenerConfigTest`가 읽는 것은 뜬 컨테이너의 consumer가 아니라 팩토리의 consumer 설정(`consumerFactory.configurationProperties`)이다. 티켓 177의 완료 조건이 팩토리를 읽으라고 적었고, 뜬 `KafkaConsumer`는 설정을 공개 API로 내보이지 않는다. 컨테이너가 팩토리의 설정을 바꾸는 길은 `@KafkaListener`의 `properties`와 group인데, 리스너는 `properties`를 쓰지 않고, group은 소비 장면이 group `commerce-streamer`의 커밋을 기다리며 본다.)
  - 컨텍스트: 두 fixture를 `@Import`한 컨텍스트가 뜬다. 빠뜨리면 commerce-api처럼 뜨지 않는다. (2026-10-28, [티켓 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177): streamer의 컨텍스트는 Kafka 설정 없이도 뜬다. `KafkaAdmin`은 `NewTopic` 빈이 있을 때만 admin을 만드는데 streamer는 토픽을 선언하지 않고, test에서는 데모 컨테이너도 뜨지 않는다. 뜨지 않는 것은 테스트가 토픽을 선언한 `BaseConsumerTest`의 컨텍스트다(`Could not create admin` ← `Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`). 컨텍스트와 함께 뜨는 `order-events` 리스너가 생기면 리스너 티켓이 다시 확인한다.) (2026-10-28, [티켓 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180): 다시 확인했다. Kafka 설정을 뺀 streamer 컨텍스트는 이제 뜨지 않는다. `internalKafkaListenerEndpointRegistry`를 시작하지 못하고, 그 원인은 `Failed to construct kafka consumer` ← `Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`다(탐침). 컨텍스트와 함께 뜨는 리스너 컨테이너가 consumer를 만들다 실패한다. `KafkaAdmin`의 `fail-fast`를 거치지 않는다.)
- **의존.** `testImplementation(testFixtures(project(":modules:kafka")))`, `testImplementation(libs.springBootStarterKafkaTest)`(`ContainerTestUtils`만). `@EmbeddedKafka`는 쓰지 않는다. (2026-10-28, [티켓 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180): `ContainerTestUtils`는 더 쓰지 않는다. 배정은 기반이 Awaitility로 기다린다(위의 `BaseConsumerTest`). 이 의존은 소비 장면이 group의 커밋된 offset을 읽는 `KafkaTestUtils.getCurrentOffset`을 위해 남는다.)

## 8. 7주차에 넘기는 소비 사례

과제는 Outbox의 출발점으로 두 사례를 남기라 한다. 미발행 사례는 1단계의 `OrderEventPublishGapTest`가 남겼다. 정상 발행·소비 사례는 이 단계의 리스너 테스트가 남긴다. 테스트는 1단계가 기록한 미발행 사례와 같은 값의 record를 넣는다. orderId 1, eventId `order-confirmed:v1:1`, confirmedAt `2026-10-27T01:02:03.123456Z`, items `[{productId 1, quantity 2}, {productId 2, quantity 1}]`. 실행의 topic, partition, offset, 두 표의 행을 `docs/research/week6-purchase-activity-writing-material.md`에 적는다. 커머스 쪽의 PG 주문 번호와 거래 키는 1단계의 실행에서 가져오므로 두 실행을 이은 기록이고, 그렇게 적는다. 두 앱을 compose 위에서 손으로 돌리는 한 번의 실행은 재현 코드가 아니라 두지 않는다. (2026-10-28, [티켓 181](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/181): 장면은 `OrderEventConsumedCaseTest`다. 클래스와 토픽(`order-events-consumed-case`)을 따로 두어 record가 토픽의 첫 record, offset 0이다. 값은 commerce-api의 `OrderEventPublishingTest`가 같은 주문과 시각으로 고정한 꼴이다. 한 실행의 값은 [글감](../research/week6-purchase-activity-writing-material.md#w7-handoff-consumed-case)의 "W7 handoff: consumed case"에 있다.)

## 9. 받아들인 한계

- 충돌 하나가 세 파티션의 소비를 세운다. 도구 없이 사람이 푼다. 6절.
- 일시 장애도 세운다. (2026-10-29, [issue 187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187): 실패 실험이 봤다. 한 파티션의 3초 잠금 대기 하나가 세 파티션을 모두 세우고, 원인이 사라져도 저절로 다시 시작하지 않는다. 사람이 컨테이너를 `start()`하거나 프로세스를 다시 띄워야 하고, 그때까지 모든 파티션이 서 있다. 운영의 모양(6절)은 일시 장애만 backoff로 재시도한다.)
- 두 앱이 한 DB를 나눠 쓴다. 표는 서로 건드리지 않지만 같은 인스턴스다.
- 처리한 주문 확정에 보관 기한이 없다.
- 소비 사례의 기록은 두 실행을 이은 것이다. 8절.
- 지표가 없다. 로그 줄뿐이다.

## 10. 과제의 이름과 이 저장소의 이름

| 과제 | 이 저장소 | 까닭 |
| --- | --- | --- |
| 상품 구매 활동 | 구매 활동(`PurchaseActivity`, `purchase_activity`) | 맥락의 이름이 상품 구매 활동이라 "상품"이 겹친다. |
| processed(처리한 이벤트의 기록) | 처리한 주문 확정(`ProcessedOrderConfirmation`, `processed_order_confirmation`) | 1단계의 인터뷰에서 과제의 이름을 골랐다. 이벤트 종류마다 하나다. |
| 중복·재전달 | 같은 사실 | 재전달은 전달의 말이고, 가르는 것은 사실이다. (2026-10-29, [명세 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184)의 검토: 전달의 말은 다시 발행하다와 다시 전달되다로 가르고, 둘 다 같은 사실로 온다. [Kafka 전달 설계](./kafka-delivery.md) 6절.) |
| 오류(같은 eventId의 다른 내용) | 충돌 | 커머스 `CONTEXT.md`의 이벤트 ID 항목과 같은 말. |
| 중단형 error handler | 멈추다 | `CommonContainerStoppingErrorHandler`. |
| ack | 읽었다고 표시하다 | `Acknowledgment.acknowledge()`. |
| record 리스너 팩토리 | `OrderEventsListenerConfig`의 `ORDER_EVENTS_LISTENER`(빈 `orderEventsListenerContainerFactory`) | (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 티켓 177이 만들었다. 템플릿의 batch 팩토리 `BATCH_LISTENER_DEFAULT`와 나란히 있고, 리스너가 `containerFactory`로 이름 부른다(5절).) |
| 정상 발행·소비 사례 | 소비 사례(consumed case), `OrderEventConsumedCaseTest` | (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 티켓 181이 만들었다. 1단계의 미발행 사례(unpublished case)와 짝이다(8절).) |

## 11. 2단계의 명령

- `./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check :modules:kafka:ktlintCheck`. Docker가 있어야 한다. (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 수를 읽는 실행은 아래 블록처럼 앞에 `cleanTest`를 둔다.)
- commerce-api의 `check`는 `kafka.yml`의 오타 고침과 stereotype 베낌에 영향받지 않아야 하므로 마지막 티켓이 한 번 돌린다.
- 테스트 수와 클래스 수는 마지막 티켓이 적는다. (2026-10-28, [티켓 182](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/182): 아래에 적었다.)

```bash
# 기록하는 포트(명세 176의 이음매 1): PurchaseActivityRecorderTest(12). 롤백하는 기반이다.
./gradlew :apps:commerce-streamer:test --tests '*PurchaseActivityRecorderTest'

# broker에서 리스너까지(이음매 2): OrderEventsListenerTest(6), OrderEventsListenerStopTest(6). 클래스마다 토픽과 컨텍스트가 따로다.
./gradlew :apps:commerce-streamer:test --tests '*OrderEventsListenerTest' --tests '*OrderEventsListenerStopTest'

# 7주차에 넘기는 소비 사례(8절): OrderEventConsumedCaseTest(1). 실행 값을 INFO 한 줄로 남긴다.
./gradlew :apps:commerce-streamer:test --tests '*OrderEventConsumedCaseTest'

# 설정과 컨텍스트: OrderEventsListenerConfigTest(3), DemoKafkaConsumerTest(1), CommerceStreamerContextTest(1)
./gradlew :apps:commerce-streamer:test --tests '*OrderEventsListenerConfigTest' --tests '*DemoKafkaConsumerTest' --tests '*CommerceStreamerContextTest'

# 저장소: ProcessedOrderConfirmationRepositoryTest(6), PurchaseActivityRepositoryTest(7)
./gradlew :apps:commerce-streamer:test --tests '*RepositoryTest'

# 도메인 단위와 fixture의 계약: PurchaseActivityTest(4), ProcessedOrderConfirmationTest(1), PurchaseActivityFixturesTest(5), ProcessedOrderConfirmationFixturesTest(3). 컨텍스트를 띄우지 않는다.
./gradlew :apps:commerce-streamer:test --tests 'com.loopers.domain.*'

# 끝의 확인. 수는 cleanTest부터 돈 실행의 JUnit XML 보고서에서 읽는다.
./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check :modules:kafka:ktlintCheck
scripts/test-counts --results apps/commerce-streamer/build/test-results/test

# commerce-api의 check. kafka.yml의 고침이 그 앱에도 닿는다. cleanTest가 있어야 테스트가 UP-TO-DATE로 지나가지 않고 실제로 돈다.
./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:check
scripts/test-counts
```

- 괄호의 수는 2026-10-28 `8313f00`의 트리에서 끝의 확인을 돌려 읽은 것이다. streamer는 13개 클래스, 55개 테스트, 실패 0, 건너뜀 0이다(33 s). commerce-api는 79개 클래스, 943개 테스트, 실패 0, 건너뜀 0으로 1단계의 끝과 같다(3 min 39 s). 1단계의 끝은 PR 174의 head `f792658`이고, `week6`에는 `b84646d`로 합쳐졌다. 셸에 `BOOTSTRAP_SERVERS`는 없었다. 거른 명령들도 같은 날 돌려 같은 수를 확인했다. 여섯 필터가 열세 클래스를 한 번씩 덮는다. `8313f00` 뒤로는 문서만 바뀌었다. (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: 닫는 검토가 `OrderEventsListenerStopTest`에 장면 하나를 더해 위 블록의 그 수를 6으로 고쳤다. [`dc6d03c`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/dc6d03c)에서 streamer의 끝의 확인을 `cleanTest`부터 다시 돌려 13개 클래스, 56개 테스트, 실패 0, 건너뜀 0을 읽었다(39 s). `:modules:kafka:ktlintCheck`도 통과했다. commerce-api와 `modules`의 코드는 `8313f00` 뒤로 바뀌지 않아 commerce-api의 `check`는 다시 돌리지 않았다.)
- 2단계가 더한 테스트는 streamer의 55개 전부다. 이 브랜치 전에는 streamer에 테스트가 없었다. 티켓마다 177이 5개, 178이 23개, 179가 15개, 180이 6개, 181이 6개다. (2026-10-28, [명세 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: 닫는 검토가 1개를 더해 56개다.)
- 거른 실행은 보고서 디렉터리를 그 실행의 것으로 갈아 치운다. 수는 끝의 확인 바로 뒤에 읽는다.
- streamer에는 `HexagonalArchitectureTest`가 없다. 거른 실행의 수는 고른 클래스의 것뿐이고, 아무것도 찾지 못한 필터는 `No tests found for given includes`로 실패한다. commerce-api의 필터는 그 클래스가 늘 함께 돌아 아무것도 찾지 못해도 실패하지 않는다([주문 이벤트 설계](./order-events.md)의 "1단계의 명령").
- Spring 컨텍스트는 넷이다. 롤백하는 기반과 `CommerceStreamerContextTest`가 나눠 쓰는 하나와, 커밋하는 기반의 클래스마다 하나씩 셋이다. 컨텍스트를 띄우는 명령은 Docker가 있어야 하고, MySQL과 Kafka 컨테이너를 JVM마다 하나씩 띄운다. Redis는 띄우지 않는다. 도메인의 네 클래스는 컨텍스트를 띄우지 않는다.
- `apache/kafka-native:4.3.2` 컨테이너가 한 번씩 시작하지 못했다(`Container exited with code 1`, 또는 `Transitioning from RECOVERY to RUNNING`을 기다리다 시간이 다 됨). 티켓 178, 180, 181에서 한 번씩이고, 바꾼 것 없이 다시 돌리자 지나갔다.
