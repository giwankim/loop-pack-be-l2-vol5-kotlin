# 결제 사고 설계 — 끝나지 않은 결제를 지표로 알리고, 사고를 재현해 복구한다

10주차 과제의 설계 문서다. 브랜치마다 절 하나를 쓴다(`weekly-ranking.md`의 방식). 용어는 [`CONTEXT.md`](../../CONTEXT.md)를 따른다("끝나지 않은 결제", "결과 미확인", "처리 중", "대사"). 결정의 출처는 1단계가 [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266)이다.

상태: 2026-11-26 1단계(`feat/payment-observability`)를 인터뷰했다. 이 문서는 그 첫 티켓([issue 267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267))이 뼈대로 만들었고, 절의 내용은 단계를 구현한 뒤 쓴다. (2026-11-26 [issue 272](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/272): 1단계를 구현했고 2절에 썼다.) (2026-11-26 [issue 279](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/279): 2단계를 [명세 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274)로 인터뷰하고 구현했고 3절에 썼다.)

## 1. 근거와 범위

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 끝나지 않은 결제의 수를 gauge로 알리고, PG의 답과 TX B의 결과를 주문별 로그 이벤트로 남긴다. 실제 포트의 테스트 기반. | `feat/payment-observability` | 인터뷰 끝남(2026-11-26). [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266). 구현 끝남(2026-11-26, 2절) |
| 2 | 사고의 재현(정상 10건과 장애 10건), 가설의 판정, 대사 API로 하는 복구, 증거 파일. | `feat/payment-incident` | 인터뷰 끝남(2026-11-26). [명세 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274). 구현 끝남(2026-11-26, 3절) |
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
- 갱신은 수 하나(`UnresolvedPaymentCount?`)를 `AtomicReference` 하나에 보관하고, 두 시계열이 그것을 읽는다. 그래서 한 scrape가 두 갱신의 수를 섞지 않는다. (2026-11-26 PR 273의 리뷰: 틀렸다. 두 시계열은 scrape 안에서 저마다 `count.get()`을 부르므로, 두 읽기 사이에 갱신이 끝나면 한 scrape가 두 갱신의 수를 섞는다. 결제 하나가 상태를 옮긴 때라면 그 결제가 두 시계열에 다 보이거나 어디에도 보이지 않는다. 참조 하나는 시계열 하나의 읽기를 고르게 할 뿐이다. Micrometer에는 여러 gauge를 한 번에 읽게 하는 길이 없고, 다음 scrape가 바로잡으므로 그대로 둔다.) `null`이 두 시계열의 `NaN`이다. gauge는 Micrometer의 supplier 꼴로 등록한다. `obj, fn` 꼴은 상태를 약하게 잡는다.

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
- **scheduler 스레드 하나를 릴레이와 나눠 쓴다.** 릴레이를 켠 인스턴스에서는 긴 릴레이 한 바퀴만큼 gauge의 값이 더 늦다. `max`는 가장 새로운 값이 아니라 가장 큰 값을 고른다. 결제가 끝나 수가 줄 때는 늦은 인스턴스의 지난 수가 더 커서 `max`가 그 지난 수를 보이고, 수가 늘 때는 다른 인스턴스의 새 수가 늦음을 가린다. 늦어진 정도는 재지 않았다. (2026-11-26 PR 273의 리뷰: 거꾸로도 그렇다. 세는 질의가 길어지면, 이를테면 DB가 답하지 않는 동안에는 릴레이가 그 사이 한 바퀴를 시작하지 못한다. 세는 질의는 커버링 인덱스로 짧고, DB가 답하지 않는 동안에는 릴레이의 쓰기도 같은 DB를 기다린다. 스레드 하나는 명세 266이 정한 그대로 둔다.)
- **수는 `deleted_at`을 보지 않는다(2.2절).** 결제의 삭제가 생기면 지운 결제도 센다.
- **수는 ADR 0014의 예외다(2.2절).** Finder 가운데 애그리거트가 아닌 것을 돌려주는 메서드가 하나 있다.
- **`cause`에 PG 응답 본문의 일부가 실릴 수 있다.** 그 메시지가 파싱 오류일 때다. 예외들의 KDoc이 그렇게 적고, 전의 WARN도 그랬다. (2026-11-26 PR 273의 리뷰: 틀렸다. `cause`는 HTTP 상태(`HTTP 503`), 예외 사슬의 맨 안쪽 타입 이름(`NoHttpResponseException`), `circuit open` 가운데 하나다. 어댑터의 `failureKind`가 Spring 예외의 메시지를 버린다([결제 설계](./payments.md) 3.5). 예외들의 KDoc은 그 메시지를 쓰지 않는 까닭을 적은 것인데, 이 단계가 그것을 `cause`의 내용으로 잘못 읽었다.)
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
- (2026-11-26 명세 266의 닫는 리뷰: 리뷰의 고침 `4a1183b5`(KDoc의 새 문장을 영어로)와 `c96fa48d`(saga의 로그 도우미가 `trigger`와 결제를 먼저 받는다) 뒤에 `c96fa48d`의 깨끗한 트리에서 다시 돌렸다(08:35:59Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 124개 클래스, 1244개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 1 s). 고침은 테스트를 더하거나 빼지 않았다. `c96fa48d` 뒤로는 문서만 바뀌었다.) (2026-11-26 PR 273의 리뷰: `ed21c9a4`가 `NaN` 장면이 WARN을 기다리게 고쳐 테스트가 바뀌었으므로, `bf124279`의 깨끗한 트리에서 다시 돌렸다(08:52:13Z 무렵 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 124개 클래스, 1244개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 11 s). `bf124279` 뒤로는 문서만 바뀌었다.)

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
- **남은 위험.** 분산 추적을 하지 않았다(로그의 식별자로만 잇는다). 나이 지표가 없다. scheduler 스레드 하나를 릴레이와 나눠 쓴다. 수는 `deleted_at`을 보지 않는다. `cause`와 실패한 반영의 예외 글은 내용을 검사하지 않는다. (2026-11-26 PR 273의 리뷰: `cause`는 HTTP 상태나 예외의 타입 이름뿐이라 위험이 아니다. 남는 것은 실패한 반영의 예외 글이다. 한 scrape가 두 갱신의 수를 섞을 수 있다는 것(2.1절)과, 세는 질의가 릴레이를 늦출 수 있다는 것도 더한다.) 모두 2.6절이다. 셋째 브랜치의 "기능별 검증 범위와 남은 위험"이 이것을 잇는다.
- (2026-11-26 명세 266의 닫는 리뷰: **로그 이벤트를 saga 밖의 클래스로 옮기는 것은 미뤘다.** `trigger`와 결제가 도우미 넷을 함께 다니고, `OrderPaymentSaga`가 saga와 함께 이벤트의 필드도 만든다. 2단계가 이벤트를 어떻게 읽는지 본 뒤 정한다. 티켓은 없다.)

