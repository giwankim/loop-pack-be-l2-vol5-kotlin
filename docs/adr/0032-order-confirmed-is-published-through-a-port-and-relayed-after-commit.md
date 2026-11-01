---
status: accepted
date: 2026-10-27
---

# 주문 확정 이벤트는 TX B 안에서 required 포트로 발행하고, 어댑터가 커밋 뒤 비동기로 Kafka에 전달한다

6주차 과제 1·2절은 검증된 PG SUCCESS가 주문을 처음 CONFIRMED로 바꾼 커밋이 성공한 뒤에만 `OrderConfirmed`를 `order-events`에 발행하라고 한다. 발행 위치는 "after-commit listener 또는 트랜잭션 서비스 호출이 실제 commit된 뒤의 orchestration"이고, 같은 메서드의 `@Transactional` 끝에 쓴 send는 커밋이 아니다. producer의 send 결과를 관찰해 성공한 record의 topic·partition·offset과 전송 오류를 가르고, 커밋 뒤 send가 실패해도 확정된 주문을 되돌리거나 PG create를 다시 부르지 않는다. 커밋과 전송 사이의 누락 구간은 다음 주 Outbox의 출발점으로 재현해 둔다.

[ADR 0028](./0028-order-slice-orchestrates-the-payment.md)이 정한 모양에서 주문이 CONFIRMED가 되는 자리는 하나다. `OrderPaymentSagaSteps.applyResult`가 `ReconcileResult.Changed`로 결제가 SUCCESS가 됐을 때 `order.completePayment()`를 부른다. 확정의 생성 응답, 고객의 대사, PG의 알림이 모두 같은 TX B로 온다. 조율하는 `OrderPaymentSaga`는 `NEVER`라 TX B가 돌아오면 이미 커밋돼 있다.

