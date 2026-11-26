# 결제 사고 설계 — 끝나지 않은 결제를 지표로 알리고, 사고를 재현해 복구한다

10주차 과제의 설계 문서다. 브랜치마다 절 하나를 쓴다(`weekly-ranking.md`의 방식). 용어는 [`CONTEXT.md`](../../CONTEXT.md)를 따른다("끝나지 않은 결제", "결과 미확인", "처리 중", "대사"). 결정의 출처는 1단계가 [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266)이다.

상태: 2026-11-26 1단계(`feat/payment-observability`)를 인터뷰했다. 이 문서는 그 첫 티켓([issue 267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267))이 뼈대로 만들었고, 절의 내용은 단계를 구현한 뒤 쓴다. (2026-11-26 [issue 272](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/272): 1단계를 구현했고 2절에 썼다.)

## 1. 근거와 범위

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 끝나지 않은 결제의 수를 gauge로 알리고, PG의 답과 TX B의 결과를 주문별 로그 이벤트로 남긴다. 실제 포트의 테스트 기반. | `feat/payment-observability` | 인터뷰 끝남(2026-11-26). [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266). 구현 끝남(2026-11-26, 2절) |
| 2 | 사고의 재현(정상 10건과 장애 10건), 가설의 판정, 대사 API로 하는 복구, 증거 파일. | `feat/payment-incident` | 인터뷰 전 |
| 3 | 아웃박스 → Kafka → 구매 활동 → 주간 랭킹의 후속 확인, `docs/runbook/week10.md`, 기능별 검증 범위와 남은 위험. | `feat/payment-incident-follow-on` | 인터뷰 전 |

셋은 모두 `week10`으로 PR한다.

### 1.2 1단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267) | 실제 포트의 테스트 기반 `BaseRealPortWebApiAdapterTest`와 `ManagementEndpointE2ETest`. 운영 코드는 없다. |
| [268](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/268) | `PaymentFinder.countUnresolved`(결과 미확인과 처리 중의 수, ADR 0014의 예외)와 `IDX_PAYMENT_ON_STATUS`. 이주 SQL `docs/migrations/2026-11-26-payment-status-index.sql`. |
| [269](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/269) | gauge `commerce.payment.unresolved`(`status`가 `UNKNOWN`, `PENDING`)의 구동 어댑터 `adapter/monitoring`의 `UnresolvedPaymentGauge`. 설정 `payment.unresolved-gauge.enabled`(`test`에서 끔)와 `refresh-interval`(10 s). `UnresolvedPaymentGaugeE2ETest`가 실제 scrape로 읽는다. |
| [270](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/270) | `OrderPaymentSaga`가 PG의 답마다 남기는 이벤트 일곱(`pg.create.*`, `pg.lookup.*`). 고정된 문장과 key-value(`event`, `trigger`, `orderId`, `paymentId`, `pgOrderId`와 이벤트마다 더하는 필드). `OrderPaymentLogEventTest`가 잡은 key-value로 읽는다. |
| [271](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/271) | TX B의 결과 이벤트 둘. 커밋한 뒤의 `payment.apply.committed`(`result`가 `changed`·`unchanged`·`conflicted`, 바뀌면 `from`과 `to`. 충돌이면 WARN)와, 되돌아간 TX B의 `payment.apply.failed`(WARN, 예외를 실은 뒤 그대로 다시 던진다). TX B의 두 입구는 `OrderInfo`와 `ReconcileResult`를 담은 `AppliedAnswer`를 돌려준다. |

티켓마다 구현 중에 정한 것은 [글감](../research/week10-payment-observability-writing-material.md)에 있다.

## 2. 1단계: 끝나지 않은 결제의 지표와 결제의 로그 이벤트

결정의 출처는 [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266)이다(2026-11-26 인터뷰, 질문 25개). 과제의 몫은 2절이다. ADR은 더하지 않았다. gauge의 출처, label, 로그의 모양은 모두 커밋 하나로 바꿀 수 있다. 용어집은 "끝나지 않은 결제"를 더했다: 결제 상태가 결과 미확인이거나 처리 중인 결제다. 운영 코드는 commerce-api만 바꿨다. 릴레이, commerce-streamer, commerce-batch에는 로그도 지표도 더하지 않았다.

health는 바꾸지 않았다. 사고 동안 UP이라고 답하는 것이 과제가 보려는 것이고, 끝나지 않은 결제로 DOWN이 된 인스턴스는 load balancer에서 빠져 그 결제를 끝낼 대사 요청도 받지 못한다. `monitoring.yml`의 노출 범위(health, prometheus)도 그대로다.