## 3. 2단계: 사고의 재현과 복구

결정의 출처는 [명세 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274)다(2026-11-26 인터뷰, 질문 28개). 과제의 몫은 [과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 1, 3, 4, 5, 7절이다: 정상과 장애의 구간, 같은 주문의 복구, 가설의 판정, 복구의 증명, 증거 파일. **운영 코드는 바뀌지 않았다.** 테스트, 증거, 문서뿐이다. ADR은 없다. 정한 것은 테스트의 구조와 증거의 배치이고 커밋 하나로 바꿀 수 있다. 용어집도 바꾸지 않았다. 사고, 기준 구간, 장애 구간, 복구는 테스트 실행의 말이라 이 문서에만 쓴다. 필요한 도메인 용어(결과 미확인, 끝나지 않은 결제, 대사, 알림)는 이미 있다.

이 절의 **운영자**는 사고를 맡아 진단하고 복구하는 사람이다. 용어집의 관리자(관리자 API를 부르는 역할)가 아니다. 이 단계의 운영자는 관리자 API를 하나도 부르지 않는다(3.8절).

### 3.1 2단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [275](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/275) | 사고 fixture `IncidentPg`(`com.loopers.support`)와 `IncidentPgTest`. 잡은 로그 이벤트를 읽는 `LogEvents.kt`(`logEvents()`, `logEventsOf(orderId)`)와, 그것으로 옮긴 `OrderPaymentLogEventTest`. `TestPg`는 상태 없는 그대로 읽기 넷을 얻었다. |
| [276](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/276) | `PaymentIncidentE2ETest`의 `@Test` 하나와 그 기준 구간, 장애 구간, 증거의 디렉터리. |
| [277](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/277) | 같은 `@Test`의 진단: 세 가설, 테스트가 PG에 직접 보내는 목록 조회, `hypotheses.json`과 `verdict.json`. |
| [278](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/278) | 같은 `@Test`의 복구와 그 증명, 장애 구간의 파일의 SHA-256. |
| [279](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/279) | 이 절, 저장소에 실은 증거(3.5절), 끝의 확인(3.11절). |

다섯 티켓 모두 `src/main`의 파일을 바꾸지 않았다. 사고 테스트는 운영 코드의 틈을 드러내지 않았고, 그래서 이 명세 안에 새 티켓은 없다. 티켓마다 구현 중에 정한 것은 [2단계의 글감](../research/week10-payment-incident-writing-material.md)에 있다.

### 3.2 사고의 한 lifecycle

`PaymentIncidentE2ETest`(`adapter/webapi/v1/order`)의 `@Test` 하나가 기준 구간 → 장애 구간 → 관찰 → 진단 → 복구를 차례로 돈다. 같은 DB와 같은 PG 거래가 모든 단계를 잇는다. 이름은 `lost responses leave three payments unknown under health UP, a PG lookup tells why, reconcile recovers them once`다.

- **기반.** `BaseRealPortWebApiAdapterTest`(2.4절)를 잇고 `@EnableTestPg`를 단다. 확정이 요청마다 커밋해야 gauge가 그 행을 세므로 테스트 트랜잭션에서 빠지고(`Propagation.NOT_SUPPORTED`) 표를 스스로 비운다. `UnresolvedPaymentGaugeE2ETest`의 방식이다.
- **준비.** 소유자 한 명, 상품 하나(가격 1,000, 재고 20). 소유자는 포인트 잔액 50,000과 발급된 미사용 쿠폰 하나를 가지고 시작하고, 20건 가운데 어느 주문도 그것을 쓰지 않는다. 주문은 주문 포트로 만들고(`prepareOrder`, 1개씩), 그 뒤로는 `mvc`로만 움직인다. 테스트는 결제나 주문의 상태를 DB에 쓰지 않고, 테스트의 SQL은 모두 `select`다.
- **기준 구간.** 응답 유실이 꺼져 있다고 단언한다. 주문 10건을 차례로 확정한다(202. 생성이 `PENDING`으로 받아들여진다). 테스트가 그 주문의 확정 바로 뒤에 알림을 보내면(200), 커머스의 조회가 `SUCCESS`를 찾아 끝난다.
- **장애 구간.** 다른 주문 10건이다. 장애 구간에서 3·6·9번째로 만든 주문을 DB에서 읽은 실제 주문 ID로 가리킨다. 10건의 PG 거래를 응답 유실을 켜기 전에 모두 등록한다. 셋은 잃을 주문(`registerLost`, PG 거래는 `SUCCESS`)이고 일곱은 정상 주문이다. 응답 유실을 켜고 10건을 차례로 확정한다. 일곱은 기준 구간처럼 알림으로 끝난다. 셋은 202, 결제 중인 주문, 결과 미확인으로 남는다. 테스트는 그 셋의 알림을 보내지 않는다.
- **보류할 것은 그것뿐이다.** 테스트 PG는 스스로 알림을 보내지 않고 대사하는 scheduler도 없다. 장애 구간의 기록이 그것을 보인다: 세 PG 주문 번호에 커머스가 보낸 목록 조회가 0건이다.
- **관찰.** 단계마다 같은 `observe`다. management 포트에서 health와 Prometheus를 한 번씩 받아, gauge가 그 단계의 값을 읽을 때까지(10 s까지) 되풀이한다. 바뀐 수는 갱신 한 번 뒤에야 원문에 닿는다. 마지막으로 받은 것에서 기록을 만들고, 기록과 두 원문을 파일로 쓰고, 그 기록에 단언한다.

**사고 fixture `IncidentPg`.** `TestPg` 위의 테스트 지원 클래스다. `TestPg`는 WireMock의 stub 그대로 상태가 없다.

- PG 거래 20건(PG 주문 번호, 거래 키, 금액, PG 상태)을 든다. 거래 키는 fixture가 `20261126:TR:incident-<n>`으로 만든다. PG 주문 번호는 있는 `TestPgOrderIdGenerator`가 차례대로 내주므로, 장애 구간은 10건을 모두 꺼내 등록한 뒤 같은 차례로 확정한다.
- 응답 유실은 stub 위의 stub이다. 잃을 주문에는 받아들이는 stub(202, `SUCCESS`)이 있고, 유실이 켜진 동안 그 위에 `EMPTY_RESPONSE`의 stub이 하나 더 있다. 우선순위가 같으면 WireMock은 새 것을 고른다. `stopResponseLoss()`가 위의 것을 지우면 아래의 것이 다시 답한다. `isResponseLossOn()`이 켜져 있는지 답한다. 테스트의 `finally`가 언제나 끈다.
- **두 번째 생성.** `pgOrderIdsCreatedMoreThanOnce()`가 요청 기록에서 생성을 두 번 이상 받은 PG 주문 번호를 읽는다. 등록한 주문이 아니어도 읽는다. 단계마다 기록에 싣고 비었다고 단언하며, `finally`가 다시 본다. 비지 않으면 그 자체로 테스트가 실패하고, 앞선 실패가 있으면 그것에 suppressed로 붙는다.
- **PG 거래의 수는 요청 기록에서 센다.** 생성을 받은 PG 주문 번호의 수다. fixture에 등록한 수를 세면 커머스가 무엇을 했든 10과 20이다. 모든 생성이 stub에 맞았고(`unmatchedPgRequests` 0), PG는 답을 보내든 잃든 PG 거래를 만든다.

### 3.3 세 가설과 판정

예상은 진단 전에 적는다. 테스트가 enum `Hypothesis`에 고정하고, 첫 조회 전에 `hypotheses.json`으로 쓴다.

| 가설 | 예상 | 판정 |
| --- | --- | --- |
| A. PG의 처리가 아직 끝나지 않았다 | PG의 목록 조회가 PG 거래를 `PENDING`으로 찾는다 | 기각. 조회는 셋 모두 `["SUCCESS"]`다 |
| B. PG의 성공 응답을 잃었다 | 조회가 `SUCCESS`이고, 그 주문의 이벤트는 `pg.create.outcome-unknown` 하나뿐이다 | 채택. 셋 모두 이벤트가 `[pg.create.outcome-unknown]`이다 |
| C. 성공 결과를 받았으나 반영하지 못했다 | 조회가 `SUCCESS`이고, 이벤트에 `pg.create.accepted` 뒤의 `payment.apply.failed`가 있다 | 기각. `pg.create.accepted`가 없다 |

- **진단은 읽기만 한다.** 테스트가 운영자로서 테스트 PG에 직접 `GET /api/v1/payments?orderId=<PG 주문 번호>`를 보낸다(JDK `HttpClient`, PG 계약의 `X-USER-ID`). PG 주문 번호는 결제의 행에서 읽는다. 커머스의 endpoint는 하나도 부르지 않는다. 조회 앞뒤로 세 주문의 `orders`, `payment`, `order_event_outbox` 행과 상품의 행을 통째로 읽어 같다고 단언한다. 행의 수(3, 3, 0, 1)도 단언해, 빈 목록끼리 같은 것이 아님을 보인다.
- **운영자의 조회는 수로 가린다.** 그 조회도 커머스의 조회와 같은 요청 기록에 남는다. 장애 구간이 세 PG 주문 번호의 목록 조회 0건을 단언했고, 진단은 그 뒤 정확히 1건을 단언한다. 그러니 그 1건은 운영자의 것이고 그 사이에 커머스는 조회하지 않았다. 생성 수(PG 주문 번호마다 1, 모두 20)와 stub을 비껴간 요청(0)도 그대로다.
- **판정은 주문마다 단언한다.** A는 조회의 PG 상태로, C는 이벤트의 차례로 기각한다. 조회가 셋 모두 정확히 `SUCCESS`라는 것도 단언하므로, `SUCCESS`를 같이 예상하는 C는 이벤트의 차례로만 떨어질 수 있다.
- **이벤트는 잡은 것에서 읽는다.** `orderApplicationLogs.logEventsOf(orderId)`다(2.3절. `test`의 console은 key-value를 찍지 않는다). 2.11절이 미룬 "로그 이벤트를 saga 밖으로 옮기는 것"은 이 reader로 정했다. `OrderPaymentSaga`의 로그는 그대로다.
- **반영 실패의 대조 주문은 두지 않았다.** TX B가 실패하는 주문을 장애 구간에 넣으면 과제가 정한 장애 구간의 구성(성공 7, 결과 미확인 3)이 깨진다. 반영 실패의 모습(`pg.create.accepted` → `payment.apply.failed`)은 `OrderPaymentLogEventTest`가 고정한 것을 인용한다(2.7절).
- `verdict.json`은 가설과 그 `writtenAt`을 다시 싣고, 주문마다 PG 주문 번호, 조회의 시각과 받은 본문 그대로, 관찰(조회의 PG 상태들과 이벤트의 이름들), 가설마다의 예상과 판정을 싣는다. 그 파일 하나로 읽힌다.

### 3.4 복구와 그 증명

- **대상은 DB에서 읽는다.** 끝나지 않은 결제(`payment.status in ('UNKNOWN', 'PENDING')`)의 주문 ID를 읽고, 장애 구간이 깬 셋과 같다고 단언한 뒤 고정한다. 응답 유실이 꺼져 있다고 단언한다.
- **고객의 대사 API로 복구한다.** 대상마다 `POST /api/v1/orders/{orderId}/payment/reconcile`을 소유자의 `X-USER-ID`로 한 번 부른다(200, `CONFIRMED`, `SUCCESS`). 대사는 PG 주문 번호로 조회해 찾은 `SUCCESS`를 반영한다. 생성은 보내지 않는다. 알림 endpoint나 새 운영자 endpoint로 복구하지 않았다. 사고 중에 새 길을 믿지 않으려는 것이다.
- 대상마다 이벤트는 `pg.create.outcome-unknown` → `pg.lookup.found` → `payment.apply.committed`이고, 뒤의 둘은 `trigger=reconcile`, 마지막은 `result=changed`, `from=UNKNOWN`, `to=SUCCESS`다. 2.11절이 말한 복구의 모습 그대로다.

증명은 모두 복구 뒤에 새로 읽는다.

| 증명 | 본 것 |
| --- | --- |
| 장애 구간의 같은 10건 | `recovery.json`의 주문 ID가 `fault.json`의 10건과 같다. 10건 모두 확정된 주문, 결제 성공, 예약 0. 결과 미확인 0. 결제마다 거래 키가 PG가 든 PG 거래의 것과 같고 그 PG 거래는 `SUCCESS`다. 기준 구간의 주문이나 새 주문으로 수를 채우지 않는다 |
| PG 거래 | 요청 기록에서 센 PG 거래가 모두 20건 |
| 두 번째 생성이 없다 | 20건의 PG 주문 번호마다 생성 수 1. 대사 앞뒤의 생성 수가 같다(증가 0). `pgOrderIdsCreatedMoreThanOnce` 빔, stub을 비껴간 요청 0. 대상마다 목록 조회 2(진단의 것과 대사의 것) |
| 재고 | 상품 재고 0(20 − 20). 성공 때 더 줄지 않았고 되풀이 뒤에도 그대로다 |
| 포인트와 쿠폰 | 소유자의 `point_account`와 `coupon` 행을 실행의 처음에 통째로 읽은 것과 끝에 읽은 것이 같다 |
| 아웃박스 | 20건의 주문마다 살아 있는 `order_event_outbox` 행 하나. 되풀이 뒤에도 하나다. 릴레이는 꺼져 있고 이 단계는 아웃박스 행까지다 |
| 되풀이 | 대상마다 두 번째 대사(200, `CONFIRMED`)와 되풀이한 알림(200). 20건의 주문·결제·상품·아웃박스 행, 잡은 이벤트, PG가 받은 요청 수가 모두 그대로다 |
| 다른 사용자 | 다른 사용자의 대사는 404 `ORDER_NOT_FOUND`. 위의 "그대로"의 창 안이다 |
| gauge와 health | 한 scrape에서 `UNKNOWN 0.0`, `PENDING 0.0`, health `UP` |
| 장애 구간의 파일 | 첫 대사 전에 `fault.json`, `fault-health.json`, `fault-prometheus.txt`, `fault-log-events.json`의 SHA-256을 구해 `recovery.json`의 `faultFileSha256`에 싣는다. 끝에서 `recovery.json`을 디스크에서 다시 읽어 지금 구한 SHA-256과 견준다 |

**되풀이는 `result=unchanged`를 남기지 않는다.** 복구한 주문은 확정된 주문이라, 고객의 대사도 알림도 `OrderPaymentSaga.checkLookupNeeded`에서 조회와 TX B 앞에 돌아온다. 이벤트가 하나도 없다. `unchanged`는 TX B까지 가서 결제를 그대로 둔 때의 것이다. 처리 중인 결제를 대사해 조회가 `PENDING`을 다시 찾은 때나, 다른 길이 먼저 반영한 결제를 TX B가 다시 읽은 때다. 확정된 주문의 대사와 알림은 TX B에 닿지 않는다. 그래서 "아무것도 바꾸지 않는다"는 새 이벤트 없음, PG 요청 없음, 같은 행으로 단언한다. 로그 한 줄보다 강하다. `unchanged` 이벤트 자체는 `OrderPaymentLogEventTest`가 고정한다(3.6절의 마지막 줄).

### 3.5 증거

실행마다 `apps/commerce-api/build/payment-incident/<run id>/`에 쓴다. run id는 UTC의 시작 시각과 무작위 접미사 여덟 글자(`20261126T114111Z-a052e207`의 꼴)다. 디렉터리는 `createDirectory`로 새로 만들고 모든 파일은 `CREATE_NEW`로 쓴다. 그래서 두 실행은 두 디렉터리이고, 같은 이름을 두 번 쓰면 덮어쓰지 않고 실패한다. 아무것도 지우지 않는다. 쓰는 일은 테스트 파일 안의 private 클래스 `IncidentEvidence`다. 쓰는 곳이 하나라 `com.loopers.support`에 두지 않았다.

| 파일 | 내용 | 쓰는 때 |
| --- | --- | --- |
| `baseline.json`, `fault.json`, `recovery.json` | 단계의 기록: 시각, 응답 유실, 주문 집합, 잃은 주문, DB의 끝나지 않은 결제, 주문마다 주문 ID·멱등 키·PG 주문 번호·거래 키·주문 상태·결제 상태·예약 수량·생성 수·목록 조회 수, 상품 재고, PG 거래 수, 생성 수, 두 번 생성된 PG 주문 번호, stub을 비껴간 요청 수, health의 상태, gauge의 두 값. `recovery.json`만 `faultFileSha256`을 더 싣는다 | 단계의 관찰 |
| `<phase>-health.json`, `<phase>-prometheus.txt` | 기록과 같은 scrape의 health와 Prometheus 원문, 받은 그대로 | 단계의 관찰 |
| `fault-log-events.json` | 잃은 세 주문의 잡은 이벤트(level, key-value, 예외) | 장애 구간의 관찰 뒤 |
| `hypotheses.json` | 가설과 예상, `writtenAt` | 첫 조회 전 |
| `verdict.json` | 가설, 주문마다의 조회와 관찰과 판정(3.3절) | 진단 끝 |
| `failure.json` | 실패한 단계, 예외의 클래스와 메시지, stack trace | 실패했을 때만, `finally` |

- 잃은 주문의 거래 키는 `null`로 쓴다. 프로젝트의 Jackson은 `null` 필드를 빼므로 주문의 기록은 `@JsonInclude(ALWAYS)`다. 파일은 테스트가 적기를 잊은 것이 아니라 커머스가 그 키를 몰랐다고 말한다.
- 예약 수량은 주문 상태에서 온다. 결제 중인 주문이면 품목의 수량이고 아니면 0이다(용어집의 재고 예약). 예약이 정말 남았다는 것은 상품 재고가 보인다.
- **단언은 파일에 쓴 값에 한다.** 단계마다 기록 하나를 만들어 쓰고 그 기록에 단언한다. health와 Prometheus 원문은 기록의 gauge와 health를 읽은 그 scrape의 것이다. 파일이 있다는 것만으로는 아무것도 증명하지 않는다.
- `@BeforeEach`에서 실패하면 디렉터리가 아직 없어 `failure.json`을 쓸 곳이 없다.

**저장소에 실은 실행.** 끝의 확인(3.11절)의 사고 실행 `20261126T120602Z-0c742bb1`를 [`docs/evidence/week10/20261126T120602Z-0c742bb1/`](../evidence/week10/20261126T120602Z-0c742bb1/)에 통째로 실었다. 파일 열두 개이고, Prometheus 원문 셋도 줄이지 않았다. `failure.json`은 없다. 실행은 `a0429979`의 깨끗한 트리에서 돌린 끝의 확인의 것이다.

| 무엇 | 실은 실행의 값 |
| --- | --- |
| 주문 | 기준 구간 1–10, 장애 구간 11–20. 잃은 주문 13, 16, 19 |
| 기준 구간 | 12:06:03.350Z. 10건 모두 확정된 주문, 결제 성공, 생성 1, 목록 조회 1(알림 뒤 커머스의 것). 상품 재고 10, PG 거래 10, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, 응답 유실 꺼짐 |
| 장애 구간 | 12:06:03.950Z. 일곱은 기준 구간과 같다. 13, 16, 19는 결제 중인 주문, 결과 미확인, 예약 1, 생성 1, 목록 조회 0, 거래 키 `null`. DB의 끝나지 않은 결제도 13, 16, 19. 상품 재고 0, PG 거래 20, gauge `UNKNOWN 3.0`·`PENDING 0.0`, health `UP`, 응답 유실 켜짐. 세 주문의 이벤트는 저마다 `pg.create.outcome-unknown` 하나(WARN, `trigger=confirm`, `cause=NoHttpResponseException`) |
| 진단 | 가설은 12:06:03.969Z에 썼고, 조회는 12:06:03.985Z, .990Z, .993Z다. 셋 모두 조회가 `["SUCCESS"]`, 이벤트가 `[pg.create.outcome-unknown]`이고 A 기각, B 채택, C 기각 |
| 복구 | 12:06:04.201Z. 11–20 모두 확정된 주문, 결제 성공, 예약 0, 생성 1. 13, 16, 19는 목록 조회 2. 끝나지 않은 결제 없음. 상품 재고 0, PG 거래 20, 생성 20, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, 응답 유실 꺼짐. `faultFileSha256`의 `fault.json`은 `58b208d9…`로 시작한다 |

### 3.6 복구가 실패하는 경우

복구가 성공하지 않는 경우는 새 장면을 두지 않았다. 대사가 PG 거래를 찾지 못하거나, 여럿을 찾거나, 맞지 않는 것을 찾거나, 조회할 수 없거나, 처리 중을 다시 찾으면 결제를 성공으로 바꾸지 않는다. 5주차와 1단계의 장면이 그것을 고정했다([결제 설계](./payments.md) 13.2, 14절).

| 경우 | 고정한 장면 | 결제의 끝 |
| --- | --- | --- |
| 조회할 수 없다(503, 응답 한도를 넘긴 멈춤, 열린 브레이커) | `OrderPaymentIntegrationTest`의 `a reconcile whose lookup is unavailable answers the stored state and changes nothing`, `the PG's reconcile throws PG_UNAVAILABLE on an unavailable lookup while the customer's answers the stored state`, `the PG's reconcile throws PG_UNAVAILABLE on an open breaker without a lookup, the customer's answers stored state`. HTTP는 `OrderConfirmationApiTest`의 `reconciling while the PG lookup is unavailable returns 202 with the location and changes nothing` | 저장된 상태 그대로. 고객의 대사는 그것으로 답하고 알림은 `PG_UNAVAILABLE` |
| PG 거래가 없다(0건) | `a restart before the create left keeps the payment unknown through a replay and an empty reconcile, without a create`(5주차의 장면 8), `lookups that find no transaction or several are not retried and never open the breaker` | 결과 미확인 그대로. 0건은 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3) |
| PG 거래가 여럿이다 | `a reconcile that finds several or mismatched transactions applies none and logs a warning`의 `several transactions`(장면 9) | 결과 미확인 그대로, WARN 한 줄 |
| 금액이 다르다 | 같은 장면의 `another amount`(`reason=AMOUNT_MISMATCH`). 다른 PG 주문 번호(`PG_ORDER_ID_MISMATCH`)도 같은 장면이다 | 충돌. 결과 미확인 그대로, WARN 한 줄 |
| 처리 중을 다시 찾는다 | `OrderPaymentLogEventTest`의 `a TX B that leaves the payment as it was leaves payment apply committed at INFO as unchanged`(1단계). 처리 중인 결제를 대사하자 조회가 `PENDING`을 찾고 `result=unchanged`다. 그 장면은 이벤트만 단언하고 결제의 행은 다시 읽지 않는다. 대사가 처리 중을 찾은 뒤의 행을 단언한 장면은 5주차에도 1단계에도 없다. 5주차의 장면 2는 받아들여진 `PENDING` 생성 뒤 결제 중인 주문으로 남는 것을 보지만, 그 장면의 대사는 성공을 찾는다 | 처리 중 그대로(이벤트의 `unchanged`에서 읽은 것) |