1. **발행은 required 포트다.** `application.order.required.OrderEventPublisher.publish(event: OrderConfirmedEvent)`. TX B 안에서 `applyResult`가 SUCCESS로 바뀐 그 자리에서 부른다. 다른 자리는 부르지 않는다. 구현하는 Service와 부르는 쪽마다 쓰기 포트 하나를 두는 규칙([ADR 0013](./0013-commerce-api-follows-splearn-hexagonal-structure.md))에서 부르는 쪽은 단계의 빈 하나다. 포트의 약속은 셋이다. 둘러싼 트랜잭션이 커밋되면 전달하고, 되돌아가면 버리며, 부르는 쪽에 던지지 않는다.
2. **이벤트는 잠근 주문에서 미리 만든다.** `OrderConfirmedEvent(orderId, confirmedAt, items)`는 응용 계층의 타입이고 포트 곁에 둔다. `items`는 상품 ID 오름차순의 `(productId, quantity)`다. 주문은 상품별 품목이 하나씩이라(`Order`의 init 검사) 정규화는 애그리거트가 이미 보장한다. 커밋 뒤에 엔티티를 다시 읽지 않는다.
3. **어댑터는 Spring 이벤트로 받아 커밋 뒤에 중계한다.** `adapter.integration.kafka`의 포트 구현은 `ApplicationEventPublisher.publishEvent`로 넘기고, 그 전에 `check(TransactionSynchronizationManager.isActualTransactionActive())`로 트랜잭션 밖의 호출을 거절한다(`ProductListRevisionMarker`와 같은 검사). 같은 패키지의 중계(relay)는 `@TransactionalEventListener(phase = AFTER_COMMIT)`에 `@Async("orderEventsExecutor")`를 더한 메서드다. 실행기는 작업마다 가상 스레드 하나를 쓰는 전용 빈이다. `spring.threads.virtual.enabled`를 켜 Tomcat과 리스너 컨테이너까지 바꾸지 않는다.
4. **왜 비동기인가.** Spring은 `afterCommit` 동기화를 `cleanupAfterCompletion`보다 먼저 돌린다. 동기 중계는 Kafka 전송이 끝날 때까지 TX B의 DB 연결을 풀에 돌려주지 않고, 전송 중 예외는 `commit()` 밖으로 나가 커밋된 주문의 확정이 500으로 답한다. `@Async`는 `afterCommit`에서 작업을 넘기고 곧바로 돌아오므로 연결은 전송과 무관하게 풀린다. `send()` 자체가 metadata를 못 얻어 `max.block.ms`까지 막히는 경우도 요청 스레드 밖이다. 중계 메서드는 `Unit`을 돌려준다. `Future`를 돌려주면 예외가 아무도 읽지 않는 future에 들어가 사라진다. (2026-10-27, [issue 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172): 예외에 관한 문장은 틀렸다. `@TransactionalEventListener(AFTER_COMMIT)`는 `afterCommit`이 아니라 `afterCompletion(STATUS_COMMITTED)`에서 돌고, `TransactionSynchronizationUtils.invokeAfterCompletion`이 그 예외를 잡아 ERROR로 남기므로 `commit()` 밖으로 나가지 않는다(spring-tx 7.0.9). 누락 구간 테스트의 중계에서 `@Async`를 빼고 발행의 실패가 중계 밖으로 나가게 바꾸자, 확정은 정상으로 답했고 `TransactionSynchronization.afterCompletion threw exception` ERROR 한 줄만 남았다. `commit()` 밖으로 나가는 것은 손으로 등록한 `TransactionSynchronization.afterCommit`의 예외다. `afterCompletion`도 `cleanupAfterCompletion`보다 먼저 돌므로, 동기 중계가 TX B의 연결을 쥐고 `max.block.ms`까지 확정하는 스레드를 막는다는 까닭은 그대로다.) (2026-10-27, [issue 173](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/173): 같은 까닭으로 이 규칙의 `afterCommit`은 `afterCompletion(STATUS_COMMITTED)`으로 읽는다. `@Async`가 작업을 넘기는 자리도 그것이다. 커밋 뒤라는 뜻은 같다.)
5. **전송 결과는 콜백으로 관찰하고 기다리지 않는다.** 중계는 `send()`를 `try/catch`로 감싸고 돌려받은 future에 `whenComplete`를 단다. 성공은 INFO 한 줄에 eventId, topic, partition, offset을, 실패는 WARN 한 줄에 eventId, orderId, 원인을 남긴다. 어느 경우에도 던지지 않는다. 전송의 기한은 producer의 `delivery.timeout.ms`(기본 120 s) 하나다. 그보다 짧게 기다려 끊으면 실패를 본 것이 아니라 "아직 시도 중"을 본 것이므로 별도의 기다림 한도를 두지 않는다. 처음 생각한 3 s는 근거가 없었다. `acks=all`을 적되 broker가 하나라 `acks=1`과 같다는 것을 설계 문서에 적는다.
6. **식별은 주문에서 유도한다.** record의 key는 `orderId`의 문자열이다. eventId는 `order-confirmed:v1:{orderId}`로 유도하고 저장하지 않는다. 주문의 확정은 상태 기계와 CHECK 제약으로 한 번뿐이라 다시 발행해도, 다시 전달돼도 같은 값이다. 전송 횟수, 현재 시각, 새 UUID는 들지 않는다. 메시지는 봉투 꼴이다. `eventId`, `eventType`(`OrderConfirmed`), `schemaVersion`(1)이 밖에, 업무 필드 `orderId`, `confirmedAt`, `items`가 `payload` 안에 있다. 봉투 DTO `OrderConfirmedMessage`는 어댑터의 것이다. PG 어댑터의 DTO가 `adapter.integration.pg`에 있는 것과 같다. `confirmedAt`은 마이크로초까지의 ISO-8601 UTC 문자열이다. (2026-10-27, [issue 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171): producer의 mapper는 정하지 않았다. spring-kafka의 기본 mapper(Jackson 3)는 `Instant`를 `ISO_INSTANT`로 써서 소수부를 세 자리씩 필요한 만큼만 쓴다. 그래서 `…03.123456Z` 말고도 `…03.100Z`나 `…03Z`로 나간다. 값은 마이크로초까지 그대로이고, 소비 쪽은 문자열이 아니라 시각으로 견준다. 늘 여섯 자리로 쓸지는 정하지 않았다. [설계 문서](../design/order-events.md)의 "계약" 절에 날짜 메모가 있다.) (2026-10-27, [명세 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167)의 닫는 검토: 그 꼴 그대로를 계약으로 정했다. 여섯 자리로 고정하지 않는다.)
7. **발행하지 않는 경우.** 확정 전, 결제 중, 결과 미확인, 결제에 실패한 주문, TX B의 롤백, 끝난 결제의 반복 반영(`Unchanged`), 충돌(`Conflicted`), 생성 전 거절. 포인트로 확정된 이전 주문은 소급 발행하지 않는다. 지문(fingerprint)은 저장하지 않는다. 소비 쪽은 저장된 행과 필드마다 견준다.
8. **확정 시각은 시계 빈에서 받는다.** `Order.completePayment(confirmedAt: Instant)`로 바꾸고 단계의 빈이 `Clock` 빈에서 읽어 넘긴다. 재현 코드가 시계를 고정할 수 있어야 한다는 과제 2절의 요구다. 테스트는 이미 있는 `TestClock`을 그 빈으로 쓴다.
9. **토픽은 앱이 선언한다.** `NewTopic("order-events", partitions = 3, replicas = 1)` 빈을 두어 `KafkaAdmin`이 시작할 때 만든다. 복제 1은 broker가 하나라 강제된 값이다. 파티션 3은 Kafka의 사실이 아니라 짝짓기다. 템플릿의 리스너 concurrency가 3이고, 소비하는 record 리스너도 3으로 둔다. 설정은 `pg`처럼 최상위 접두사 `order-events`(`topic`, `partitions`)다. 테스트는 클래스마다 토픽 이름을 덮어 격리한다. (2026-10-27, [issue 173](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/173): 덮는 것은 broker를 읽는 두 클래스(`OrderEventPublishingTest`, `OrderEventPublishGapTest`)와 덮기를 보는 `OverriddenOrderEventsTopicTest`뿐이다. PG로 확정하는 다른 테스트의 중계는 공유 broker의 `order-events`에 발행하고, 그 record는 아무도 읽지 않는다. 읽는 클래스가 저마다 토픽을 쓰므로 다른 클래스가 늦게 발행한 record가 그 장면에 섞이지 않는다.)