### 2.1 지표

| 항목 | 값 |
| --- | --- |
| 이름 | `commerce.payment.unresolved`. Prometheus에서는 `commerce_payment_unresolved` |
| 종류 | gauge |
| label | `status` 하나. 값은 `UNKNOWN`과 `PENDING` 둘뿐이고, 두 시계열은 수가 0이어도 있다 |
| 출처 | 결제의 행. 예약된 갱신이 주기마다 `PaymentFinder.countUnresolved()`로 상태별 수를 한 번 세어 보관하고, scrape는 보관한 수를 읽는다. scrape가 질의를 돌리지 않고, 코드가 메모리에서 수를 올리고 내리지도 않는다 |
| 주기 | 설정 `payment.unresolved-gauge.refresh-interval`, 기본값 10초. `fixedDelay`라 앞 갱신이 끝난 때부터 센다 |
| 값이 없을 때 | 첫 갱신이 성공하기 전과 갱신이 실패한 뒤에는 두 시계열 모두 `NaN`이다. 실패는 WARN과 예외로 남고, 갱신이 예외를 직접 잡으므로 다음 주기가 다시 센다. 다음 성공이 값을 되돌린다 |
| 읽는 법 | 인스턴스마다 DB 전체의 같은 수를 알리므로 `max`로 읽는다. `sum`은 인스턴스의 수만큼 부풀린다 |

- 설명(`# HELP`)에 셋을 적었다: 끝나지 않은 결제의 수라는 것, 값이 갱신 주기만큼 늦을 수 있다는 것, `max`로 읽어야 한다는 것.
- 메모리의 counter가 아니라 DB에서 센다. 프로세스가 본 사건(요청 수, 시간 초과)은 메모리에서 세지만, 여러 인스턴스가 함께 쓰는 표의 현재 수는 DB에 물어야 한다. 메모리에서 올리고 내린 수는 재시작에 0이 되고, 결제를 시작한 인스턴스와 끝낸 인스턴스가 다르면 어긋난다.
- 갱신은 수 하나(`UnresolvedPaymentCount?`)를 `AtomicReference` 하나에 보관하고, 두 시계열이 그것을 읽는다. 그래서 한 scrape가 두 갱신의 수를 섞지 않는다. `null`이 두 시계열의 `NaN`이다. gauge는 Micrometer의 supplier 꼴로 등록한다. `obj, fn` 꼴은 상태를 약하게 잡는다.

| 부분 | 클래스 | 하는 것 |
| --- | --- | --- |
| 구동 어댑터 | `adapter.monitoring.UnresolvedPaymentGauge` | `@Scheduled` 갱신과 Micrometer 등록. 제 `@EnableScheduling`을 가진다. Micrometer는 어댑터 층에만 있다. `HexagonalArchitectureTest`에 여섯째 어댑터 층 `adapter.monitoring`을 더했고, 릴레이의 trigger처럼 제공 포트에만 닿는다 |
| 제공 포트 | `PaymentFinder.countUnresolved()` | 결과 미확인의 수와 처리 중의 수를 담은 `UnresolvedPaymentCount(unknown, pending)`. 구현은 `PaymentQueryService`다(2.2절) |

**스위치.** 설정 `payment.unresolved-gauge.enabled`가 `@EnableScheduling`과 등록을 함께 감싼다. 기본값은 켬(`matchIfMissing = true`, `application.yml`의 기본 문서도 `true`)이고 `test` 프로필에서만 끈다. 다른 장면의 SQL 캡처에 배경 질의가 섞이지 않게 하려는 것이다. 꺼져 있으면 빈도 지표도 없다. 모든 인스턴스가 알려야 하므로 한 인스턴스만 켜는 릴레이(`order-events.relay.enabled`)와 다르다. 실제 포트의 테스트 기반(2.4절)은 켜고 주기를 100 ms로 둔다.

**스케줄러 스레드.** Boot의 scheduler 스레드는 하나 그대로다. 전에는 `@EnableScheduling`이 `OrderEventRelayScheduler`에만 있어 릴레이를 끈 인스턴스에는 예약된 작업이 없었다. 이제 `test` 밖의 모든 인스턴스가 scheduling을 켠다. 릴레이를 켠 인스턴스에서는 긴 릴레이 한 바퀴가 gauge의 갱신을 늦춘다(2.6절).