대사는 어느 경우에도 생성을 보내지 않고 결제를 실패로 끝내지 않는다([결제 설계](./payments.md) 13.2). 그런 결제는 운영자가 PG에서 결과를 확인할 때까지 끝나지 않은 결제로 남고 gauge에 보인다.

### 3.7 고르지 않은 것

- **운영자의 대사 endpoint, 커머스의 진단 endpoint.** 사고 중에 새 길을 믿지 않는다. 그 대가가 3.8절의 첫 두 한계다.
- **자동 대사 scheduler, 알림이나 대사를 보류하는 운영 스위치.** 보류는 테스트가 알림을 보내지 않는 것으로 충분했다(3.2절).
- **TX B가 실패하는 대조 주문.** 장애 구간의 성공 7과 결과 미확인 3을 깬다(3.3절).
- **`TestPg`에 상태를 넣는 것.** 상태는 사고 fixture가 든다. `TestPg`는 읽기만 얻었다(3.12절).
- **단계마다의 `@Test`.** 단계가 같은 DB와 PG 거래를 이어야 한다. 선례는 `WeeklyRankingFlowTest`(한 `@Test`의 흐름)다.
- **복구가 실패하는 경우의 새 장면.** 3.6절.
- **부하, 동시 확정, 실제 PG.** 확정은 테스트 스레드에서 차례로 보낸다.
- **ADR, 용어집의 새 말.** 3절의 머리.

