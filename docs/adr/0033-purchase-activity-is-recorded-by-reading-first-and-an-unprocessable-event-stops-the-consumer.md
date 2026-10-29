---
status: accepted
date: 2026-10-28
---

# 구매 활동은 먼저 읽고 한 트랜잭션에 기록하며, 처리할 수 없는 이벤트는 소비를 멈춘다

6주차 과제의 소비 부분은 commerce-streamer가 `order-events`의 `OrderConfirmed`를 상품 구매 활동으로 저장하라고 한다. 처리한 이벤트의 기록과 상품별 구매 행을 DB 유일 제약 아래 한 트랜잭션에 쓰고, 다시 온 같은 사실은 no-op, 같은 eventId의 다른 사실은 오류로 다루며, 별도의 record 리스너 팩토리에 `MANUAL_IMMEDIATE`와 중단형 error handler를 두고, 실제 consumer 설정의 auto-commit이 꺼져 있음을 확인하라고 한다. 계약은 [주문 이벤트 설계](../design/order-events.md) 2절이고, [ADR 0032](./0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md)가 발행 쪽을 정했다. 이 맥락의 용어는 [`apps/commerce-streamer/CONTEXT.md`](../../apps/commerce-streamer/CONTEXT.md)에 있다.

1단계의 인터뷰는 소비 쪽을 "유일 제약이 중복을 막고, 위반 뒤 별도 TX에서 같은 사실인지 다시 본다"로 넘겼다(설계 10절). 2단계의 인터뷰에서 그 차례가 거꾸로임을 봤다. 흔한 경로인 다시 온 같은 사실이 예외 경로가 되고, 위반 뒤의 비교가 두 번째 트랜잭션을 강제한다.