### 2.2 세는 질의와 인덱스

```sql
select status, count(*) as payments from payment where status in (:statuses) group by status
```

- `PaymentRepository.countByStatusIn`의 native 질의다. `Payment`의 `@SQLRestriction("deleted_at is null")`은 집계를 포함한 모든 JPQL에 붙으므로, 파생 `countBy…`나 JPQL `@Query`는 `deleted_at`을 되살린다. 포트는 상태의 이름을 문자열로 넘긴다.
- 서비스는 `UNKNOWN`과 `PENDING`을 적어 넘긴다. `UnresolvedPaymentCount`의 필드 하나에 상태 하나다. `!isFinished`에서 끌어내면 새로 생긴 끝나지 않은 상태를 세고도 조용히 버린다. 행이 없는 상태는 0이다.
- **`deleted_at`을 보지 않는다.** 결제를 삭제하는 길이 없어 거를 것이 없고, 보면 인덱스만으로 답할 수 없다(2.8절의 붉은 확인). **결제의 삭제가 생기면 이 질의를 다시 본다.** `PaymentRepository`의 KDoc도 그렇게 적는다.
- **인덱스.** `payment`에 `IDX_PAYMENT_ON_STATUS (status)`를 더했다. 엔티티의 `@Table`과 [`docs/migrations/2026-11-26-payment-status-index.sql`](../migrations/2026-11-26-payment-status-index.sql) 둘 다에 적었다(Flyway는 없다). 이주는 `ALGORITHM = INPLACE, LOCK = NONE`이고, 테스트 밖의 스키마에서 돌려 보지 않았다. 이 질의는 앱이 사는 동안 주기마다 돌고 `payment`는 늘기만 하는 표라서 둔다.
- 계획은 `EXPLAIN FORMAT=TREE`로 이렇다: `Group aggregate: count(0)` → `Filter: (payment.status in ('UNKNOWN','PENDING'))` → `Covering index range scan on payment using IDX_PAYMENT_ON_STATUS`. 인덱스가 없을 때는 `Table scan on payment`와 `Aggregate using temporary table`이었다. 행 네 개에서 range scan을 고를지 full index scan을 고를지는 optimizer의 몫이라 scan의 종류는 고정하지 않았다. 장면은 `Covering index`와 `using IDX_PAYMENT_ON_STATUS`를 함께 단언한다.
- **ADR 0014의 예외.** Finder는 애그리거트 전체를 돌려준다는 것이 ADR 0014의 규칙 1이다. `countUnresolved`는 센 수를 돌려준다. 센 수는 애그리거트를 읽은 것이 아니므로 결제를 읽어 돌려주는 대신 작은 값을 돌려준다. ADR 0014에 날짜 메모를 달았다.

### 2.3 로그 이벤트

`OrderPaymentSaga`가 남긴다. PG 호출과 TX B가 모두 이 Service를 지나고 주문의 식별자가 여기에 있다. PG 어댑터에는 더하지 않았다. 모양은 고정된 한국어 문장 하나와 kotlin-logging의 `payload`(SLF4J의 key-value)다. 식별자를 문장에 되풀이해 적지 않는다. 전에 있던 WARN 둘(생성의 결과 미확인, PG 거래 여럿)도 이 모양으로 옮겼다.

| `event` | 때 | level | 더하는 필드 |
| --- | --- | --- | --- |
| `pg.create.accepted` | 생성이 받아들여져 PG 거래를 받았다 | INFO | `transactionKey`, `pgStatus` |
| `pg.create.rejected` | 생성 전 거절 | INFO | 없다 |
| `pg.create.outcome-unknown` | 생성의 답을 모른다 | WARN | `cause` |
| `pg.lookup.found` | 조회가 PG 거래 하나를 찾았다 | INFO | `transactionKey`, `pgStatus` |
| `pg.lookup.none` | 조회가 PG 거래를 찾지 못했다 | INFO | `transactionKey`(결제가 알 때) |
| `pg.lookup.several` | 조회가 PG 거래를 여럿 말했다 | WARN | `transactionKey`(결제가 알 때) |
| `pg.lookup.unavailable` | 조회할 수 없다 | WARN | `transactionKey`(결제가 알 때), `cause` |
| `payment.apply.committed` | TX B가 커밋했다 | INFO, `conflicted`면 WARN | `transactionKey`(반영한 거래의 것), `result`, 바뀌었으면 `from`과 `to` |
| `payment.apply.failed` | TX B가 예외로 되돌아갔다 | WARN | `transactionKey`(반영하려던 거래의 것. 생성 전 거절에는 없다), 로그의 예외 |