### 3.8 한계

3단계의 runbook이 넘겨받는 한계 둘이 먼저다.

- **운영자가 대사하려면 고객으로서 불러야 한다.** 대사 API는 요청자의 주문만 대사한다. 남의 주문은 404다([결제 설계](./payments.md) 13.1의 차례 2, 3.4절의 다른 사용자). 테스트의 운영자는 소유자의 `X-USER-ID`를 실어 부른다. 운영에서 그 헤더는 API 게이트웨이가 싣는 것이라(용어집의 요청자) 운영자가 고객의 식별자를 어떻게 얻고 싣는지는 이 단계가 정하지 않았다. 요청자가 필요 없는 길은 알림 endpoint인데, 이 단계는 복구에 쓰지 않았다.
- **진단하려면 PG에 직접 닿아야 한다.** gauge는 몇 건인지만 말한다. 어느 주문인지는 DB의 끝나지 않은 결제에서, 왜인지는 PG의 목록 조회와 그 주문의 로그 이벤트에서 읽는다. 커머스는 PG의 답을 보여 주는 endpoint가 없으므로, 운영자는 PG 계약의 `X-USER-ID`로 PG에 직접 조회해야 한다. 로그의 key-value는 dev·qa·prd의 logstash JSON에만 있다(2.3절).