1. **구매 활동은 커머스가 아니라 streamer가 이벤트에서 만든다.** commerce-api가 TX B에서 구매 활동 표를 함께 쓰면 랭킹의 쓰기가 결제 확정의 트랜잭션에 들어오고, 9주차의 집계가 바뀔 때마다 결제 코드가 바뀐다. streamer가 커머스의 `orders`·`order_line_item`을 읽으면 그 표의 스키마가 두 앱의 계약이 되고, 확정 시점을 알려면 폴링이나 CDC가 필요하다. 이벤트는 계약을 봉투 하나로 좁히고, streamer가 멈춰도 커머스는 모른다. 그래서 이 맥락은 주문과 상품을 식별자로만 알고, 커머스의 표로 가는 외래 키를 두지 않는다.
2. **루트는 둘이다.** `ProcessedOrderConfirmation`(eventId 유일, orderId 유일, confirmedAt)과 `PurchaseActivity`(orderId·productId 유일, quantity 양수, confirmedAt)는 저마다 애그리거트 루트이고 저장소 포트를 따로 가진다. 처리한 주문 확정은 중복을 가르는 기록이고, 구매 활동은 9주차가 읽는 사실이다. 보관 기한과 읽는 쪽이 다르다. 하나의 애그리거트로 묶으면 같은 주문을 건드리는 둘째 이벤트의 기록이 남의 자식 행을 가져야 한다. 둘을 한 트랜잭션에 쓰는 것은 JPA의 cascade가 아니라 응용 서비스가 보장한다.
3. **먼저 읽고 쓴다.** provided 포트 `PurchaseActivityRecorder.record(request)`는 `@Transactional` 하나다. eventId로 처리한 주문 확정을 읽고, 없으면 orderId로 읽는다. 둘 다 없으면 둘을 저장한다. eventId로 찾았으면 orderId, 시각으로 읽은 confirmedAt, 상품별 수량의 집합을 견줘 같으면 아무것도 쓰지 않고(`ALREADY_RECORDED`), 다르면 `PurchaseActivityConflictException`이다. orderId로만 찾았으면 다른 eventId이므로 충돌이다. 유일 제약은 기제가 아니라 뒷받침이다. 같은 eventId를 두 consumer가 같은 순간 다루는 일은 key가 orderId라 rebalance를 건널 때만 생기고, 그때 진 쪽의 `DataIntegrityViolationException`은 리스너 어댑터가 같은 호출을 한 번 다시 해 읽는 경로로 푼다. (2026-10-28, [issue 179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179)와 [issue 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180): 두 곳이 정확하지 않았다. 포트는 인터페이스라 `@Transactional`이 없다. 트랜잭션 하나는 구현 `PurchaseActivityModifyService`(ADR 0013의 `<X>ModifyService`)의 `@ValidatedApplicationService`가 건다. 견주는 confirmedAt은 시각으로 읽은 값 그대로가 아니라 엔티티가 마이크로초로 자른 값이다. MySQL은 일곱째 자리를 버리지 않고 반올림하므로([issue 178](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/178)의 탐침) 엔티티가 자른다. 요청의 시각을 그대로 견주는 변이에서는 일곱째 자리가 있는 같은 사실이 저장된 자기와 충돌했다. 한 번 더 부르는 것은 리스너의 `recordSettlingARace`이고, 두 번째 위반은 컨테이너를 세운다. 이 경로는 issue 180의 탐침으로만 봤고 남은 테스트는 없다.)
4. **같은 사실과 충돌을 가른다.** 운영의 inbox 구현은 대개 메시지 ID를 보면 그냥 건너뛴다. 과제와 용어집은 같은 eventId의 다른 내용을 다시 온 이벤트가 아니라 충돌이라 하므로 견준다. 그 값은 발행 쪽의 버그로 바뀐 이벤트가 소리 없이 읽었다고 표시되지 않는 것이고, 비용은 어차피 하는 읽기 한 번의 비교다.
5. **처리할 수 없는 이벤트는 멈춘다(잠정).** 리스너 팩토리는 streamer의 것이고 `MANUAL_IMMEDIATE`, `CommonContainerStoppingErrorHandler`, concurrency 3이다. 충돌, 모르는 schemaVersion, 읽을 수 없거나 검증에 실패한 payload는 모두 다시 전달돼도 결과가 같으므로 재시도하지 않고, 리스너 밖으로 던져 컨테이너를 세운다. 모르는 eventType은 오류가 아니라 건너뛴다. spring-kafka 4.1.1은 record 리스너의 error handler에 부모 `ConcurrentMessageListenerContainer`를 넘기므로 세 consumer가 모두 선다. 그 record의 offset은 커밋되지 않고, 저절로 다시 시작하지 않으며, 다시 시작하면 같은 record부터 읽어 다시 선다. 과제가 이름 부른 handler이고 3단계의 실패·재시작 실험이 이 멈춤에 기댄다. 운영의 모양은 `DefaultErrorHandler`에 처리할 수 없는 예외를 재시도하지 않는 것으로 등록하고 `DeadLetterPublishingRecoverer`로 `order-events.DLT`에 보내며 일시 장애만 backoff로 재시도하는 것이다. 바꾸기 쉽도록 streamer가 던지는 예외는 `UnprocessableOrderEventException` 하나를 상속한다. (2026-10-28, [issue 181](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/181): "리스너 밖으로 던져"와 "하나를 상속한다"는 정확하지 않았다. 봉투로 읽을 수 없는 본문은 변환기가 리스너 메서드를 부르기 전에 `MessageConversionException`으로 실패해, 리스너는 그 record를 보지 못한다. 컨테이너는 같이 서지만 spring-kafka 4.1.1의 소스로 본 것이고 장면은 없다. 검증에 실패한 요청은 Spring의 메서드 검증이 던지는 `ConstraintViolationException`이라 표지 타입을 상속하지 않는다. 그래서 리스너는 표지 타입과 `ConstraintViolationException` 둘을 잡아 ERROR 한 줄(eventId, record의 key로 읽은 orderId, partition, offset, 원인)을 남기고 다시 던진다. 운영의 모양이 재시도하지 않는 것으로 등록할 것도 이 둘이다([구매 활동 설계](../design/purchase-activity.md)의 "실패와 멈춤"). 엔티티의 `IllegalArgumentException`은 둘 밖이다. 요청의 검증이 먼저 거절하므로 검증을 끈 변이에서만 닿았다([issue 179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179)). 세 consumer가 모두 서고, 다시 시작하면 같은 offset에서 다시 서는 것은 멈춤 장면이 본다.) (2026-10-28, [issue 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176)의 닫는 검토: 위 메모의 두 곳을 고친다. 봉투로 읽을 수 없는 본문의 예외는 `MessageConversionException`이 아니라 spring-kafka의 `ConversionException`이고, 컨테이너가 그것을 `ListenerExecutionFailedException`으로 감싸 error handler에 넘긴다. 장면도 이제 있다. JSON이 아닌 값이 컨테이너를 세우고 offset을 커밋하지 않으며, 리스너의 줄 없이 spring-kafka의 `Error handler threw an exception` 한 줄이 그 사슬을 원인으로 적는다([`dc6d03c`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/dc6d03c)). `ConversionException`도 `DefaultErrorHandler`가 기본으로 재시도하지 않는 타입이라 운영의 모양은 그대로다.) (2026-10-29, [issue 187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187): 일시 장애도 같은 handler가 세우고, 3단계의 실패 실험 `OrderEventsListenerFailureTest`가 그것을 실제 장애로 봤다. 테스트의 DataSource에서 따로 얻은 연결이 커밋하지 않은 `purchase_activity` 행을 쥔 채로 두 품목의 이벤트를 넣으면, 리스너의 트랜잭션은 처리한 주문 확정과 첫 품목을 넣은 뒤 둘째 품목의 유일 키를 기다리다 [ADR 0018](./0018-pessimistic-row-locks-in-one-global-order.md)의 `innodb_lock_wait_timeout=3`에 MySQL 1205로 실패한다. Hibernate 7.4는 1205를 `LockTimeoutException`(`LockAcquisitionException`의 하위)으로 읽고, Spring 7.0은 그것을 `CannotAcquireLockException`으로 번역한다. `DataIntegrityViolationException`이 아니므로 리스너의 `recordSettlingARace`는 다시 부르지 않는다. 트랜잭션 전체가 되돌아가 두 표 모두 그 주문의 행이 없고, 커밋된 offset은 그 record의 n에 머물며, 세 consumer가 선다. 막던 연결을 되돌리고 `start()`하면 같은 record가 다시 전달돼 기록되고 offset이 n+1이 된다. 처리할 수 없는 이벤트와 다른 점은 원인이 사라진 뒤의 다시 시작이 지나간다는 것뿐이다. 그래서 리스너는 거절 줄의 두 타입 밖의 모든 예외에 ERROR 한 줄 `Could not record an order confirmed event; the consumer stops until it is restarted. eventId=…, orderId=…, partition=…, offset=…, cause=…`를 남기고 다시 던진다. `recordSettlingARace`의 두 번째 위반도 이 줄이다. 변환기의 `ConversionException`은 리스너에 닿지 않아 여전히 spring-kafka의 줄뿐이다. 두 줄의 문구가 다시 시작해도 되는지를 가른다.)
6. **표시는 기록의 커밋 뒤다.** 리스너는 기록이 돌아온 뒤에만 `acknowledge()`를 부른다. 기록과 표시 사이에 멈추면 같은 record가 다시 오고 규칙 3이 no-op으로 푼다. 새 group은 `earliest`에서 시작한다. 기록하는 consumer는 멱등하므로 되읽기가 안전하고, 발행 쪽보다 늦게 배포된 streamer가 그 사이의 확정을 건너뛰지 않는다.
7. **설정의 경계.** `modules:kafka`는 연결과 직렬화(주소, 역직렬화 클래스, 템플릿, 변환기)를 갖고, 리스너의 약속(ack 모드, error handler, concurrency, group, offset reset)은 앱이 갖는다. commerce-api의 토픽 빈과 실행기가 이미 앱에 있는 것과 같은 선이다. `kafka.yml`의 `value-serializer` 오타는 역직렬화의 것이라 모듈에서 고친다. (2026-10-28, [issue 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177)과 [issue 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180): 경계의 두 곳이 정확하지 않았다. 값 역직렬화 클래스는 모듈만의 것이 아니다. streamer의 팩토리가 변환기가 기대는 `ByteArrayDeserializer`를 스스로 두므로, 모듈의 오타 고침이 닿는 것은 템플릿의 팩토리(`consumerFactory`, `BATCH_LISTENER_DEFAULT`)뿐이다. issue 177의 탐침에서 템플릿의 `consumerFactory`는 고치기 전에 `StringDeserializer`로, 고친 뒤에 `ByteArrayDeserializer`로 읽었다. 팩토리는 `enable.auto.commit=false`도 스스로 둔다. `kafka.yml`의 `enable-auto-commit`은 Kafka가 모르는 철자라 쓰이지 않는다. group은 `application.yml`만으로 정해지지 않는다. `@KafkaListener`의 `idIsGroup`이 기본으로 리스너 `id`를 group으로 쓰므로, 리스너가 `idIsGroup = false`를 두어야 `commerce-streamer`에 든다. issue 177의 탐침에서 `id = "probe"`인 리스너는 group `probe`에 들었다. group과 offset reset은 `kafka.yml`을 가져오는 문서 아래의 별도 문서에 있다. 가져온 파일이 가져오는 문서를 덮기 때문이다.)