## 고르지 않은 것

- **조율하는 사가가 단계의 답을 보고 포트를 부른다.** `applyTransaction`이 "이번 호출이 확정했다"를 돌려주고 `NEVER`인 사가가 커밋 뒤에 발행한다. 커밋 뒤가 구조로 보장되고 DB 연결도 이미 풀려 있다. 그러나 단계의 반환 타입이 이벤트를 실어야 하고, 사가가 Kafka의 실패를 잡아야 한다. 단계가 이벤트 타입을 돌려주는 것을 원하지 않았다.
- **어댑터가 `TransactionSynchronization.afterCommit`을 손으로 등록한다.** `ProductListRevisionMarker`와 같은 꼴이고 한 번 덜 건너간다. 그러나 Spring이 선언적으로 주는 것을 손으로 쓰는 것이고, 과제가 이름 부른 것은 after-commit listener다.
- **Spring Data의 `@DomainEvents`.** 발행이 저장소 어댑터의 `save` 호출에 묶인다. 포트의 방향과 거꾸로다.
- **포트 없이 응용 계층이 `ApplicationEventPublisher`를 직접 부른다.** 의존이 생성자에 보이지 않고 ADR 0013의 포트 목록에서 사라진다.
- **동기 중계와 기다림 한도.** 규칙 4와 5의 까닭이다.
- **지금 Outbox.** 과제가 다음 주로 두었다. 이번 주는 누락 구간을 재현해 넘긴다.
- **사용자 정의 `AsyncUncaughtExceptionHandler`.** 컨텍스트에 하나뿐이고 `(Throwable, Method, Object...)`만 받아 이벤트를 알지 못한다. 기본 처리기가 메서드와 인자를 ERROR로 남기는 것으로 중계 자체의 버그를 잡고, 예상한 실패는 중계의 코드가 잡는다. (2026-10-27, [issue 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171): 기본 처리기는 메서드와 스택 트레이스만 남기고 인자는 남기지 않는다. 버그의 ERROR 줄에는 eventId가 없지만 고르지 않은 까닭은 그대로다.)

## 대가

- 읽는 사람이 두 번 건너야 한다. 포트 → Spring 이벤트 → 리스너 → Kafka. 포트의 KDoc이 약속을 적고 이 ADR이 까닭을 적는다.
- 커밋과 전송의 틈이 큐 하나만큼 더 넓어진다. 과제가 재현하라는 누락 구간이 바로 이것이고, 다음 주의 Outbox가 좁힌다.
- 테스트는 돌려받은 값이 아니라 broker를 읽는다. 발행을 보는 테스트는 커밋하는 기반(`BaseCommittingApplicationServiceTest`)을 쓴다. 롤백으로 감싼 기반에서는 AFTER_COMMIT 리스너가 돌지 않는데, 그것은 되돌아간 확정이 발행되지 않는다는 뜻 그대로다.
- MDC와 추적 컨텍스트가 가상 스레드로 따라가지 않는다. 이번 주에는 eventId와 orderId를 로그에 직접 적는다.
- 이벤트들 사이의 차례는 지키지 않는다. key가 orderId고 주문은 한 번 확정되므로 같은 key의 두 번째 이벤트가 없다.
- commerce-api가 `modules:kafka`에 기댄다. `kafka.yml`의 schema registry 속성(`auto.register.schemas`, `use.latest.version`)과 broker 속성(`auto.create.topics.enable`)은 클라이언트가 모르는 설정이라 WARN을 남긴다. 템플릿의 것이라 이번 주에는 두고 설계 문서에 적는다.

## 다시 볼 조건

- **7주차의 Outbox.** 같은 Spring 이벤트에 `@TransactionalEventListener(phase = BEFORE_COMMIT)` 리스너를 더해 TX B 안에서 outbox 행을 쓴다. 실패하면 TX B가 되돌아간다. AFTER_COMMIT 중계는 물리거나, 소비 쪽의 eventId 중복 제거에 기대어 빠른 길로 남긴다. 단계와 포트는 바뀌지 않는다. 포트 구현이 outbox 행을 직접 쓰는 길도 있으나 한 이벤트에 여러 리스너를 거는 자리를 잃는다.
- 주문을 확정하는 쓰기가 둘 이상이 되면 규칙 1의 "부르는 쪽 하나"를 다시 본다.
- 추적이 필요해지면 실행기에 컨텍스트 전파를 더한다.

## 바꾸는 결정

ADR 0028의 "고르지 않은 것 · 도메인 이벤트"에 날짜 메모를 단다. TX B의 단계로서의 이벤트는 여전히 고르지 않고, 이 이벤트는 커밋 뒤에 발행된다. ADR 0013의 쓰기 포트 규칙을 그대로 적용한다. 새 포트는 `OrderEventPublisher` 하나다.