나머지.

- **알림을 보류한 PG다.** 진짜 PG라면 잃은 세 주문에도 알림을 보냈을 것이고, 커머스는 그것으로 대사했을 것이다(ADR 0031). 이 사고는 생성의 응답도 알림도 닿지 않은 경우다. 테스트 PG가 알림을 스스로 보내지 않는 것으로 그렇게 두었다.
- **응답 유실은 한 꼴이다.** `EMPTY_RESPONSE`로 연결이 답 없이 끊기는 것(`cause` `NoHttpResponseException`)뿐이다. 응답 한도를 넘긴 멈춤과 5xx는 사고에서 돌리지 않았다. 그것도 `pg.create.outcome-unknown`을 남긴다는 것은 1단계의 장면이 본다(2.7절의 `HTTP 503`).
- **가설 C는 좁다.** `pg.create.accepted` → `payment.apply.failed`만 본다. 2.11절은 `pg.lookup.found`와 `pg.create.rejected` 뒤의 `payment.apply.failed`도 반영의 실패로 말한다. 잃은 생성에는 그 길이 닿지 않으므로 이 사고의 판정은 같지만, 조회가 끼어 있는 사고라면 C를 넓혀야 한다.
- **`result=unchanged`는 사고에서 나오지 않았다(3.4절).**
- **대사가 처리 중을 다시 찾은 뒤의 결제의 행은 어느 장면도 단언하지 않는다(3.6절).** 이벤트의 `unchanged`가 그것을 말할 뿐이다.
- **운영자의 조회는 커머스의 조회와 같은 요청 기록에 있다.** 수로만 가른다(3.3절).
- **한 사용자, 한 상품, 차례로 확정한 20건이다.** 처리량이나 동시성에 대해서는 아무것도 말하지 않는다. 시각은 노트북의 Testcontainers MySQL에서 돈 테스트의 것이다.
- **파일의 SHA-256은 테스트가 장애 구간의 기록을 다시 쓰지 않았다는 것만 보인다.** 저장소에 실은 뒤의 변경은 git이 본다.