- **모든 이벤트의 필드**: `event`, `trigger`, `orderId`, `paymentId`, `pgOrderId`. `trigger`는 `confirm`(주문 확정), `reconcile`(고객의 대사), `callback`(PG의 알림) 가운데 하나다.
- `cause`는 `PgOutcomeUnknownException`과 `PgUnavailableException`의 메시지다: 실패의 종류(`NoHttpResponseException` 같은 예외 이름)나 `HTTP <status>`. `result`는 `changed`, `unchanged`, `conflicted`다. `from`과 `to`는 결제 상태다. 반영 이벤트는 `pgStatus`를 싣지 않는다. 앞선 `pg.*` 이벤트가 싣고, 무엇이 바뀌었는지는 `from`과 `to`가 말한다. 충돌의 까닭은 싣지 않는다. `PaymentModifyService`의 WARN이 그대로 그것을 적는다. `pg.lookup.several`은 거래의 수를 싣지 않는다.
- **싣지 않는 것**: 사용자 식별자, 멱등 키, 금액, 카드의 어떤 값도. 멱등 키와 금액은 `paymentId`로 결제 행에서 읽는다.
- **조회 이벤트는 `retrieveTransaction` 안에서 남긴다.** 두 대사가 함께 지나는 한 곳이다. 조회할 수 없으면 이벤트를 남긴 뒤 다시 던지므로, 고객의 대사는 저장된 상태를 답하고 PG의 알림은 `PG_UNAVAILABLE`로 바꾼다. 어댑터의 조회 재시도는 한 호출 안에 있어 대사 한 번이 이벤트 하나다.
- **반영 이벤트는 프록시 호출의 둘레에서 남긴다.** `applyAndLog`가 `OrderPaymentSagaSteps`의 TX B 입구를 부른다. 정상으로 돌아왔다는 것은 Spring이 커밋했다는 것이고, 커밋 때의 실패도 같은 호출에서 던져진다. TX B 안에서 남기면 커밋하기 전에 커밋했다고 말하게 된다. 그래서 TX B의 두 입구는 `OrderInfo`와 `ReconcileResult`를 담은 `AppliedAnswer`를 돌려준다. `payment.apply.failed`의 catch는 `Exception`으로 넓지만 보기만 하고 같은 인스턴스를 다시 던진다. 응답과 예외의 타입은 이 단계 전과 같다. PG 호출 둘레의 catch는 좁은 그대로다. 거기서 넓은 catch는 결과 미확인을 실패로 바꿀 수 있다.
- 생성 전 거절의 반영도 TX B라서 `pg.create.rejected` 뒤에 `payment.apply.*`가 온다. 결과 미확인의 생성은 TX B를 부르지 않으므로 그 뒤에 반영 이벤트가 없다.
- 대사는 PG를 조회하기 전에 결제를 읽는다. 그래서 반영 이벤트도, 실패한 것까지 결제의 식별자를 싣는다.
- 같은 멱등 키의 재요청처럼 PG를 부르지 않는 길은 이벤트를 남기지 않는다.
- **어디에 찍히는가.** dev·qa·prd의 logstash JSON은 key-value를 최상위 필드로 싣는다. `test`·`local`의 plain console은 문장만 찍고 key-value를 찍지 않는다. `scripts/scene-lines`도 문장만 본다. 그래서 테스트와 다음 브랜치의 증거는 잡은 이벤트(`ListAppender`)에서 key-value를 읽는다.

### 2.4 실제 포트의 테스트 기반

