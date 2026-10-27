# 주문 이벤트 설계 — 주문 확정 이벤트의 발행

6주차 과제의 설계 문서다. 용어는 [`CONTEXT.md`](../../CONTEXT.md)의 `### 이벤트`와 발행하다를 따른다. 결정은 [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md)에 있다.

상태: 2026-10-27 발행 쪽(1단계, `feat/order-events`)을 인터뷰하고 썼다. 소비 쪽(2단계, `feat/purchase-activity`)과 전달 실험(3단계, `feat/kafka-delivery`)은 저마다의 인터뷰 뒤에 절을 더한다. 이 문서의 계약(2절)이 그 둘의 입력이다.

## 1. 근거와 범위

- 과제: [6주차 실습 · Kafka 설정 변화로 전달 상태 설명하기](https://app.notion.com/p/6-Kafka-4025f76d2654800da946cb2b4ad1bc88). 1단계의 근거는 세 절이다.
  - 1절: 업무 연결과 이벤트 계약(topic, eventType/schemaVersion, key, eventId, orderId/confirmedAt, items), 비밀·개인정보 금지, 공개 주입 endpoint 금지.
  - 2절: 처음 CONFIRMED로 반영한 커밋 뒤에만 발행, after-commit listener 또는 커밋 뒤 orchestration, send 결과의 관찰, 실패해도 주문을 되돌리지 않음, 누락 구간의 재현, 7주차에 넘기는 두 사례.
  - 7절과 체크리스트: 대기·UNKNOWN·실패·롤백에 발행 없음, 중복 callback/reconcile에 새 논리 이벤트 없음, 커밋 후 발행 공백의 기록.
- 5주차의 결과에서 시작한다. 새 결제나 상품 CRUD는 없다. 랭킹 API와 Outbox는 이번 주에 만들지 않는다.

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 주문 확정 이벤트의 발행. `modules:kafka` 의존, Kafka 테스트 컨테이너, 포트와 어댑터, 토픽, 시계 빈, 누락 구간의 재현. | `feat/order-events` | 인터뷰 끝남(2026-10-27) |
| 2 | commerce-streamer의 소비. `processed_order_confirmation`과 상품별 구매 행, record 리스너 팩토리(`MANUAL_IMMEDIATE`, 중단형 error handler), 중복과 충돌, 실패 경로. | `feat/purchase-activity` | 다음 인터뷰 |
| 3 | 전달 실험. 파티션 2개와 consumer 1→2→3, key→partition, DB 커밋 뒤 offset 미커밋 재전달, 실제 리스너의 실패·재시작, 7주차에 넘기는 두 사례의 정리. | `feat/kafka-delivery` | 다음 인터뷰 |

셋은 모두 `week6`로 PR한다. 기술 글은 `week6`에 둔다.

## 2. 계약

| 항목 | 이 저장소 |
| --- | --- |
| topic | `order-events`. 테스트는 클래스마다 고유 suffix로 격리한다. |
| key | 주문 식별자(`orderId`)의 문자열. 같은 주문은 같은 key다. |
| eventId | `order-confirmed:v1:{orderId}`. 저장하지 않고 유도한다. 재발행·재전달에도 같다. |
| eventType / schemaVersion | `OrderConfirmed` / `1`. |
| orderId / confirmedAt | 확정된 주문의 식별자 / 처음 확정된 시각(`Order.confirmedAt`, 마이크로초, ISO-8601 UTC 문자열). |
| items | 상품 ID 오름차순의 `{productId, quantity}`. 주문은 상품별 품목이 하나씩이라 중복이 없고 수량은 양수다. |

메시지는 봉투 꼴이다. 세 메타데이터가 밖에, 업무 필드가 `payload` 안에 있다. `order-events`는 여러 종류의 이벤트를 실을 이름이라 봉투를 골랐다.

```json
{
  "eventId": "order-confirmed:v1:42",
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "payload": {
    "orderId": 42,
    "confirmedAt": "2026-10-27T03:14:15.123456Z",
    "items": [
      { "productId": 7, "quantity": 2 },
      { "productId": 8, "quantity": 1 }
    ]
  }
}
```

- 속성의 차례와 `items`의 차례가 바뀌어도 새 사실이 아니다. 견줄 때는 고정 필드와 상품별로 정규화한 값을 쓴다. 지문(fingerprint)은 저장하지 않는다. 소비 쪽이 저장한 행과 필드마다 견준다.
- `payload`는 계약의 표 그대로다. 사용자 식별자, 금액, 단가, 결제·PG 식별자, 카드는 들지 않는다. 9주차 랭킹에 필요한 것은 상품과 수량이다.
- Kafka header는 쓰지 않는다. `kafka.yml`이 `spring.json.add.type.headers: false`로 타입 header를 끈 채다. 소비 쪽은 `eventType`과 `schemaVersion`을 본문에서 읽는다.
- (2026-10-27: [티켓 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171)이 broker에서 읽은 본문으로 확인했다. `modules:kafka`의 `JacksonJsonSerializer`는 spring-kafka 4.1.1이 만드는 기본 `JsonMapper`(Jackson 3)를 쓰고, Jackson 3은 `Instant`를 `DateTimeFormatter.ISO_INSTANT`의 문자열로 쓴다. 고정한 시계의 `2026-10-27T01:02:03.123456Z`가 그대로 나갔으므로 producer의 mapper는 따로 정하지 않았다. record에는 header가 하나도 없었다. 다만 `ISO_INSTANT`는 소수부를 세 자리씩 끊어 필요한 만큼만 쓴다. 마이크로초가 `000`으로 끝나는 시각은 `…03.100Z`, 소수부가 없는 시각은 `…03Z`로 나간다. 값은 마이크로초까지 그대로이고 ISO-8601 파서는 세 꼴을 모두 읽으므로, 소비 쪽은 `confirmedAt`을 문자열이 아니라 시각으로 읽어 견준다.)
- 발행 쪽과 소비 쪽은 DTO를 나눠 가진다. 계약은 바이트이고, 이 절이 그 문서다.
- 고객이 임의의 이벤트를 넣는 공개 endpoint는 없다. 합성 이벤트는 테스트 안에서만 만든다.

## 3. 발행 규칙

| 경우 | 발행 |
| --- | --- |
| TX B에서 결제가 SUCCESS로 바뀌어(`ReconcileResult.Changed`) 주문이 처음 CONFIRMED가 되고 커밋됐다. 확정의 생성 응답, 고객의 대사, PG의 알림 어느 길이든 같다. | 한다. 한 번. |
| 확정 전, 결제 중, 결과 미확인, 결제에 실패한 주문 | 안 한다. |
| TX B가 되돌아갔다. | 안 한다. 포트의 약속이다. |
| 끝난 결제에 같은 답을 다시 반영했다(`Unchanged`). 중복 callback, 중복 reconcile, 늦은 생성 응답. | 안 한다. |
| 충돌(`Conflicted`), 생성 전 거절 | 안 한다. |
| 포인트로 확정된 이전 주문 | 소급 발행하지 않는다. 조회는 그대로다. |
| 커밋 뒤 전송이 실패했다. | 주문은 CONFIRMED 그대로, 예약은 소비된 그대로. 발행의 실패를 WARN으로 따로 드러낸다. PG create를 다시 부르지 않는다. |

payload는 잠근 주문의 저장된 품목·수량·시각에서 TX B 안에서 만든다. 소비 시점의 상품 조회로 덮어쓰지 않는다.

## 4. 전달 메커니즘

```
OrderPaymentSagaSteps.applyResult  (TX B, 주문 잠금 아래)
  └─ order.completePayment(clock.instant())
  └─ orderEventPublisher.publish(OrderConfirmedEvent)        ← required 포트 (application.order.required)
        └─ [adapter.integration.kafka] 포트 구현
             ├─ check(isActualTransactionActive())
             └─ applicationEventPublisher.publishEvent(event)
… TX B 커밋 …
  └─ afterCommit: @TransactionalEventListener(AFTER_COMMIT) @Async("orderEventsExecutor")
        └─ [adapter.integration.kafka] 중계(relay), 가상 스레드
             ├─ OrderConfirmedMessage(봉투)로 옮긴다
             ├─ try { kafkaTemplate.send(topic, key, message) } catch { WARN }
             └─ future.whenComplete { INFO(topic, partition, offset) | WARN(원인) }
```

- **커밋 뒤가 보장되는 까닭.** `@TransactionalEventListener(AFTER_COMMIT)`는 이벤트를 발행한 때의 트랜잭션에 동기화를 걸어 커밋 뒤에만 돈다. 되돌아간 TX B의 이벤트는 버려진다. 트랜잭션 밖에서 발행하면 조용히 버려지는 것이 이 애노테이션의 성질이라, 포트 구현이 `check`로 먼저 거절한다.
- **왜 사가가 아니라 단계가 부르는가.** 조율하는 사가가 커밋 뒤에 포트를 부르는 길도 커밋 뒤를 구조로 보장한다. 그러나 단계의 반환 타입이 이벤트를 실어야 하고 사가가 Kafka의 실패를 잡아야 한다. 단계가 확정한 그 자리에서 포트를 부르면 단계도 사가도 Kafka를 모른다.
- **왜 비동기인가.** Spring은 `afterCommit`을 연결을 풀에 돌려주는 `cleanupAfterCompletion`보다 먼저 돌린다. 동기 중계는 전송이 끝날 때까지 TX B의 DB 연결을 쥐고, 중계가 던진 예외는 `commit()` 밖으로 나가 커밋된 확정이 500으로 답한다. 비동기 중계는 작업을 넘기고 돌아와 연결이 곧 풀리고, `@Async` 메서드의 예외는 호출자에게 가지 않는다. 그래서 중계는 `Unit`을 돌려준다. `Future`를 돌려주면 예외가 그 future에 들어가 아무도 읽지 않는다. (2026-10-27, [티켓 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172): 동기 중계의 예외가 `commit()` 밖으로 나간다는 것은 틀렸다. AFTER_COMMIT 리스너는 `afterCompletion(STATUS_COMMITTED)`에서 돌고, Spring은 `afterCompletion`의 예외를 잡아 `TransactionSynchronization.afterCompletion threw exception`으로 ERROR에 남긴 뒤 이어 간다(spring-tx 7.0.9). 누락 구간 테스트에서 `@Async`를 빼고 실패가 중계 밖으로 나가게 바꾸자 확정은 정상으로 답했다. `afterCompletion`도 `cleanupAfterCompletion`보다 먼저라 연결을 쥐는 문제와, metadata를 기다리는 `send()`가 확정하는 스레드를 `max.block.ms`까지 막는 문제는 그대로다. 예외가 `commit()` 밖으로 나가는 것은 손으로 등록한 `afterCommit` 동기화다.)
- **실행기.** 작업마다 가상 스레드 하나(`Executors.newVirtualThreadPerTaskExecutor()`를 `TaskExecutorAdapter`로 감싼 빈 `orderEventsExecutor`). 큐가 없어 broker가 오래 멈춰도 메모리에 쌓이지 않고, 큐가 꽉 차 `afterCommit`에서 `TaskRejectedException`이 나는 일도 없다. `spring.threads.virtual.enabled`는 켜지 않는다. Tomcat과 리스너 컨테이너까지 바꾸는 전역 스위치다. (2026-10-27: [티켓 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171)에서 본 것. `Executor` 빈이 하나라도 있으면 Boot 4.1의 `applicationTaskExecutor`가 물러난다(`spring.task.execution.mode`가 기본값 `auto`일 때). commerce-api에는 그 실행기를 쓰는 곳이 없다. 비동기 타입을 돌려주는 MVC 처리기도, 다른 `@Async` 메서드도 없다. `TaskExecutorAdapter`에는 `close`가 없어 컨텍스트가 닫힐 때 도는 중계를 기다리지 않는다. 그때까지 발행하지 못한 중계는 6절의 누락 구간이다. INFO 줄은 broker의 응답을 받은 producer의 네트워크 스레드에서 남는다. 중계의 가상 스레드에는 이름이 없다.)
- **예외 처리기.** `void` `@Async` 메서드의 예외는 `AsyncUncaughtExceptionHandler`로 간다. 기본 처리기가 메서드와 인자를 ERROR로 남긴다. 그것은 중계 자체의 버그(예: 봉투로 옮기다 난 오류)를 위한 뒷받침이고, 예상한 실패(전송 오류)는 중계의 `try/catch`와 `whenComplete`가 WARN으로 남긴다. 사용자 정의 처리기는 두지 않는다. (2026-10-27, [티켓 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171): 기본 처리기 `SimpleAsyncUncaughtExceptionHandler`는 메서드와 스택 트레이스만 ERROR로 남기고 인자는 남기지 않는다(spring-aop 7.0.9). 중계 자체의 버그는 그 줄에 eventId와 orderId가 없다. 봉투로 옮기는 일은 `try` 밖에 두어, 버그가 전송 실패의 WARN으로 섞이지 않게 했다.)

## 5. 실패와 관찰

| 설정 | 값 | 뜻 |
| --- | --- | --- |
| `acks` | `all`(명시) | broker가 하나라 `min.insync.replicas` 기본값 1과 함께 `acks=1`과 같다. 복제의 보장은 이 환경에서 증명하지 못한다. |
| `enable.idempotence` | `true`(기본) | 재시도가 같은 record를 두 번 쓰지 않는다. 한 producer 세션 안의 보장이다. |
| `request.timeout.ms` | 20 s(`kafka.yml`) | 한 번의 요청이 답을 기다리는 한도. |
| `retry.backoff.ms` / `retries` | 500 ms / 3(`kafka.yml`) | 재시도 사이의 대기. `delivery.timeout.ms` 안에서만 뜻이 있다. |
| `delivery.timeout.ms` | 120 s(기본) | producer가 포기하는 시점. `linger.ms + request.timeout.ms` 이상이어야 한다. send의 future는 이때 실패로 끝난다. |
| `max.block.ms` | 60 s(기본) | `send()` 자체가 metadata나 버퍼를 기다리며 막히는 한도. 비동기 중계라 요청 스레드 밖이다. |

- 중계는 전송을 기다리지 않는다. future는 ack를 받았거나 `delivery.timeout.ms`에 포기했을 때만 끝나므로, 그보다 짧게 기다려 끊으면 실패가 아니라 "아직 시도 중"을 본 것이다. 처음 생각한 3 s 한도는 동기 중계가 DB 연결을 쥐고 있어서 필요했던 수였고, 과제에도 근거가 없다.
- 성공은 INFO 한 줄(eventId, topic, partition, offset), 실패는 WARN 한 줄(eventId, orderId, 원인). 지표는 이번 주에 두지 않는다.
- `kafka.yml`의 `auto.register.schemas`, `use.latest.version`(schema registry의 것)과 `auto.create.topics.enable`(broker의 것)은 클라이언트가 모르는 설정이라 시작할 때 WARN을 남긴다. 템플릿의 것이라 이번 주에는 두고 적어 둔다.

## 6. 누락 구간

커밋과 전송은 원자적으로 묶이지 않는다. 커밋 뒤 전송 전에 전송이 실패하거나 프로세스가 멈추면 확정된 주문과 소비된 예약은 남고 이벤트는 없다. 비동기 중계는 그 틈을 큐 하나만큼 더 넓힌다.

- **재현.** 테스트가 `KafkaTemplate`을 springmockk `@MockkBean`으로 바꿔 `send`가 던지거나 `Pause`에서 멈추게 한다. 실제 주문 API, 테스트 PG, 실제 TX B로 주문을 확정한다. 그 뒤 새 트랜잭션에서 주문은 CONFIRMED, 예약은 소비됨을 읽고, broker에 그 eventId의 record가 없음을 보고, 확정은 정상으로 답했음을 본다. WARN 한 줄이 남는다. (2026-10-27, [티켓 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172): `OrderEventPublishGapTest`다. 확정은 HTTP가 아니라 provided 포트 `OrderConfirmer`로 부른다. 장면은 둘이다. `send`가 곧바로 던지는 장면과, `send`가 `Pause`에서 멈춘 동안 DB와 broker를 읽는 장면이다. 둘 다 `KafkaException("Send failed")` ← `TimeoutException("Topic … not present in metadata after 60000 ms.")`를 던진다. broker에 닿지 못한 producer가 `max.block.ms`를 다 기다린 뒤 `KafkaTemplate.send`가 던지는 꼴이고, 멈춘 장면은 그 기다림의 한가운데, 던지는 장면은 그 끝이다. 멈춘 장면은 풀어 준 뒤 중계의 WARN을 기다려 중계가 끝난 다음에 마친다. `KafkaTemplate`은 `SmartInitializingSingleton`이라 mock은 `@MockkBean(relaxUnitFun = true)`다. 중계 밖으로 나간 예외가 없음은 `SimpleAsyncUncaughtExceptionHandler`의 ERROR가 없음으로 본다. 실행 결과와 commit, 명령은 `docs/research/week6-order-events-writing-material.md`의 "W7 handoff: unpublished case"에 있다.)
- **증거의 뜻.** 이것은 DB와 broker가 하나의 트랜잭션이 아니라는 증거다. 실패 주입을 OS의 강제 종료라고 부르지 않는다.
- **7주차에 넘기는 것.** 미발행 사례 1건은 이 테스트다. 정상 발행·소비 사례 1건은 소비 쪽(2단계)이 남긴다. 각 사례의 역할, W6 commit, 실행 방법, 고정 시계·품목·수량을 재현 코드에 적고, 실행 결과의 orderId, PG 주문 번호, 거래 키, eventId, confirmedAt, items, 주문·예약·broker 상태를 `docs/research/`에 기록한다. 카드 값과 비밀은 뺀다. 임시 컨테이너는 정상 정리한다.
- **다음 주의 개선.** 같은 Spring 이벤트에 `BEFORE_COMMIT` 리스너를 더해 TX B 안에서 outbox 행을 쓴다. 단계와 포트는 그대로다(ADR 0032의 다시 볼 조건).

## 7. 토픽과 설정

- `NewTopic("order-events", partitions = 3, replicas = 1)` 빈. `KafkaAdmin`이 시작할 때 만든다. 복제 1은 broker가 하나라 강제된 값이다. 파티션 3은 템플릿의 리스너 concurrency 3과 소비 쪽 record 리스너의 concurrency 3에 짝지은 값이다. key가 orderId라 파티션 수와 무관하게 한 주문의 차례는 지켜지지만, 한 주문에 이벤트는 하나라 그 차례도 쓰이지 않는다.
- 설정 접두사는 `pg`처럼 최상위의 `order-events`다. `topic`(기본 `order-events`), `partitions`(3). `@ConfigurationProperties("order-events")`는 Kafka 어댑터 패키지에 둔다.
- commerce-api의 `application.yml`이 `kafka.yml`을 들인다. `local`·`test` 프로필의 bootstrap은 `localhost:19092`(compose의 `PLAINTEXT_HOST`)이고, 그 밖은 `${BOOTSTRAP_SERVERS}`다. 테스트는 컨테이너의 주소로 덮는다. (2026-10-27: [티켓 168](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/168)이 `local, test` 문서를 둘로 나눴다. `local`만 `localhost:19092`를 지니고, `test`는 주소 없이 `spring.kafka.admin.fail-fast: true`를 둔다. `jpa.yml`이 `test`에 접속 주소를 두지 않는 것과 같은 꼴이라, fixture를 빠뜨린 컨텍스트가 `MySqlTestContainersConfig`처럼 곧바로 실패한다. 나누기 전에는 fixture 없는 컨텍스트가 `localhost:19092`에 붙으려다 `KafkaAdmin`의 기다림(30 s)과 닫기(10 s)를 넘기고 61 s 만에 그대로 떴다. compose가 떠 있었다면 로컬 broker에 토픽을 만들었을 것이다. 주소만 뺐을 때는 admin 클라이언트가 풀리지 않은 `${BOOTSTRAP_SERVERS}`를 거절했지만, `KafkaAdmin`은 기본값으로 그 오류를 로그로만 남겨 컨텍스트가 16 s 만에 떴다. `fail-fast`를 더하자 `IllegalStateException: Could not create admin` ← `ConfigException: Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`로 실패한다. 운영 프로필의 `fail-fast`는 꺼진 그대로다. 켜면 broker가 없을 때 commerce-api가 뜨지 않는다. 템플릿의 `admin.properties.bootstrap.servers: kafka:9092`는 `local`에 남겼으나 쓰이지 않는다. Boot가 admin의 `bootstrap.servers`를 연결 정보(`KafkaConnectionDetails`)의 공통 주소로 다시 쓰기 때문이다. 템플릿 정리라 두었다.)
- `spring.kafka.producer.acks: all`을 `kafka.yml`에 적는다. (2026-10-27: producer factory의 설정(`ProducerFactory.getConfigurationProperties()`)에 `acks=all`이 있는지 `OrderEventsProducerTest`가 본다. kafka-clients의 기본값도 3.0부터 `all`이라, 실제 producer의 값을 읽으면 이 줄이 없어도 통과한다. factory의 설정에는 명시한 값만 있다.)

## 8. 테스트 기반

- **Kafka 컨테이너.** `modules:kafka`의 testFixtures에 `KafkaTestContainersConfig`. `MySqlTestContainersConfig`와 같은 꼴이다. JVM마다 컨테이너 하나, `@Import`로 명시, `DynamicPropertyRegistrar`로 `spring.kafka.bootstrap-servers`를 넣는다. 이미지는 `apache/kafka-native`. Docker Hub에 major.minor 태그가 없어 전체 판으로 고정한다(2026-10-27 확인: 4.3.2가 안정 판, 4.4.0은 닷새 전). Testcontainers 2.0.5의 `org.testcontainers.kafka.KafkaContainer`가 이 이미지를 안다. compose의 `bitnamilegacy/kafka:3.5`는 로컬 실행용으로 그대로 둔다. (2026-10-27: 고른 판은 `apache/kafka-native:4.3.2`다. 같은 날 Docker Hub에서 다시 봤다. `4.3.2`는 2026-10-20에, `4.4.0`은 2026-10-22에 올라왔고 `4.3` 태그는 없다(404). 새 minor의 첫 판보다 이전 minor의 패치 판을 골랐다. `BaseApplicationServiceTest`와 `CommerceApiContextTest`, 장면 8의 재시작한 컨텍스트가 이 설정을 가져온다. 발행 테스트만 들일지는 "fixture를 빠뜨린 컨텍스트는 MySQL처럼 실패한다"는 티켓의 기준이 정했다. 그 기준을 위해 test 프로필이 broker 주소를 두지 않고 `fail-fast`를 켜므로(7절의 날짜 메모), 토픽을 선언한 전체 컨텍스트는 모두 broker가 있어야 뜬다. 발행 테스트만 들이려면 나머지 컨텍스트에 주소를 따로 줘야 하는데, 쓰는 broker의 주소면 로컬로 새고 아무도 없는 주소면 컨텍스트마다 `KafkaAdmin`의 기다림(fixture 없이 61 s)을 쓴다. 컨테이너는 0.4 s 만에 뜬다. `@DataJpaTest`의 `BaseRepositoryTest`는 Kafka 자동 설정이 없어 가져오지 않는다.)
- **broker 읽기.** spring-kafka-test의 `KafkaTestUtils`. 테스트마다 새 consumer(고유 group, `earliest`)로 record의 key, 본문 JSON, topic, partition, offset을 본다. 같은 준비가 두 테스트에서 반복되면 그때 fixture에 도우미를 둔다. (2026-10-27: [티켓 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171)이 고유 group과 `earliest`를 버렸다. 클래스의 테스트들이 한 토픽을 나눠 쓰는데, `DatabaseCleanUp`의 `TRUNCATE`가 AUTO_INCREMENT를 되돌려 주문 ID와 거기서 유도한 key·eventId가 테스트마다 1부터 다시 나온다. 가장 이른 offset부터 읽으면 앞 테스트의 record가 이 테스트의 것으로 보인다. 그래서 consumer는 `@BeforeEach`에서 토픽의 모든 파티션을 `assign`하고 `seekToEnd`한 뒤 `position`으로 그 자리를 장면 전에 정한다. group에 들지 않으므로 `earliest`가 막으려던 경합, 곧 group의 파티션 배정이 record보다 늦는 일도 없다. 기대하는 record는 `getRecords(consumer, 10 s, 수)`로 기다리고, 그 뒤 1 s를 더 읽어 다른 record가 따르지 않는지 본다. 지금 broker를 읽는 클래스는 `OrderEventPublishingTest` 하나라 consumer는 그 클래스에 있다. 같은 클래스의 장면들은 Spring 이벤트 기록이 아니라 broker를 읽게 됐고, 커밋하는 기반의 `@RecordApplicationEvents`는 쓰는 테스트가 없어 지웠다. 발행이 TX B의 커밋을 따른다는 것은 되돌아간 TX B에는 record가 없고 그 뒤 커밋한 대사에는 하나가 있는 장면이 broker로 보인다.) (2026-10-27, [티켓 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172): 누락 구간 테스트가 broker를 읽는 둘째 클래스가 되어, 이 절의 규칙대로 consumer를 도우미로 옮겼다. `com.loopers.support`의 `TopicReader`가 열 때 끝 자리를 정하고 `publishedRecords(expected)`가 10 s와 1 s를 기다린다. kafka `testFixtures`가 아니라 commerce-api의 테스트에 두었다. 쓰는 곳이 commerce-api뿐이고, 도우미는 처음 쓰는 곳 곁에 둔다. streamer나 실험이 쓰게 되면 옮긴다.)
- **발행을 보는 테스트는 커밋한다.** `BaseCommittingApplicationServiceTest`와 `@EnableTestPg`. 롤백으로 감싼 기반에서는 AFTER_COMMIT 리스너가 돌지 않는다.
- **시계.** `Clock` 빈을 commerce-api에 두고 테스트는 `TestClock`으로 덮는다. `Order.completePayment(confirmedAt)`가 그 값을 받는다. 확정 시각을 단언하는 테스트는 시계를 고정한다.
- **누락 구간.** 6절. `@MockkBean`으로 바꾼 `KafkaTemplate`은 그 장면의 컨텍스트에만 든다.
- **포트의 약속.** 되돌아간 TX B 뒤에 record가 없음, `Unchanged` 뒤에 record가 없음, 트랜잭션 밖의 `publish`가 `IllegalStateException`으로 거절됨.
- 버전(2026-10-27): spring-kafka 4.1.1, kafka-clients 4.2.1, Testcontainers 2.0.5, Boot 4.1.1.

## 9. 받아들인 한계

- 커밋 뒤 전송 전의 누락 구간이 남는다. 6절. 7주차의 Outbox가 좁힌다.
- broker가 하나라 복제와 `acks`의 보장을 증명하지 못한다. `acks=all`은 적어 두는 선언이다.
- 발행의 실패는 로그 한 줄이다. 지표도, 다시 보내는 길도 없다. 다시 보내는 길이 Outbox다.
- 이벤트들 사이의 차례는 지키지 않는다. 한 주문에 이벤트가 하나라 문제가 아니다.
- MDC와 추적이 가상 스레드로 따라가지 않는다. 로그에 eventId와 orderId를 직접 적는다.

## 10. 다음 단계로 넘기는 것

- **소비(2단계).** `processed_order_confirmation`(eventId 유일, orderId 유일, confirmedAt)과 상품별 구매 행(orderId·productId 유일, quantity, confirmedAt). 행 표의 이름은 그 인터뷰에서 정한다. 둘을 한 TX에 커밋한다. 같은 eventId·같은 사실은 no-op, 같은 eventId의 다른 사실과 같은 orderId의 다른 eventId는 충돌이다. 중복 판단은 DB 유일 제약이 지키고, 위반 뒤 별도 TX에서 같은 사실인지 다시 본다. streamer 전용 record 리스너 팩토리(`MANUAL_IMMEDIATE`, `CommonContainerStoppingErrorHandler`, concurrency 3, `ByteArrayDeserializer`와 `ByteArrayJacksonJsonMessageConverter`). `kafka.yml`의 `value-serializer` 오타는 `value-deserializer`로 고친다. `spring.application.name`이 `commerce-api`인 streamer의 `application.yml`도 그때 본다. 소비 쪽 DTO는 따로 둔다. 재시도 뒤 중단하는 handler는 대안으로 적는다. (2026-10-27: `kafka.yml`의 `test` 프로필에 broker 주소가 없어졌다(7절의 날짜 메모). streamer의 컨텍스트 테스트도 `KafkaTestContainersConfig`를 `@Import`해야 뜬다. 지금 streamer에는 테스트가 없다.)
- **실험(3단계).** 파티션 2개의 격리 토픽, consumer 1→2→3, key→partition metadata, DB 커밋 뒤 offset 미커밋 재전달(n → n → n+1, seek 없음), 실제 리스너의 실패·롤백·재시작. 7주차에 넘기는 두 사례의 기록.
- **7주차.** Outbox. 6절.

## 11. 과제의 이름과 이 저장소의 이름

| 과제 | 이 저장소 | 까닭 |
| --- | --- | --- |
| `providerOrderId` | PG 주문 번호(`pgOrderId`) | 5주차의 용어 그대로. |
| `OrderConfirmed` 이벤트 | 주문 확정 이벤트. 코드는 `OrderConfirmedEvent`(응용), `OrderConfirmedMessage`(어댑터의 봉투) | 저장소의 역할 접미사(`*Request`, `*Info`, `*Exception`)와 같은 꼴. 선 위의 `eventType`은 `OrderConfirmed` 그대로다. |
| after-commit listener | 중계(relay) | 포트 → Spring 이벤트 → AFTER_COMMIT 비동기 중계. |
| 상품 구매 활동 | 2단계의 인터뷰에서 정한다 | 소비 쪽의 용어다. |
| 누락 구간 | 누락 구간(publish gap) | 그대로. |