### 3.9 장면

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| 사고 fixture | `IncidentPgTest` | 등록한 정상 주문이 `PENDING`으로 확정되고 알림으로 끝난다. 유실이 켜진 동안 등록한 잃을 주문이 결과 미확인으로 확정되고 생성 1, 목록 조회 0이다. 유실은 시작 전에 꺼져 있고 시작 뒤에 켜져 있으며 `finally`의 해제 뒤 잃을 주문의 생성이 답을 받는다. 두 번째 생성을 받은 PG 주문 번호를 돌려주고 한 번 받은 것은 돌려주지 않는다. 4개 |
| 사고의 lifecycle | `PaymentIncidentE2ETest` | 3.2–3.4절. 1개 |
| 로그 이벤트의 reader | `OrderPaymentLogEventTest` | 새 reader로 읽는다. 단언과 장면의 수(22)는 그대로다. 한 주문의 차례가 잡은 이벤트 전부와 같은지도 본다. 다른 주문의 이벤트나 `orderId` 없는 이벤트가 섞이면 붉다 |

있던 장면은 모두 그대로 지나간다. `TestPg`가 얻은 읽기는 `losesCreateResponse`가 돌려주는 `StubMapping`, `remove(stub)`, `createdPgOrderIds()`, `listLookupCount(pgOrderId)`다. 마지막 것은 거래 키의 조회까지 세는 `lookupCount()`와 이름을 갈랐다.

