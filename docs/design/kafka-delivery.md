# Kafka 전달 설계 — 배정, key와 파티션, 커밋 뒤 다시 전달, 리스너의 실패

6주차 과제 3단계(`feat/kafka-delivery`)의 설계 문서다. 용어는 [`apps/commerce-streamer/CONTEXT.md`](../../apps/commerce-streamer/CONTEXT.md)를 따른다. 특히 "다시 오는 이벤트" 절의 다시 발행하다와 다시 전달되다, 그리고 멈추다다. 결정은 [명세 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184)에 있고 새 ADR은 없다. 되돌리기 어려운 결정이 없었다. 실험이 기대는 규칙은 [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md)와 [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md)의 규칙 5·6이다.

상태: 2026-10-29 3단계를 인터뷰해 명세 184로 정하고, 같은 날 그 티켓 185–189를 마쳤다. 이 문서는 마지막 티켓([issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189))이 `756f0b7`의 코드에 맞춰 썼다. 구현이 명세와 달라진 것은 명세 184의 날짜 메모에 있고, 이 문서의 본문은 구현된 그대로다.

## 1. 근거와 범위

- 과제: [6주차 실습 · Kafka 설정 변화로 전달 상태 설명하기](https://app.notion.com/p/6-Kafka-4025f76d2654800da946cb2b4ad1bc88). 3단계의 근거는 과제의 실험 넷과 PR에 보이라는 것들이다.
  1. **배정.** 파티션 2개의 격리 토픽에 consumer가 하나, 둘, 셋 차례로 든다. consumer 객체의 수가 아니라 broker가 본 배정을 남긴다.
  2. **key와 파티션.** 같은 key의 send가 한 파티션에 가는지를 send metadata로 본다.
  3. **커밋 뒤 다시 전달.** DB 커밋 뒤 offset을 커밋하지 않고 멈추면, 같은 group의 다음 consumer가 같은 record를 seek 없이 받는다. 커밋된 offset은 n → n → n+1이다.
  4. **실제 리스너의 실패.** 실제 Spring 리스너가 쓰던 도중에 실패하면 모두 롤백되고 offset은 그대로다. 원인을 걷어내고 다시 시작하면 기록된다.
  5. **PR에 보일 것.** 대표 주문의 구매 활동 행, 배정 표, 실제 offset의 앞뒤, 남은 DB → broker 구간, 단일 broker와 통제된 멈춤의 한계. 과제의 AI 검토가 든 반례 셋(DB 저장 뒤 commit 전에 멈춤, handler가 예외를 삼킴, 같은 ID로 다른 수량 수신)도 있다.
- 2단계의 결과에서 시작한다. 2단계의 테스트가 이미 보이는 것은 다시 하지 않는다. record가 행이 되고 offset은 DB 커밋 뒤에만 커밋된다. 같은 이벤트를 두 번 발행해도 행은 한 벌이다. 처리할 수 없는 이벤트는 consumer를 세운다. 아직 보이지 않던 것이 이 단계의 실험 넷이다. 그 가름은 명세 184의 Problem Statement에 있다.
- 운영 코드의 변경은 하나다. 리스너가 거절 줄의 두 타입 밖의 예외에도 ERROR 한 줄을 남긴다(2.4절). 나머지는 모두 테스트다.
- 구현 기록: [6주차 Kafka 전달 글감](../research/week6-kafka-delivery-writing-material.md). 티켓마다의 닫는 댓글, 네 실험의 한 실행, 반례 셋과 세 단계의 누적 검증 표, 7주차에 넘기는 두 사례, PR 본문 초안이 있다.

### 1.1 세 단계

[주문 이벤트 설계](./order-events.md) 1.1절의 표다. 이 문서는 셋째 줄이다.

### 1.2 3단계가 만든 것

2026-10-29 [명세 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184)의 티켓이다. 명세가 이름 지은 네 실험 클래스(`ConsumerGroupAssignmentTest`, `KeyPartitioningTest`, `RedeliveryAfterCommitTest`, `OrderEventsListenerFailureTest`)는 모두 그대로 최종 이름이 됐다. 명세가 이름 짓지 않은 실험 도우미는 `com.loopers.support`의 `ExperimentBroker`다.

| 티켓 | 만든 것 |
| --- | --- |
| [185](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/185) | 실험 도우미 `ExperimentBroker`(토픽을 만들고 group의 이름을 짓고, 닫을 때 지운다)와 그 테스트 `ExperimentBrokerTest`, key → 파티션 실험 `KeyPartitioningTest`(2.2절). |
| [186](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/186) | 배정 실험 `ConsumerGroupAssignmentTest`, 도우미의 group 설명 읽기 `describeGroup`과 member → 파티션 표 `partitionsByMember()`(2.1절). |
| [187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187) | 리스너의 실패 줄, 실패 실험 `OrderEventsListenerFailureTest`, 기반의 `assertOnlyTheRowsOf`·`storedConfirmationsLine`·`storedActivitiesLine`, ADR 0033 규칙 5와 [구매 활동 설계](./purchase-activity.md) 6절, 9절의 날짜 메모(2.4절). |
| [188](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/188) | 다시 전달 실험 `RedeliveryAfterCommitTest`, 도우미의 커밋된 offset 읽기 `committedOffset(group, TopicPartition)`, 2단계의 다시 발행 장면의 새 이름 `the same event published twice …`(2.3절). |
| [189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189) | 이 문서, [주문 이벤트 설계](./order-events.md) 1.1절, 10절과 명세 184의 날짜 메모, 글감의 머리말·한 실행·반례 절·7주차의 두 사례·PR 본문 초안. |

티켓보다 먼저 이 브랜치에 들어온 커밋이 다섯이다. 용어집의 세 고침(`9554a35`: 다시 발행하다와 다시 전달되다, 넓힌 멈추다)과 빌드의 판 올림 넷이다. liberica JDK 25.0.5(`1618ee7`), Boot 4.1.2와 Jetty EE10 12.1.14(`5ba74ca`), Gradle wrapper 9.8.2와 typesafe-conventions 0.12.1(`2daf469`), springdoc 3.2.0(`0be006d`)이다. 운영 코드에서 바뀐 것은 `OrderEventsListener`의 셋째 catch와 그 KDoc, `OrderEventsListenerConfig`의 KDoc뿐이다. commerce-api와 `modules`의 코드는 바뀌지 않았다.

## 2. 실험 넷

| 표지 | 실험 | 클래스 | 기반 | 증거 |
| --- | --- | --- | --- | --- |
| E1 | 배정 | `ConsumerGroupAssignmentTest` | `BaseApplicationServiceTest`(이음매 A) | broker의 group 설명(상태, member → 파티션), probe를 읽은 member |
| E2 | key → 파티션 | `KeyPartitioningTest` | `BaseApplicationServiceTest`(이음매 A) | `SendResult`의 metadata(파티션, offset) |
| E3 | 커밋 뒤 다시 전달 | `RedeliveryAfterCommitTest` | `BaseConsumerTest`(이음매 B) | 두 전달의 (파티션, offset), broker에서 읽은 커밋된 offset, 두 표, 리스너의 INFO 두 줄 |
| E4 | 실제 리스너의 실패 | `OrderEventsListenerFailureTest` | `BaseConsumerTest`(이음매 C) | 두 표, 커밋된 offset, 컨테이너의 비정상 멈춤, 실패 줄 |

- **이음매.** 명세 184의 Testing Decisions다. A는 앱의 실제 Kafka 클라이언트와 broker로, 응용 코드를 시험하지 않고 이 앱의 클라이언트 설정으로 Kafka가 하는 일을 본다. B는 컨테이너 없이 consumer에서 리스너 메서드까지로, 이 단계가 새로 둔 이음매다. 다시 전달 실험은 consumer의 poll, close, commit을 쥐어야 하는데 컨테이너는 그것을 감춘다. C는 실제 컨테이너를 거친 broker에서 리스너까지로, 2단계의 이음매 2다.
- **표지.** E1–E4는 인터뷰가 실험에 붙인 이름이다. E1–E3의 INFO 줄 머리(`E1 group assignment`, `E2 key → partition`, `E3 redelivery after commit`)에 남았고, E4의 줄은 `Real listener failure, a lock wait timeout after a partial write`로 풀어 썼다.
- 넷 모두 평소의 `check`에서 돈다. 장면마다 본 값을 단언 뒤에 INFO 줄로 남긴다. 한 실행의 줄은 글감의 ["One run of the four experiments"](../research/week6-kafka-delivery-writing-material.md#one-run-of-the-four-experiments)에 있다.

### 2.1 배정 (E1)

- **토픽과 member.** `ExperimentBroker`가 파티션 2개, replica 1의 토픽을 만들고 group의 이름을 짓는다. member는 consumer 하나짜리 컨테이너 `KafkaMessageListenerContainer`이고 client ID가 `member-1`, `member-2`, `member-3`이다. 리스너 팩토리 `orderEventsListenerContainerFactory`의 consumer 팩토리로 만든다. 그 consumer 팩토리는 빈이 아니라 팩토리 안에서 만든 것이라 팩토리에서 꺼낸다. 그래서 consumer 설정(`ByteArrayDeserializer`, auto-commit 꺼짐, `earliest`)은 앱의 것이고, 컨테이너 설정(concurrency 3, 멈추는 handler, 변환기)은 쓰지 않는다. 단독 컨테이너는 client ID에 접미사를 붙이지 않아 broker가 member를 `member-N`으로 부른다.
- **기다림과 표.** member를 하나 더할 때마다 broker의 `describeConsumerGroups`가 `Stable`이고 member가 그 수일 때까지 기다린다(30 s). 그 설명에서 member → 파티션 표를 얻고, 컨테이너마다 배정된 파티션을 그 표와 견준다. 리더가 아닌 member는 broker가 `Stable`이 된 조금 뒤에 SyncGroup의 답으로 배정을 알아서, 이 견줌도 한도 안에서 기다린다.
- **probe.** 크기마다 파티션 0과 1에 key 없이 번호로 record를 하나씩 보낸다. probe 리스너는 읽었다고 표시한 뒤에 읽은 것을 적는다. `MANUAL_IMMEDIATE`라 장면이 본 읽기의 offset은 이미 커밋돼 있고, 다음 member의 가입이 그 record를 다른 member에게 넘기지 않는다. 읽은 member는 셋째 member가 든 뒤에 센다. 넘겼다면 그때 둘이 보인다.
- **단언.** 크기마다 두 파티션의 member가 정확히 하나다. 셋에서 파티션 없는 member가 있다. probe마다 읽은 member는 그 파티션의 member 하나뿐이다. 어느 member가 어느 파티션을 받는지는 단언하지 않는다. assignor가 정하기 때문이다.
- **클라이언트의 기본값**(kafka-clients 4.2.2의 소스). 팩토리는 둘 다 두지 않는다.
  - `group.protocol`은 `classic`이다(`ConsumerConfig.DEFAULT_GROUP_PROTOCOL`, 115행).
  - `partition.assignment.strategy`는 `[RangeAssignor, CooperativeStickyAssignor]`다(446–448행).
  - client의 rebalance 프로토콜은 목록의 모든 assignor가 지원하는 가장 새것이다(`ConsumerCoordinator` 256–270행). `RangeAssignor`는 EAGER만 지원하므로 eager다. 가입마다 group의 모든 member가 가진 파티션을 내려놓고 다시 받는다. 한 실행의 로그에서 둘째와 셋째 가입 때 먼저 든 member가 모두 `partitions revoked`를 남겼다.
  - 모든 member가 `range`를 첫째로 적으므로 coordinator가 그것을 고른다. broker의 설명도 `type=Classic, assignor=range`다.
  - range는 member ID의 차례로 나눈다. broker는 member ID를 client ID와 UUID로 만들므로(`member-1-ce26db2b-…`) 여기서는 `member-1`이 파티션 0을 받는다. 그것은 assignor의 선택이지 group의 약속이 아니다.
- **까닭.** consumer가 파티션보다 많으면 남는 member는 아무것도 읽지 않는다. 파티션 수가 consumer 병렬성의 상한이다. 운영의 `order-events`가 파티션 3이고 리스너의 concurrency가 3인 까닭이다([주문 이벤트 설계](./order-events.md) 7절).

### 2.2 key → 파티션 (E2)

- **토픽과 템플릿.** 토픽은 운영과 같은 3개 파티션이다. 템플릿은 컨텍스트의 `KafkaTemplate`이다. `modules:kafka`의 `KafkaConfig`가 만드므로 commerce-api의 중계와 producer 설정과 기본 partitioner가 같다. 그래서 실험을 commerce-api로 옮기지 않았다. 네 실험이 한 앱의 테스트에 모이고, 실험 도우미와 기반을 함께 쓴다.
- **key.** 계약 꼴의 key, 곧 주문 ID의 문자열 다섯(`1`, `2`, `42`, `1000`, `123456789`)이다. 각 key를 세 번씩 보내고, send마다 끝나기를 기다린다. 파티션과 offset은 `SendResult`의 metadata에서 읽는다.
- **단언.** 한 key의 모든 send가 첫 send의 파티션에 갔다는 것뿐이다. 다른 key가 흩어진다고는 단정하지 않는다. 한 실행에서 key 둘씩이 한 파티션을 나눴다(글감의 issue 185 절).
- **partitioner**(kafka-clients 4.2.2의 소스). `partitioner.class`의 기본값은 null이다. record에 파티션 인자가 없고 key가 있으면 `KafkaProducer.partition`이 `BuiltInPartitioner.partitionForKey`를 부른다. 곧 `toPositive(murmur2(key의 바이트)) % 파티션 수`다. 그래서 같은 key가 같은 파티션에 가는 것은 토픽의 파티션 수가 그대로일 때만이다. 파티션을 늘리면 한 주문의 이벤트가 다른 파티션으로 갈 수 있다. 지금은 한 주문에 이벤트가 하나라 그 차례가 쓰이지 않는다([주문 이벤트 설계](./order-events.md) 7절).

### 2.3 커밋 뒤 다시 전달 (E3)

- **기반.** `BaseConsumerTest`다. 기록이 실제로 커밋돼야 하고, 표를 비우는 정리가 있어서다. 기반이 요구하는 클래스 토픽(`order-events-redelivery-after-commit`)은 두지만 실험의 consumer는 `ExperimentBroker`가 만든, 파티션이 하나인 토픽과 그 group을 읽는다. 기반의 리스너 컨테이너가 실험의 record를 먼저 기록하지 않게 하려서다.
- **consumer와 리스너.** consumer는 컨테이너 없이 리스너 팩토리의 consumer 팩토리로 만든 평범한 `Consumer`다(`ByteArrayDeserializer`, `enable.auto.commit=false`, client ID `redelivery-first`와 `redelivery-second`). record는 컨테이너가 넘기듯 공유 `ByteArrayJacksonJsonMessageConverter`의 `toMessage`로 `OrderEventMessage`가 된다. 그 메시지의 key·파티션·offset 헤더로 `OrderEventsListener` 빈의 `listen`을 직접 부른다. 이벤트 종류 검사, schemaVersion 검사, 기록, 로그 줄이 모두 실제 것이고, 운영 코드는 바꾸지 않았다. 무엇을 커밋할지는 테스트의 `Acknowledgment`가 정한다.
- **차례.** 과제의 여섯 단계다.
  1. 다른 주문의 record를 offset 0에, 이벤트의 record를 offset n = 1에 보낸다. 둘 다 send metadata에서 읽는다.
  2. 첫 consumer가 구독하고, `ConsumerRebalanceListener.onPartitionsAssigned`에서 n을 커밋한다. client는 그 뒤에 새 파티션의 커밋된 offset을 가져오므로, seek 없이 n부터 읽는다.
  3. 리스너가 기록하고(`RECORDED`) DB가 커밋된다. 테스트의 `Acknowledgment`는 불렸다는 것만 적는다. broker의 커밋된 offset은 n이다.
  4. 첫 consumer를 `close(CloseOptions.timeout(10 s))`로 닫는다. 닫기 전 그 consumer의 위치는 n+1이었고, 닫은 뒤 broker의 커밋된 offset은 여전히 n이다.
  5. 같은 group의 둘째 consumer가 seek나 reset 없이 들어와 같은 파티션과 offset n을 받는다.
  6. 리스너가 같은 사실로 보고 아무것도 쓰지 않는다(`ALREADY_RECORDED`). 테스트의 `Acknowledgment`가 둘째 consumer로 n+1을 동기 커밋한다.
- **앞의 record.** 여섯 단계에는 없던 것이다. n = 0이면 가장 이른 offset과 n이 같은 수라, "둘째 consumer가 n을 받았다"가 `auto.offset.reset=earliest`로도 설명된다. 첫 green 실행이 그랬다. 앞에 다른 주문의 record가 있으면 reset이나 되감기는 그 record를 받아 단언이 실패한다(글감의 issue 188 절, 탐침 (c)).
- **파티션이 하나인 토픽.** group이 아무것도 커밋하지 않은 파티션에서 consumer는 가장 이른 offset으로 reset한다. 파티션이 하나면 그런 파티션이 없고, "같은 파티션"은 저절로 참이다. 증거의 무게는 offset에 있다.
- **닫기가 커밋하지 않는 까닭**(kafka-clients 4.2.2의 소스). `ConsumerCoordinator.close`는 `maybeAutoCommitOffsetsSync`로만 커밋하고, 그것은 auto-commit이 꺼져 있으면 아무것도 하지 않는다. 장면은 비동기 커밋도 남기지 않는다. `close(Duration)`은 kafka-clients 4.1부터 deprecated라 `CloseOptions`를 쓴다.
- **스레드.** consumer의 `poll`은 Awaitility의 `pollInSameThread()`로 테스트 스레드에서만 부른다. Kafka consumer는 두 스레드가 함께 쓸 수 없고, Awaitility는 조건을 제 스레드에서 돌려 한도를 넘긴 대기가 `poll` 안에 남은 채 `@AfterEach`가 consumer를 닫을 수 있다. 한 번의 `poll`이 가입, 커밋된 offset의 조회, record의 반환을 함께 할 수 있으므로, 가져온 record는 장면이 꺼낼 때까지 쌓아 둔다.
- **행.** 두 표를 행의 ID와 `updated_at`을 넣은 줄로 첫 전달 뒤와 끝에 읽어 견준다. 같은 값으로 다시 쓴 갱신도 드러난다.

### 2.4 실제 리스너의 실패 (E4)

- **자리.** 클래스 토픽 `order-events-listener-failure`, 레지스트리의 `order-events` 컨테이너, group `commerce-streamer`다. 두 record는 기반 `publish`의 파티션 인자로 파티션 0에 보낸다. 한 파티션의 커밋된 offset이 이야기 전체를 말한다.
- **차례.**
  1. 정상 이벤트를 offset 0에 보내고 커밋을 기다린다. 그래서 실패할 record의 offset n = 1이 멈추기 전에도 멈춘 뒤에도 커밋된 offset이다.
  2. 테스트의 `DataSource`에서 따로 얻은 JDBC 연결이 트랜잭션을 열고, 실패할 주문의 둘째 품목의 `purchase_activity` 행을 넣고 커밋하지 않는다.
  3. 품목이 둘인 이벤트를 offset n에 보낸다. 리스너의 트랜잭션은 처리한 주문 확정과 첫 품목을 넣은 뒤 둘째 품목의 유일 키를 기다린다.
  4. 3 s 뒤 MySQL이 1205(`Lock wait timeout exceeded`)로 실패시킨다. 풀의 연결마다 세션 변수 `innodb_lock_wait_timeout=3`이 있다([ADR 0018](../adr/0018-pessimistic-row-locks-in-one-global-order.md) 규칙 7). 트랜잭션 전체가 롤백되고, 리스너가 실패 줄을 남기고 다시 던지며, `CommonContainerStoppingErrorHandler`가 세 consumer를 모두 세운다.
  5. 막던 연결을 롤백해 실패를 걷어내고, 컨테이너를 `start()`로 같은 group에서 다시 시작한다. 같은 record가 같은 파티션과 offset으로 다시 전달돼 기록되고(`RECORDED`), 커밋된 offset이 n+1이 된다.
- **예외의 타입.** Hibernate 7.4는 1205를 `LockTimeoutException`으로 읽고, 그것은 `LockAcquisitionException`의 하위다. Spring 7.0은 `LockAcquisitionException`을 `CannotAcquireLockException`으로 번역한다. `DataIntegrityViolationException`이 아니므로 리스너의 `recordSettlingARace`는 다시 부르지 않고 곧바로 나간다. 원인 사슬은 `KafkaException: Stopped container` → `ListenerExecutionFailedException` → `CannotAcquireLockException` → `LockTimeoutException` → `MySQLTransactionRollbackException`이다.
- **멈춘 동안의 단언.** 실패한 주문의 행은 두 표 모두 없고 정상 이벤트의 행만 있다. 실패 줄이 인용한 문장이 `insert into purchase_activity`다. 확정의 삽입은 이미 지나갔다는 뜻이니 쓰기는 부분이었고 모두 롤백됐다. 커밋된 offset은 n이다. 컨테이너가 서고 consumer가 모두 닫혔으며 `isInExpectedState`가 false다(비정상 멈춤). 실패 줄이 정확히 하나이고 그 record의 파티션과 offset을 적는다. 넣은 뒤 멈추기까지는 3 s 이상이다. 기다림은 아래로만 단언하고, 위는 기반의 멈춤 기다림(10 s)이 막는다.
- **실패 줄.** `Could not record an order confirmed event; the consumer stops until it is restarted. eventId=…, orderId=…, partition=…, offset=…, cause=…`. 리스너가 거절 줄의 두 타입(`UnprocessableOrderEventException`, `ConstraintViolationException`) 밖의 모든 `Exception`에 남기고 다시 던진다. 거절 줄 `Refused an order confirmed event that cannot be processed. …`은 그대로다. 두 줄의 문구가 "이벤트가 틀렸다"와 "원인을 고치고 다시 시작하라"를 가른다. orderId는 거절 줄처럼 record의 key에서, 원인은 예외의 `toString`에서 읽는다. `recordSettlingARace`의 두 번째 위반도 이 줄이다. 변환기의 `ConversionException`은 리스너에 닿지 않아 spring-kafka의 `Error handler threw an exception`뿐이고, `acknowledge()`의 예외는 기록이 커밋된 뒤라 기록하지 못한 것이 아니어서 try 밖에 둔다.
- **정책.** 이 실험은 오늘의 멈춤 정책(ADR 0033 규칙 5)을 단언한다. 정책이 `DefaultErrorHandler`와 backoff로 바뀌면 이 실험도 함께 바뀐다. 일시 장애도 세 파티션을 모두 세운다는 한계는 [구매 활동 설계](./purchase-activity.md) 9절에 있다.

## 3. 토픽과 group

| 실험 | 토픽 | 파티션 | group | 만들고 지우는 쪽 |
| --- | --- | --- | --- | --- |
| E1 | `consumer-group-assignment-<8자>` | 2 | `consumer-group-assignment-<8자>` | `ExperimentBroker` |
| E2 | `key-partitioning-<8자>` | 3 | 없음 | `ExperimentBroker` |
| E3 | `redelivery-after-commit-<8자>` | 1 | `redelivery-after-commit-<8자>` | `ExperimentBroker` |
| E3의 기반 | `order-events-redelivery-after-commit` | 3 | `commerce-streamer` | `TestOrderEventsTopicConfig`가 선언하고 지우지 않는다 |
| E4 | `order-events-listener-failure` | 3 | `commerce-streamer` | `TestOrderEventsTopicConfig`가 선언하고 지우지 않는다 |

- **`ExperimentBroker`.** 실험 클래스가 `@BeforeEach`에서 컨텍스트의 `KafkaAdmin`으로 연다. 빈이 아니라 테스트마다 여는 평범한 클래스라, 두 기반의 하위 클래스가 캐시된 컨텍스트를 나누지 않고 쓴다.
  - `createTopic(prefix, partitions)`은 이름에 `UUID`의 앞 8자를 붙여 replica 1로 만들고, broker가 그 파티션 수로 설명할 때까지 기다린다(`describeTopics`, 10 s).
  - `nameGroup(prefix)`은 같은 꼴로 group의 이름을 짓는다.
  - `describeGroup(group)`은 broker의 group 설명이다. broker가 모르는 group이면 null이다. 4.x broker는 그때 `GroupIdNotFoundException`으로 답한다.
  - `committedOffset(group, TopicPartition)`은 broker의 `listConsumerGroupOffsets`다. 커밋이 없으면 null이다. Kafka 4.3 broker는 아무도 들지 않은 group에 빈 맵으로 답한다. (2026-10-29: 4.4.0 broker에서도 `ExperimentBrokerTest`가 그 group의 커밋된 offset을 null로 읽는다.)
  - `close()`는 지은 group을 한 번의 `deleteConsumerGroups`로 지우고 그 결과를 group마다 읽은 뒤(아무도 들지 않은 group의 `GroupIdNotFoundException`은 건너뛴다) 만든 토픽을 지우고, broker가 토픽을 모를 때까지 기다린다. 두 번 닫아도 한 번만 지운다. member가 남은 group은 broker가 지우기를 거절하므로, 실험은 consumer와 컨테이너를 모두 닫은 뒤에 도우미를 닫는다.
- **실험의 record는 업무 토픽에 닿지 않는다.** 과제가 그렇게 요구한다. 실험의 토픽은 모두 `order-events`가 아니다. E1–E3의 record는 어느 리스너의 토픽에도 닿지 않고, E3의 record는 기반의 클래스 토픽에도 가지 않는다. E4만 다르다. 실제 리스너가 읽어야 하므로 record를 클래스 토픽에 보내고, 그 토픽과 group `commerce-streamer`의 offset은 `ExperimentBroker`가 아니라 2단계의 소비 장면처럼 남는다. 클래스 토픽은 2단계처럼 클래스마다 고유하다.
- **클래스 토픽.** `BaseConsumerTest`의 클래스마다 `@TestPropertySource`로 `order-events.topic`을 덮고, `TestOrderEventsTopicConfig`가 그 이름으로 3개 파티션의 토픽을 선언해 컨텍스트의 `KafkaAdmin`이 만든다([구매 활동 설계](./purchase-activity.md) 7절). 지우지 않고, broker 컨테이너와 함께 사라진다.
- **Spring 컨텍스트.** E1, E2와 `ExperimentBrokerTest`는 롤백하는 기반의 캐시된 컨텍스트를 2단계의 다섯 클래스와 함께 쓴다. E3과 E4는 클래스 토픽이 달라 저마다 컨텍스트를 띄운다. streamer의 컨텍스트는 모두 여섯이다.

## 4. 증거의 규칙

- **증거로 치는 것.** broker와 DB가 보는 것이다.
  - broker의 group 설명: `describeConsumerGroups`의 상태, member, 배정(E1).
  - broker에서 읽은 커밋된 offset: 실험의 group은 `ExperimentBroker.committedOffset`(E3), `commerce-streamer`는 기반의 `committedOffset(partition)`, 곧 `KafkaTestUtils.getCurrentOffset`(E4).
  - send metadata: `SendResult`의 파티션과 offset(E2, E3, E4).
  - 두 표의 행과 리스너의 로그 줄(E3, E4).
- **치지 않는 것.** consumer나 컨테이너 객체의 수, consumer의 위치(`position`), poll이 돌아온 횟수, 컨테이너의 내부 필드다. E3은 첫 consumer의 위치(n+1)를 닫기에 커밋할 것이 있었다는 맥락으로만 적는다. member ID는 client가 쥔 broker의 답이라 적기만 한다. E1은 컨테이너의 배정을 broker의 표와 견줄 뿐, 그것으로 member를 세지 않는다.
- **다시 전달은 offset으로만 증명한다.** 같은 파티션과 offset의 두 전달, 그리고 broker에서 읽은 커밋된 offset이다. 과제의 n → n → n+1은 첫 읽기 전, 첫 consumer를 닫은 뒤, 둘째 읽기 뒤이고, 장면은 첫 읽기 뒤에도 한 번 더 읽어 n → n → n → n+1을 본다. 리스너의 `ALREADY_RECORDED` 줄이나 poll 위치만으로는 다시 전달의 증거로 치지 않는다. 같은 줄은 다시 발행으로도 남는다.
- **기다림은 모두 한도가 있다.** Awaitility로 group은 30 s, record와 send와 admin 호출은 10 s, 멈춤은 10 s, 닫기는 10 s다. poll 없이 잠들지 않는다. 깨진 실험은 걸려 있지 않고 실패한다.
- **red check.** 장면이 빨개져야 할 변이를 잠시 넣어 돌리고 바이트 그대로 되돌렸다. 커밋된 변이는 없다. 실험마다의 red check와 그 실행 결과는 글감의 티켓 절("Red checks and probes")에 있고, 세 단계의 red check를 모은 표는 글감의 ["Counterexamples"](../research/week6-kafka-delivery-writing-material.md#counterexamples-the-assignments-three-answered-by-a-test-or-a-red-check)에 있다.

## 5. 설정과 한계

- **`acks: all`은 leader 하나의 응답이다.** `kafka.yml`의 producer가 `acks: all`을 둔다. broker가 하나(`apache/kafka-native:4.3.2`의 단일 노드)이고 replica가 1이라 in-sync replica는 leader뿐이다. 그래서 `acks=1`보다 강하지 않다. 복제, `min.insync.replicas`, leader의 failover는 시험하지 않았다([주문 이벤트 설계](./order-events.md) 5절, 9절). (2026-10-29: 이미지는 `apache/kafka-native:4.4.0`이다([주문 이벤트 설계](./order-events.md) 8절). 단일 노드인 것은 같다.)
- **테스트의 끝점과 정리.** broker 주소는 `modules:kafka` testFixtures의 `KafkaTestContainersConfig`가 `DynamicPropertyRegistrar`로 `spring.kafka.bootstrap-servers`에 넣은 컨테이너의 주소뿐이다. 실험은 그것을 컨텍스트의 `KafkaAdmin`(도우미)과 `KafkaProperties`(기반)에서 읽고, 리터럴 주소를 받지 않는다. 그래서 `kafka:9092`와 `localhost:19092`를 섞지 않는다. 컨테이너는 JVM마다 하나이고 멈추는 코드가 없다. JVM이 끝나면 Testcontainers의 Ryuk가 걷는다. E1–E3은 제 토픽과 group을 `@AfterEach`에서 `ExperimentBroker`로 지우므로, 그 실험이 끝난 broker는 실험 전과 같다. E4는 2단계의 소비 장면처럼 클래스 토픽을 쓰므로 그 토픽과 group `commerce-streamer`의 offset이 남고, 컨테이너와 함께 사라진다(3절). `test` 프로필은 주소를 두지 않고 `fail-fast`를 켜므로, fixture를 빠뜨린 컨텍스트는 뜨지 않는다([주문 이벤트 설계](./order-events.md) 7절).
- **local 프로필의 두 주소.** `kafka.yml`의 `local`은 bootstrap을 `localhost:19092`로 둔다. compose(`docker/infra-compose.yml`)의 broker는 두 리스너를 알린다. `PLAINTEXT://kafka:9092`는 compose 망 안의 것이고, `PLAINTEXT_HOST://localhost:19092`는 호스트의 것이며 `127.0.0.1`에만 묶였다. 호스트에서 뜬 앱은 19092로 붙고 broker가 알리는 주소도 `localhost:19092`라 맞는다. 같은 문서의 `admin.properties.bootstrap.servers: kafka:9092`는 쓰이지 않는다. Boot가 admin의 `bootstrap.servers`를 연결 정보(`KafkaConnectionDetails`)의 공통 주소로 다시 쓰기 때문이다([주문 이벤트 설계](./order-events.md) 7절의 티켓 168 메모). 템플릿의 줄이라 고치지 않고 적어 둔다.
- **fetch 설정은 클라이언트의 기본값이다.** `kafka.yml`과 streamer의 `application.yml`은 `fetch.min.bytes`와 `fetch.max.wait.ms`를 두지 않는다. kafka-clients 4.2.2의 기본값은 1 byte와 500 ms다(`ConsumerConfig.DEFAULT_FETCH_MIN_BYTES`, `DEFAULT_FETCH_MAX_WAIT_MS`). broker는 record가 하나라도 있으면 곧 답한다. 과제가 경고하는 `fetch.min.bytes=1MiB`와 `fetch.max.wait.ms=5000`의 늦은 전달은 이 저장소에 닿지 않는다. 조정은 범위 밖이다.
- **group 프로토콜과 assignor도 클라이언트의 기본값이다.** classic 프로토콜, `[RangeAssignor, CooperativeStickyAssignor]`, eager rebalance, range다(2.1절). 새 group 프로토콜(KIP-848, `group.protocol=consumer`)은 실험하지 않았다. 명세 184는 kafka-clients 4.2.1이라 적었지만, Boot 4.1.2로 올린 뒤의 classpath는 4.2.2다.
- **같은 key가 같은 파티션에 가는 것은 파티션 수가 그대로일 때뿐이다.** 2.2절.
- **남은 DB → broker 구간.** 3단계는 broker에서 streamer까지의 전달을 봤다. commerce-api의 커밋 뒤 전송 전 구간은 그대로다. TX B가 커밋한 뒤 비동기 중계가 `send`를 마치기 전에 프로세스가 죽으면 이벤트를 잃고, WARN도 남지 않는다([ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md), [주문 이벤트 설계](./order-events.md) 4절, 6절). 1단계의 미발행 사례가 그 재현이다. 7주차의 outbox가 TX B 안에서 outbox 행을 써서 그 구간을 닫는다.
- **통제된 멈춤만 봤다.** E3의 첫 consumer는 한도 있는 `close`로 group을 떠났다. E4의 consumer는 error handler가 세웠고, 다시 시작은 레지스트리의 컨테이너 `start()`였다. 기록 도중에 프로세스를 죽이는 실험(`kill -9`, 컨테이너의 강제 종료)은 없다. 갑자기 죽은 consumer는 `session.timeout.ms`(kafka-clients 4.2.2의 기본 45 s) 뒤에야 group에서 빠지고, 그동안 그 파티션은 아무도 읽지 않는다. 커밋하지 않은 record가 다시 전달된다는 결과는 E3과 같아야 하지만 시험하지 않았다.
- **소비 사례는 두 앱의 실행을 이은 것이다.** 7주차에 넘기는 소비 사례([구매 활동 설계](./purchase-activity.md) 8절)는 streamer의 테스트가 손으로 쓴 record를 넣은 것이다. 커머스 쪽 값(PG 주문 번호, 거래 키)은 1단계의 미발행 사례의 실행에서 가져왔다. 두 앱을 한 실행으로 잇는 사례는 없다. 이 저장소에는 PG 시뮬레이터가 없다(`pg.base-url`은 `PG_BASE_URL`에서 온다). 두 사례를 나란히 둔 것은 글감의 ["W7 handoff: both cases side by side"](../research/week6-kafka-delivery-writing-material.md#w7-handoff-both-cases-side-by-side)다.
- **broker는 하나다.** 여러 broker, 복제, rebalance 중의 broker 장애는 실험하지 않았다.

## 6. 과제의 이름과 이 저장소의 이름

| 과제 | 이 저장소 | 까닭 |
| --- | --- | --- |
| 파티션 2개, consumer 1→2→3 | 배정 실험 `ConsumerGroupAssignmentTest`(E1) | member는 broker의 group 설명으로 센다. |
| key → partition metadata | key → 파티션 실험 `KeyPartitioningTest`(E2) | 파티션은 `SendResult`의 metadata다. |
| DB 커밋 뒤 offset 미커밋 재전달 | 다시 전달되다, `RedeliveryAfterCommitTest`(E3) | 용어집이 다시 발행하다와 가른다. 2단계의 `the same event published twice …`는 다시 발행이다. |
| 실제 리스너의 실패·롤백·재시작 | 실패 실험 `OrderEventsListenerFailureTest`(E4)와 실패 줄 | 일시 장애다. 처리할 수 없는 이벤트의 멈춤은 2단계의 멈춤 장면이 본다. |
| 격리 토픽 | 실험의 토픽(`ExperimentBroker.createTopic`) | 실험마다 만들고 지운다(3절). |
| 재전달, 중복 | 다시 전달되다, 다시 발행하다 | 같은 record의 같은 자리인지, 같은 이벤트의 새 record인지로 가른다. 둘 다 구매 활동에는 같은 사실로 온다([구매 활동 설계](./purchase-activity.md) 10절). |
| 중단, 재시작 | 멈추다, 다시 시작하다(`start()`) | 용어집의 멈추다는 기록하지 못한 모든 경우다. |
| ack | 읽었다고 표시하다 | `Acknowledgment.acknowledge()`. |
| handler | 리스너 `OrderEventsListener`와 error handler `CommonContainerStoppingErrorHandler` | 과제의 handler는 둘을 함께 가리킨다. |
| 대표 주문의 구매 활동 행 | 소비 사례의 행 | 2단계가 기록한 실행이다(5절). |
| AI 검토의 반례 | 반례 셋과 red check | 글감의 "Counterexamples". |

## 7. 3단계의 명령

```bash
# 배정(E1, 이음매 A): ConsumerGroupAssignmentTest(1). 크기마다 INFO 한 줄.
./gradlew :apps:commerce-streamer:test --tests '*ConsumerGroupAssignmentTest'

# key → 파티션(E2, 이음매 A): KeyPartitioningTest(1). key → 파티션 표를 INFO 한 줄로.
./gradlew :apps:commerce-streamer:test --tests '*KeyPartitioningTest'

# 커밋 뒤 다시 전달(E3, 이음매 B): RedeliveryAfterCommitTest(1)
./gradlew :apps:commerce-streamer:test --tests '*RedeliveryAfterCommitTest'

# 실제 리스너의 실패(E4, 이음매 C): OrderEventsListenerFailureTest(1). 잠금 대기 3 s가 있다.
./gradlew :apps:commerce-streamer:test --tests '*OrderEventsListenerFailureTest'

# 실험 도우미: ExperimentBrokerTest(7)
./gradlew :apps:commerce-streamer:test --tests '*ExperimentBrokerTest'

# 끝의 확인. ktlint는 두 앱과 kafka 모듈이고, 수는 cleanTest부터 돈 실행의 JUnit XML 보고서에서 읽는다.
./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-streamer:ktlintCheck :modules:kafka:ktlintCheck
./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:check
scripts/test-counts --results apps/commerce-streamer/build/test-results/test
./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:check
scripts/test-counts
```

- 괄호의 수는 2026-10-29 `756f0b7`의 트리에서 끝의 확인을 돌려, JUnit XML 보고서에서 `scripts/test-counts`로 읽은 것이다. ktlint 셋이 통과했다. streamer는 18개 클래스, 67개 테스트, 실패 0, 오류 0, 건너뜀 0이다(56 s). commerce-api는 79개 클래스, 943개 테스트, 실패 0, 오류 0, 건너뜀 0으로 2단계의 끝과 같다(3 min 21 s). 셸에 `BOOTSTRAP_SERVERS`는 없었다. 두 실행 모두 테스트 broker에 붙었고, 실험 넷이 건너뛰지 않고 돌았다. commerce-api의 코드는 이 브랜치에서 바뀌지 않았지만, Boot 4.1.2와 Gradle 9.8.2의 판 올림(1.2절) 뒤라 다시 돌렸다. `756f0b7` 뒤로는 문서만 바뀌었다. 위 블록의 거른 명령도 같은 날 하나씩 돌려 같은 수를 읽었다. (2026-10-29, [명세 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184)의 검토: 검토의 고침(`3aa0b1d`부터 `5bfe295`까지) 위에서 이미지를 `apache/kafka-native:4.4.0`으로 올린 트리에서 끝의 확인을 다시 돌렸다. ktlint 셋이 통과했고, streamer는 18개 클래스, 67개 테스트, commerce-api는 79개 클래스, 943개 테스트로 위와 같으며 실패, 오류, 건너뜀은 모두 0이다. 두 앱의 컨텍스트가 모두 4.4.0 컨테이너에 붙었다. 네 실험의 한 실행(글감)은 `756f0b7`과 4.3.2의 것 그대로다.)
- 3단계가 더한 테스트는 streamer의 11개이고, 클래스는 다섯이다. 티켓마다 185가 5개(`ExperimentBrokerTest` 4, `KeyPartitioningTest` 1), 186이 3개(`ConsumerGroupAssignmentTest` 1, `ExperimentBrokerTest` 2), 187이 1개(`OrderEventsListenerFailureTest`), 188이 2개(`RedeliveryAfterCommitTest` 1, `ExperimentBrokerTest` 1)다. 2단계의 끝은 13개 클래스, 56개 테스트였다.
- 네 실험의 한 실행은 끝의 확인의 streamer 실행이다. 같은 JVM, 같은 broker에서 넷이 돌았고, 그 INFO 줄을 JUnit XML 보고서의 `system-out`에서 옮겼다(글감의 "One run of the four experiments").
- 거른 실행은 보고서 디렉터리를 그 실행의 것으로 갈아 치운다. 수는 끝의 확인 바로 뒤에 읽는다. streamer의 필터는 아무것도 찾지 못하면 `No tests found for given includes`로 실패한다([구매 활동 설계](./purchase-activity.md) 11절).
- 컨텍스트를 띄우는 명령은 Docker가 있어야 한다. MySQL과 `apache/kafka-native:4.3.2` 컨테이너를 JVM마다 하나씩 띄운다. (2026-10-29: Kafka 컨테이너는 `apache/kafka-native:4.4.0`이다.)