- `BaseRealPortWebApiAdapterTest : BaseWebApiAdapterTest`가 `@SpringBootTest(webEnvironment = RANDOM_PORT)`와 `@LocalManagementPort`의 `managementPort`, management 포트로 보내는 GET `managementGet(path)`를 가진다(ADR 0012: 기반이 annotation을 모두 가진다). gauge를 켜고 주기를 100 ms로 둔다. `RANDOM_PORT`에서 Boot가 management 포트를 0으로 두므로 8081을 쓰지 않는다.
- 명세는 "넷째 테스트 기반"이라 했지만 다섯째다. 이미 넷이 있었다(`BaseApplicationServiceTest`, `BaseWebApiAdapterTest`, `BaseCommittingApplicationServiceTest`, `BaseRepositoryTest`). ADR 0012에 날짜 메모를 달았고 `CODING_STANDARDS.md`가 다섯째로 적는다.
- **HTTP는 management만이다.** 주문과 확정, 대사는 지금처럼 테스트 스레드의 `mvc`로 보내고, 물려받은 테스트 트랜잭션이 그것을 되돌린다. management 서버와 gauge의 갱신은 제 스레드에서 돌아 커밋된 행만 본다. 그래서 수를 세게 할 장면은 테스트 트랜잭션을 떠난다.
- 클라이언트는 JDK의 `HttpClient`다. 404도 던지지 않고 답으로 받아야 한다.
- 명세가 추정으로 적은 "test에서 export가 꺼지지 않는다"는 돌려서 확인했다. 아무것도 더하지 않은 첫 실행이 Prometheus 원문을 받았다. Boot 4에서 테스트의 export를 끄는 것은 `spring-boot-micrometer-metrics-test`이고 commerce-api의 테스트 classpath에 없다.
- 실제 포트가 캐시된 컨텍스트를 하나 더 만든다. `@EnableTestPg`와 spy를 단 `UnresolvedPaymentGaugeE2ETest`의 것과, `OrderPaymentLogEventTest`의 `OrderPaymentSagaSteps` spy의 것이 또 하나씩이다.

### 2.5 고르지 않은 것

- **나이 지표(가장 오래된 끝나지 않은 결제).** 과제는 업무 지표 하나를 요구한다. 결제별 시각은 runbook의 진단 질의가 DB에서 읽는다.
- **끝나지 않은 결제의 health indicator.** 2절의 머리.
- **분산 추적.** Brave bridge가 `supports/logging`으로 classpath에 있지만, 문맥이 PG와 Kafka로 이어지는지 확인하지 않았다. 주문은 로그의 식별자로만 잇는다.
- **메모리의 counter.** 2.1절.
- **결제의 상태 시각 컬럼.** 2.6절의 `updated_at`.
- **이벤트마다 level, 이름, 문장을 묶은 enum.** 호출 자리 일곱과 반영의 둘이 그대로 읽힌다.
- **plain console의 패턴에 key-value를 더하는 것.** 테스트는 잡은 이벤트에서 읽는다(2.3절).

### 2.6 한계

- **나이 지표가 없다.** gauge는 끝나지 않은 결제가 몇 건인지만 말하고 얼마나 오래됐는지는 말하지 않는다. 한 건이 오래 남은 것과 새 건이 들고 나는 것을 지표로는 가르지 못한다.
- **health indicator가 없다.** 끝나지 않은 결제가 쌓여도 health는 UP이다. 의도한 것이다(2절의 머리).
- **분산 추적을 하지 않았다.** 한 주문의 경로는 `orderId`, `paymentId`, `pgOrderId`, `transactionKey`로 로그를 걸러서만 잇는다. trace ID로 PG나 Kafka 너머까지 잇는다고 쓰지 않는다.
- **결제의 상태 시각은 `updated_at`이다.** 결제에서 바뀌는 것은 결제 상태와 거래 키뿐이고, 거래 키는 `Payment.reconcile`이 상태를 옮길 때만 적는다. 그래서 `updated_at`은 상태가 마지막으로 바뀐 때다. 결제에 상태와 함께 바뀌지 않는 컬럼이 생기면 이 가정은 깨진다.
- **scheduler 스레드 하나를 릴레이와 나눠 쓴다.** 릴레이를 켠 인스턴스에서는 긴 릴레이 한 바퀴만큼 gauge의 값이 더 늦다. `max`는 가장 새로운 값이 아니라 가장 큰 값을 고른다. 결제가 끝나 수가 줄 때는 늦은 인스턴스의 지난 수가 더 커서 `max`가 그 지난 수를 보이고, 수가 늘 때는 다른 인스턴스의 새 수가 늦음을 가린다. 늦어진 정도는 재지 않았다.
- **수는 `deleted_at`을 보지 않는다(2.2절).** 결제의 삭제가 생기면 지운 결제도 센다.
- **수는 ADR 0014의 예외다(2.2절).** Finder 가운데 애그리거트가 아닌 것을 돌려주는 메서드가 하나 있다.
- **`cause`에 PG 응답 본문의 일부가 실릴 수 있다.** 그 메시지가 파싱 오류일 때다. 예외들의 KDoc이 그렇게 적고, 전의 WARN도 그랬다.
- **`payment.apply.failed`는 예외를 싣고, DB의 메시지는 값을 인용할 수 있다.** 싣지 않는 것의 장면은 key-value를 보고 예외의 글은 보지 않는다. TX B는 행을 고치기만 하므로 중복 키의 메시지는 나오기 어렵다.
- **실패한 TX B의 HTTP 오류를 읽는 장면이 없다.** 장면들은 다시 던진 예외가 같은 인스턴스인지를 고정하고, 컨트롤러 advice는 바뀌지 않았다.
- **이주 SQL은 테스트 밖의 스키마에서 돌려 보지 않았다(2.2절).**