### 3.10 붉은 확인

커밋하지 않은 로컬 수정으로 돌리고 되돌렸다(2026-11-26). 명세 274가 정한 셋이 앞의 셋이다. 티켓마다의 기록은 [2단계의 글감](../research/week10-payment-incident-writing-material.md)에 있다.

| 수정 | 붉어진 장면 | 티켓 |
| --- | --- | --- |
| 관찰 전에 잃은 주문 하나(첫째)의 알림도 보낸다 | `PaymentIncidentE2ETest`의 장애 구간. gauge를 기다리다 `expected: {"PENDING"="0.0", "UNKNOWN"="3.0"} but was: {"PENDING"="0.0", "UNKNOWN"="2.0"}`. 디렉터리에는 기준 구간의 세 파일과 `"phase" : "fault"`의 `failure.json`만 있고 장애 구간의 파일은 없다 | 276, 278 |
| 첫 대상을 대사하지 않고 새 멱등 키로 다시 확정한다 | `PaymentIncidentE2ETest`의 복구. 명세의 예상과 달리 두 번째 생성의 확인은 붉지 않다. 커머스가 생성을 보내기 전에 `IDEMPOTENCY_KEY_MISMATCH`로 거절한다. 복구가 gauge에서 실패한다(`UNKNOWN 1.0`, `"phase" : "recovery"`). 두 번째 청구를 막는 것은 ADR 0024와 0025의 멱등 키이고 테스트가 아니다 | 278 |
| 첫 대상의 생성을 테스트 PG에 `HttpClient`로 다시 보낸다(202) | `PaymentIncidentE2ETest`의 복구. 그 주문의 `createCount=2`로 실패하고, `finally`의 확인이 `PG order IDs that received more than one create: [<그 PG 주문 번호>]`로 suppressed에 붙는다. 두 번째 생성의 확인이 붉을 수 있다는 것은 이것이 보인다 | 278 |
| 잃은 주문 하나(첫째)의 PG 거래를 `PENDING`으로 든다 | `PaymentIncidentE2ETest`의 진단. 장애 구간은 지나간다(생성의 응답을 잃어 커머스가 조회하지 않는다). 조회가 `["PENDING"]`이라 A가 `ACCEPTED`, B가 `REJECTED`이고, `expected: REJECTED but was: ACCEPTED`로 A의 판정에서 붉다. `verdict.json`과 `"phase" : "diagnosis"`의 `failure.json`이 남는다 | 277 |
| `stopResponseLoss`가 유실의 stub을 지우지 않는다 | `IncidentPgTest`의 스위치 장면. `expected: 200 but was: 202` | 275 |
| `logEventsOf`의 `orderId` 거르기를 뒤집는다 | `OrderPaymentLogEventTest`의 22개 가운데 21개 | 275 |