## 고르지 않은 것

- **유일 제약이 중복을 가르고 위반 뒤 별도 TX에서 비교한다.** 1단계가 넘긴 모양. 같은 사실이 다시 올 때마다 예외와 롤백이 생기고, 위반 뒤의 세션은 쓸 수 없어 두 번째 트랜잭션과 steps 빈이 필요하다.
- **처리한 주문 확정이 구매 활동을 자식으로 갖는 한 애그리거트.** 규칙 2.
- **메시지 ID만 보고 건너뛰기.** 규칙 4.
- **지금 `DefaultErrorHandler`와 DLT.** 과제가 이름 부른 것은 중단형 handler이고 3단계 실험이 멈춤을 쓴다. 규칙 5의 운영 모양으로 남긴다.
- **재시도 뒤 멈추는 handler.** 일시 장애에는 맞지만 처리할 수 없는 이벤트에는 재시도가 뜻이 없다.
- **stereotype의 공유 모듈.** 두 앱이 쓰는 `@ApplicationService` 둘은 streamer에 베낀다. 따로 배포하는 앱이 공유 라이브러리로 묶이지 않게 하고, 열 줄짜리 애노테이션의 어긋남은 작다.
- **커머스의 표로 가는 외래 키.** 규칙 1.

## 대가

- 충돌 하나가 세 파티션의 소비를 모두 세운다. 사람이 저장된 행을 고치거나 record를 지나쳐야 다시 흐른다. 이번 주에는 그 도구가 없다.
- 일시 장애(DB 연결 끊김)도 멈춘다. 재시작이 낫게 한다.
- record마다 동기 offset 커밋 한 번이 든다. record 단위의 정확함의 값이다.
- 같은 이벤트 ID의 비교는 운영의 흔한 관행보다 한 걸음 더 간다.
- 두 루트를 한 트랜잭션에 쓰는 약속이 애그리거트가 아니라 서비스에 있다.
- `earliest`는 새 group이 보관된 역사를 모두 되읽게 한다. 멱등하므로 맞지만 시간이 든다.

## 다시 볼 조건

- **멈춤 정책.** 사용자가 poison record의 대안을 따로 보고 있다. `DefaultErrorHandler` + 재시도 안 함 + DLT로 바꾸면 팩토리 하나와 토픽 하나가 바뀐다. 규칙 5의 예외 분류가 그 준비다.
- 둘째 이벤트 종류가 오면 처리한 주문 확정을 일반 `processed_event`로 넓힐지 본다.
- 처리한 주문 확정의 보관 기한이 필요해지면 그때 둔다.
- 앱이 저장소를 나누면 베낀 stereotype은 각자의 것이 된다.

## 바꾸는 결정

[주문 이벤트 설계](../design/order-events.md) 10절의 "위반 뒤 별도 TX에서 같은 사실인지 다시 본다"에 날짜 메모를 달아 규칙 3으로 바꾼다. ADR 0032는 그대로다. `CONTEXT.md`의 주문 확정 이벤트가 "다른 맥락에 전하는" 메시지라는 말이 글자 그대로가 되어, 저장소에 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)가 생겼다.