### 2.7 장면

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| management 포트의 실제 HTTP | `ManagementEndpointE2ETest` | health가 UP이고 Prometheus 원문에 있던 지표(`jvm_memory_used_bytes`, `resilience4j_circuitbreaker_state`)가 있다. `env`와 `heapdump`가 404다. 2개 |
| 실제 scrape의 gauge | `UnresolvedPaymentGaugeE2ETest` | 아래. 4개 |
| 꺼진 gauge | `UnresolvedPaymentGaugeDisabledTest` | 기본 `test` 컨텍스트에 빈도 지표도 없다. 1개 |
| 프로필의 설정 | `UnresolvedPaymentGaugeEnabledPropertyTest` | 실제 `application.yml`을 읽는다: local, dev, qa, prd에서 켜지고 test에서 꺼진다. 배포 프로필의 주기가 10초다. 2개 |
| `PaymentFinder` | `PaymentFinderTest` | 네 결제 상태의 결제를 실제 확정으로 두고 `(1, 1)`. 행이 없는 상태는 0. 질의 하나의 EXPLAIN이 커버링 인덱스를 쓴다. 3개 |
| 인덱스의 고정 | `PaymentRepositoryTest` | 유일하지 않은 인덱스는 `IDX_PAYMENT_ON_STATUS:1:status` 하나다. 있던 장면에 더했다 |
| 로그 이벤트 | `OrderPaymentLogEventTest` | 아래. 메서드 18개, 매개변수를 펼쳐 22개 |

`UnresolvedPaymentGaugeE2ETest`(실제 포트의 기반)의 장면. 값은 모두 scrape한 원문의 줄에서 읽는다. 빈이나 상태 JSON에서 읽지 않는다.

- health가 UP이고 원문에 두 `status`의 `commerce_payment_unresolved`가 있다. `# HELP`가 설명의 세 가지를 싣는다.
- 한 주문의 리허설. `losesCreateResponse`로 확정하면(202) 유한한 기다림 안에 `UNKNOWN`이 `1.0`이 되고, 그동안 health는 200과 UP이며 `PENDING`은 `0.0`이다. 성공한 PG 거래를 두고 고객의 대사를 부르면(200) `UNKNOWN`이 `0.0`으로 돌아온다. PG의 생성 수는 1 그대로이고 stub을 비껴간 요청이 없다.
- 생성이 받아들여져 처리 중인 결제는 `PENDING`에 `1.0`이다.
- `PaymentQueryService`의 spy가 `countUnresolved`에서 던지면 두 시계열이 `NaN`이고 어댑터가 예외와 함께 WARN을 남긴다. 실패를 거두면 `UNKNOWN`은 `1.0`, `PENDING`은 `0.0`으로 돌아온다.

`OrderPaymentLogEventTest`(`BaseCommittingApplicationServiceTest`와 테스트 PG)의 장면. 이벤트마다 key-value의 맵 전체를 단언하므로(`containsExactlyInAnyOrderEntriesOf`) 남는 필드도 붉다.