### 3.11 끝의 확인

```
scripts/final-check --app commerce-api
```

- 2026-11-26 `a0429979`의 깨끗한 트리에서 돌렸다(12:03:50Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(1 s).
- commerce-api는 126개 클래스, 1249개 테스트, 실패 0, 오류 0, 건너뜀 0이다(7 min 29 s). 1단계의 끝의 확인(2.9절)이 124개 클래스와 1244개 테스트였으므로, 이 단계가 2개 클래스(`IncidentPgTest` 4개, `PaymentIncidentE2ETest` 1개)와 5개 테스트를 더했다. 테스트 0개, 건너뜀, 연결 실패가 없다([과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 8절).
- 이 실행의 사고 실행이 `20261126T120602Z-0c742bb1`이고 3.5절이 저장소에 실은 것이다. 기준 구간의 관찰에서 복구의 관찰까지 1 s가 걸리지 않았다(`12:06:03.350Z` → `12:06:04.201Z`).
- 이 단계는 commerce-streamer와 commerce-batch를 바꾸지 않았으므로 돌리지 않았다.
- `a0429979`의 코드는 `68e86fac`(278)의 것이고, 그 뒤로는 문서와 증거만 바뀌었다. 확인은 깨끗한 트리에서 시작했고, 도는 동안 이 절을 작업 트리에 썼다. Gradle이 읽는 파일은 바뀌지 않았다.

### 3.12 명세와 다른 것

2026-11-26 구현을 마치고 명세 274와 견준 것이다.

| 명세 | 구현 | 어디서 |
| --- | --- | --- |
| `TestPg`는 상태 없는 그대로 둔다 | 상태는 없지만 읽기 넷을 얻었다(3.9절) | 275 |
| 어느 PG 주문 번호든 생성이 두 번 오면 fixture가 테스트를 실패시킨다 | fixture는 그 PG 주문 번호를 돌려주기만 한다(`pgOrderIdsCreatedMoreThanOnce`). 단계의 단언과 `finally`가 실패시킨다(3.2절) | 275, 276 |
| PG 거래 20건 | 기록의 PG 거래 수는 요청 기록에서 센다. fixture의 수는 커머스가 무엇을 했든 20이다(3.2절) | 276 |
| `failure.json`은 단계와 예외 | 예외의 클래스, 메시지, stack trace를 싣는다 | 276 |
| 증거의 파일 목록 | `hypotheses.json`이 하나 더 있다. 파일은 새로 만들기만 하므로 `verdict.json`을 먼저 쓰고 뒤에 채울 수 없다(3.5절) | 277 |
| 조회 앞뒤로 결제, 주문, 재고의 행이 같다 | 아웃박스의 행도 견준다. 잃은 세 주문에는 복구 전에 아웃박스 행이 0이다 | 278 |
| 되풀이한 대사와 알림은 `result=unchanged` | 이벤트가 없다. 새 이벤트 없음, PG 요청 없음, 같은 행으로 단언한다(3.4절). 티켓의 말이 틀린 것이고 커머스의 틈이 아니라 새 티켓은 없다 | 278 |
| 붉은 확인: 새 멱등 키의 확정으로 복구하면 두 번째 생성의 확인이 붉다 | 멱등 키가 생성 전에 거절해 붉지 않다. 생성을 PG에 직접 다시 보낸 확인을 더했다(3.10절) | 278 |
| PG 거래 10건 모두 `SUCCESS` | fixture에서 읽는다. 여기서는 fixture가 PG다. 진짜 조회를 보내면 테스트가 단언하는 조회 수가 바뀐다. 커머스가 그 PG 거래를 반영했다는 증명은 같은 거래 키다 | 278 |

### 3.13 3단계가 넘겨받는 것

- **같은 `@Test`.** `PaymentIncidentE2ETest`의 메서드를 아웃박스 → Kafka → 구매 활동 → 주간 랭킹으로 늘린다. 이 단계는 주문마다 아웃박스 행 하나에서 멈췄고, 릴레이는 꺼져 있다.
- **사고 fixture.** `IncidentPg`와 `LogEvents.kt`.
- **증거의 디렉터리와 파일의 모양.** 3.5절. 새 단계는 같은 `IncidentEvidence`에 제 파일을 새로 만든다.
- **한계 둘.** 운영자가 고객으로서 대사한다는 것, 진단에 PG에 직접 닿아야 한다는 것(3.8절). runbook이 여기서 시작한다.
- **남은 위험.** 3.8절의 나머지와 2.6절. 기능별 검증 범위와 남은 위험이 이것을 잇는다.

## 4. 3단계: 후속 확인과 runbook

(3단계를 인터뷰한 뒤 쓴다.)