- 일곱 `pg.*` 이벤트마다 필드, level, `trigger`. `outcome-unknown`의 `cause`는 잃은 응답의 `NoHttpResponseException`과 503의 `HTTP 503` 둘이다. `found`는 `reconcile`과 `callback` 둘이고, `unavailable`은 고객의 대사와 PG의 알림을 나눠 PG의 알림이 여전히 `PG_UNAVAILABLE`을 던지는 것까지 본다.
- `payment.apply.committed`: `changed`(생성의 PG 상태가 성공, 실패, 처리 중인 셋, `from`은 `UNKNOWN`), 생성 전 거절 뒤의 `changed`(`UNKNOWN` → `FAILED`, `transactionKey` 없음), `unchanged`, WARN의 `conflicted`. 이벤트의 차례도 단언한다.
- `payment.apply.failed`: `OrderPaymentSpies`의 `failTxBOnceAfterItsWrites`로 받아들여진 생성 뒤의 TX B를 던지게 한다. 확정은 이벤트가 실은 것과 같은 `DataAccessResourceFailureException` 인스턴스를 던지고(`isSameAs`), 결제는 결과 미확인 그대로 거래 키가 없다. 생성 전 거절의 입구(`failRejectionTxBOnceAfterItsWrites`)와 찾은 조회 뒤의 대사에도 하나씩이다.
- **가설을 가르는 두 줄기.** 잃은 응답: 이벤트는 `pg.create.outcome-unknown` 하나이고 뒤에 `payment.apply.*`가 없다. 반영 실패: `pg.create.accepted` → `payment.apply.failed`, `pg.lookup.found` → `payment.apply.failed`, `pg.create.rejected` → `payment.apply.failed`.
- 싣지 않는 것: 장면 하나가 `accepted`, `committed`, `unavailable`, `found`, `failed`(실패한 PG 알림의 반영)를 남기고 모든 key와 값을 본다. 사용자, 멱등 키, 금액, 카드를 가리키는 key가 없고, 멱등 키와 카드 번호·종류, 금액의 값이 없다. ID가 1부터 다시 시작해 사용자 ID가 주문 ID와 같을 수 있으므로 사용자 ID는 key로만 본다.
- 같은 멱등 키의 재요청은 생성 수가 1 그대로이고, 첫 확정 뒤에 잡은 것에 `event`가 없다.

있던 장면은 응답과 상태를 바꾸지 않고 지나간다. `OrderPaymentIntegrationTest`는 옛 WARN의 글을 읽던 곳을 고쳤다: 잃은 응답의 장면은 WARN의 key-value에서 `event`와 `orderId`를 읽고, PG 거래 여럿의 행은 새 문장을 읽고, 충돌의 장면은 그 충돌을 말하는 WARN을 읽을 때 `conflicted`의 반영 이벤트를 뺀다. `OrderEventRecovererTest`는 이제 `OrderEventRecovery`의 logger만 잡는다. 그것이 준비하는 주문이 같은 패키지에 `pg.create.accepted`를 남기기 때문이다.

### 2.8 붉은 확인

커밋하지 않은 로컬 수정으로 돌리고 되돌렸다(2026-11-26). 명세 266이 정한 넷이다. 티켓마다의 기록은 [글감](../research/week10-payment-observability-writing-material.md)에 있다.

| 수정 | 붉어진 장면 | 티켓 |
| --- | --- | --- |
| 세는 질의에 `and deleted_at is null` | `PaymentFinderTest`의 EXPLAIN 장면. 명세의 예상과 달리 MySQL은 여전히 `IDX_PAYMENT_ON_STATUS`를 골랐다(`Index range scan … with index condition` 위의 `Filter: (payment.deleted_at is null)`). 행마다 `deleted_at`을 읽으러 간다. 인덱스 이름만 봤다면 초록이었고, `Covering`이 두 계획을 가른다 | 268 |
| 갱신의 `catch`에서 `count.set(null)`을 지워, 실패해도 지난 값을 지키게 | `UnresolvedPaymentGaugeE2ETest`의 `NaN` 장면. `NaN`을 기다리다 `ConditionTimeoutException`(10초)이고, scrape한 값은 `1.0` 그대로였다 | 269 |
| saga의 payload에 `put("idempotencyKey", payment.idempotencyKey)` | `OrderPaymentLogEventTest`의 12개 가운데 11개. 싣지 않는 것의 장면이 key `idempotencyKey`를 찾았고, 이벤트마다의 장면은 남는 필드를 찾았다. 재요청의 장면은 이벤트를 남기지 않으므로 초록이다 | 270 |
| `payment.apply.failed`의 로그 호출을 막기 | 21개 가운데 셋: 받아들여진 생성 뒤의 실패, 찾은 조회 뒤의 실패, 싣지 않는 것의 장면(그 이벤트 목록에 실패한 반영이 없다). 생성 전 거절 입구의 장면은 이 확인 뒤에 더했다 | 271 |

명세 밖의 하나. 267에서 `@TestPropertySource`로 노출을 `health,prometheus,env`로 넓히면 `ManagementEndpointE2ETest`의 노출하지 않는 장면이 `expected: 404 but was: 200`으로 붉다. 404는 `monitoring.yml`의 노출 목록에서 오는 것이고, 맞는 경로가 없어서가 아니다.

### 2.9 끝의 확인

```
scripts/final-check --app commerce-api
```

- 2026-11-26 `22d77d50`의 깨끗한 트리에서 돌렸다(07:55:52Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(1 s).
- commerce-api는 124개 클래스, 1244개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 18 s). 9주차의 마지막 확인([주간 랭킹 설계](./weekly-ranking.md) 12.8)이 118개 클래스와 1208개 테스트였으므로, 이 단계가 6개 클래스(2.7절의 새 클래스)와 36개 테스트를 더했다.
- 이 단계는 commerce-streamer와 commerce-batch를 바꾸지 않았으므로 돌리지 않았다.
- `22d77d50`의 코드는 `81c7a44b`의 것이고, 그 뒤로는 문서만 바뀌었다.

### 2.10 명세와 다른 것

2026-11-26 구현을 마치고 명세 266과 견준 것이다.

| 명세 | 구현 | 어디서 |
| --- | --- | --- |
| "넷째 테스트 기반" | 다섯째다. 테스트 기반은 이미 넷이었다(2.4절) | 267 |
| 기반이 gauge를 켜고 주기를 짧게 둔다 | 267에는 gauge가 없어, gauge의 티켓이 기반에 두 설정을 더했다 | 267, 269 |
| `deleted_at IS NULL`을 더하면 EXPLAIN의 장면이 붉다. 인덱스만으로 답하지 못한다 | 붉지만 인덱스는 그대로 쓴다. 커버링만 잃는다. 장면은 `Covering index`를 단언한다(2.8절) | 268 |
| 세는 포트를 spy로 한 번 실패시킨다 | 100 ms 주기에서 다음 갱신이 한 번의 실패를 덮어 scrape가 놓칠 수 있다. 그래서 spy는 장면이 `NaN`을 본 때까지 던지고, 장면이 실패를 거둔다. `CODING_STANDARDS.md`에 날짜 메모가 있다 | 269 |
| `payment.apply.failed`는 "예외를 남긴 뒤 그대로 다시 던진다" | 로그는 TX B 안이 아니라 프록시 호출의 둘레에서 남긴다. 그래서 TX B의 입구가 `AppliedAnswer`를 돌려주도록 바뀌었다(2.3절) | 271 |

### 2.11 뒤 단계가 넘겨받는 것

- **실제 포트의 테스트 기반.** `BaseRealPortWebApiAdapterTest`. 사고 테스트는 이것을 이어 주입된 management 포트로 health와 Prometheus 원문을 가져온다. 요청은 `mvc`로 보내고, 세어야 할 쓰기는 테스트 트랜잭션 밖에서 커밋한다(2.4절). gauge는 이 기반에서 켜져 있고 주기는 100 ms다. 한 주문의 리허설(2.7절)이 그 하네스가 돈다는 것을 보였다.
- **주문별 경로를 읽는 법.** 한 주문의 이벤트를 `orderId`(또는 `paymentId`, `pgOrderId`)로 걸러 차례대로 읽는다. `trigger`가 어느 길이 물었는지 말한다(확정, 고객의 대사, PG의 알림). 과제 4절의 두 가설은 이렇게 갈린다: `pg.create.outcome-unknown` 뒤에 반영 이벤트가 없으면 PG의 답을 잃은 것이고, `pg.create.accepted`나 `pg.lookup.found` 뒤에 `payment.apply.failed`가 있으면 받은 답을 반영하지 못한 것이다. 생성 전 거절의 반영도 TX B라서 `pg.create.rejected` 뒤의 `payment.apply.failed`도 반영의 실패다. 복구는 `trigger`가 `reconcile`인 `pg.lookup.found` 뒤의 `payment.apply.committed`(`result=changed`, `from=UNKNOWN`)로 보인다. key-value는 `test`의 console에 찍히지 않으므로 증거는 잡은 이벤트에서 읽는다(2.3절).
- **남은 위험.** 분산 추적을 하지 않았다(로그의 식별자로만 잇는다). 나이 지표가 없다. scheduler 스레드 하나를 릴레이와 나눠 쓴다. 수는 `deleted_at`을 보지 않는다. `cause`와 실패한 반영의 예외 글은 내용을 검사하지 않는다. 모두 2.6절이다. 셋째 브랜치의 "기능별 검증 범위와 남은 위험"이 이것을 잇는다.

## 3. 2단계: 사고의 재현과 복구

(2단계를 인터뷰한 뒤 쓴다.)

## 4. 3단계: 후속 확인과 runbook

(3단계를 인터뷰한 뒤 쓴다.)
