# 결제 사고 설계 — 끝나지 않은 결제를 지표로 알리고, 사고를 재현해 복구한다

10주차 과제의 설계 문서다. 브랜치마다 절 하나를 쓴다(`weekly-ranking.md`의 방식). 용어는 [`CONTEXT.md`](../../CONTEXT.md)를 따른다("끝나지 않은 결제", "결과 미확인", "처리 중", "대사"). 결정의 출처는 1단계가 [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266)이다.

상태: 2026-11-26 1단계(`feat/payment-observability`)를 인터뷰했다. 이 문서는 그 첫 티켓([issue 267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267))이 뼈대로 만들었고, 절의 내용은 단계를 구현한 뒤 쓴다. (2026-11-26 [issue 272](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/272): 1단계를 구현했고 2절에 썼다.) (2026-11-26 [issue 279](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/279): 2단계를 [명세 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274)로 인터뷰하고 구현했고 3절에 썼다.) (2026-11-27 [issue 284](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/284): 1단계의 gauge를 메모리에서 올리고 내리도록 [명세 281](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/281)로 다시 인터뷰하고 구현했고, 2절을 지금의 gauge로 다시 썼고 3절의 증거를 다시 돌렸다.) (2026-11-27 [issue 290](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/290): 3단계를 [명세 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286)으로 인터뷰하고 구현했고 4절에 썼다. 저장소에 실은 증거는 이제 4.3절의 실행이다.)

## 1. 근거와 범위

### 1.1 세 단계

| 단계 | 하는 것 | 브랜치 | 상태 |
| --- | --- | --- | --- |
| 1 | 끝나지 않은 결제의 수를 gauge로 알리고, PG의 답과 TX B의 결과를 주문별 로그 이벤트로 남긴다. 실제 포트의 테스트 기반. | `feat/payment-observability` | 인터뷰 끝남(2026-11-26). [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266). 구현 끝남(2026-11-26, 2절) |
| 2 | 사고의 재현(정상 10건과 장애 10건), 가설의 판정, 대사 API로 하는 복구, 증거 파일. | `feat/payment-incident` | 인터뷰 끝남(2026-11-26). [명세 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274). 구현 끝남(2026-11-26, 3절) |
| 1′ | 1단계의 gauge를 DB에서 세지 않고 메모리에서 올리고 내린다. 세는 질의, 결제 상태의 인덱스, 두 설정을 걷고, 2단계의 증거를 다시 돌린다. 3단계보다 먼저다. | `refactor/in-memory-unresolved-gauge` | 인터뷰 끝남(2026-11-27). [명세 281](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/281). 구현 끝남(2026-11-27, 1.3절, 2절) |
| 3 | 아웃박스 → Kafka → 구매 활동 → 주간 랭킹의 후속 확인, `docs/runbook/week10.md`, 기능별 검증 범위와 남은 위험. | `feat/payment-incident-follow-on` | 인터뷰 끝남(2026-11-27). [명세 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286). 구현 끝남(2026-11-27, 4절) |

셋은 모두 `week10`으로 PR한다.

### 1.2 1단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [267](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267) | 실제 포트의 테스트 기반 `BaseRealPortWebApiAdapterTest`와 `ManagementEndpointE2ETest`. 운영 코드는 없다. |
| [268](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/268) | `PaymentFinder.countUnresolved`(결과 미확인과 처리 중의 수, ADR 0014의 예외)와 `IDX_PAYMENT_ON_STATUS`. 이주 SQL `docs/migrations/2026-11-26-payment-status-index.sql`. (2026-11-27 issue 283: 모두 걷었다. 1.3절.) |
| [269](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/269) | gauge `commerce.payment.unresolved`(`status`가 `UNKNOWN`, `PENDING`)의 구동 어댑터 `adapter/monitoring`의 `UnresolvedPaymentGauge`. 설정 `payment.unresolved-gauge.enabled`(`test`에서 끔)와 `refresh-interval`(10 s). `UnresolvedPaymentGaugeE2ETest`가 실제 scrape로 읽는다. (2026-11-27 issue 282, 283: 어댑터는 요구 포트를 구현하는 피구동 어댑터가 되었고, 갱신과 두 설정은 걷었다. 1.3절.) |
| [270](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/270) | `OrderPaymentSaga`가 PG의 답마다 남기는 이벤트 일곱(`pg.create.*`, `pg.lookup.*`). 고정된 문장과 key-value(`event`, `trigger`, `orderId`, `paymentId`, `pgOrderId`와 이벤트마다 더하는 필드). `OrderPaymentLogEventTest`가 잡은 key-value로 읽는다. |
| [271](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/271) | TX B의 결과 이벤트 둘. 커밋한 뒤의 `payment.apply.committed`(`result`가 `changed`·`unchanged`·`conflicted`, 바뀌면 `from`과 `to`. 충돌이면 WARN)와, 되돌아간 TX B의 `payment.apply.failed`(WARN, 예외를 실은 뒤 그대로 다시 던진다). TX B의 두 입구는 `OrderInfo`와 `ReconcileResult`를 담은 `AppliedAnswer`를 돌려준다. |

티켓마다 구현 중에 정한 것은 [글감](../research/week10-payment-observability-writing-material.md)에 있다.

### 1.3 gauge를 메모리로 바꾼 브랜치가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [282](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/282) | 결제의 요구 포트 `PaymentStatusChangeListener`와 그것을 구현하는 `UnresolvedPaymentGauge`, `OrderPaymentSaga`의 두 포트 호출, `UnresolvedPaymentGaugeE2ETest`의 여덟 장면. 기반의 도우미 `startPaymentWithoutConfirm`과 spy의 `failTxAOnceAfterItsWrites`. 어댑터의 예약과 스위치, 꺼진 gauge의 테스트도 이 티켓이 걷었다. 포트가 그것들을 거짓으로 만들었다. |
| [283](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/283) | 걷은 것: `PaymentFinder.countUnresolved`와 `UnresolvedPaymentCount`, 세는 native 질의, `PaymentFinderTest`와 그것만 부르던 기반의 도우미 둘, `IDX_PAYMENT_ON_STATUS`(지우는 이주 [`docs/migrations/2026-11-27-drop-payment-status-index.sql`](../migrations/2026-11-27-drop-payment-status-index.sql)), 설정 `payment.unresolved-gauge.*` 둘과 그 테스트. ADR 0014에는 예외를 거두는 날짜 줄. |
| [284](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/284) | 2절을 지금의 gauge로 다시 쓴 것, 2단계의 증거를 다시 돌린 것(3.5절), 끝의 확인(2.9절). 운영 코드는 없다. |

티켓마다 구현 중에 정한 것과 붉은 확인은 [gauge를 바꾼 글감](../research/week10-in-memory-unresolved-gauge-writing-material.md)에 있다.

## 2. 1단계: 끝나지 않은 결제의 지표와 결제의 로그 이벤트

결정의 출처는 [명세 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266)이다(2026-11-26 인터뷰, 질문 25개). 과제의 몫은 2절이다. ADR은 더하지 않았다. gauge의 출처, label, 로그의 모양은 모두 커밋 하나로 바꿀 수 있다. (2026-11-27 [명세 281](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/281)이 출처를 바꿨다. 이름, 종류, label은 그대로이고 ADR도 더하지 않았다. 2.1절.) 용어집은 "끝나지 않은 결제"를 더했다: 결제 상태가 결과 미확인이거나 처리 중인 결제다. 운영 코드는 commerce-api만 바꿨다. 릴레이, commerce-streamer, commerce-batch에는 로그도 지표도 더하지 않았다.

health는 바꾸지 않았다. 사고 동안 UP이라고 답하는 것이 과제가 보려는 것이고, 끝나지 않은 결제로 DOWN이 된 인스턴스는 load balancer에서 빠져 그 결제를 끝낼 대사 요청도 받지 못한다. `monitoring.yml`의 노출 범위(health, prometheus)도 그대로다.

### 2.1 지표

(2026-11-27 [명세 281](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/281): 이 절을 지금의 gauge로 다시 썼다. 1단계의 gauge는 결제의 행에서 값을 가져왔다. 예약된 갱신이 10초마다(`payment.unresolved-gauge.refresh-interval`) `PaymentFinder.countUnresolved()`로 상태별 수를 세어 보관했고, 첫 갱신 전과 갱신이 실패한 뒤에는 두 시계열이 `NaN`이었으며, 인스턴스마다 DB 전체의 같은 수를 내므로 `sum`이 아니라 `max`로 읽어야 했다. 그 질의 하나에 결제 상태의 인덱스 `IDX_PAYMENT_ON_STATUS`와 그 EXPLAIN 장면, ADR 0014의 예외, `deleted_at`을 보지 않는다는 한계, `test`에서 끄는 스위치 `payment.unresolved-gauge.enabled`, 릴레이와 나눠 쓰는 scheduler 스레드가 딸려 있었다. 바꾼 까닭은 셋이다. 애플리케이션이 지표 하나를 위해 제 DB를 주기마다 조회했다. 지표는 사건이 일어난 자리에서 프로세스의 메모리로 바뀌고, 인스턴스의 값을 모으는 일은 Prometheus가 한다. 그리고 과제 2절이 요구하는 것은 "현재 미확인 결제 수를 알려 주는 업무 지표 하나"(과제의 말)이고, DB에서 갱신하는 gauge는 그 예일 뿐이다. 명세 266이 DB를 고른 까닭 둘(재시작에 0이 된다, 시작한 인스턴스와 끝낸 인스턴스가 다르면 어긋난다)은 지금도 참이라 2.6절의 한계로 적었다. 걷은 것의 설명과 장면(`NaN`, 꺼진 gauge, 프로필의 설정, `PaymentFinderTest`), 붉은 확인 둘(268, 269), 명세와 다른 것 셋은 [이 절의 옛 판](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/ddbe2bf1/docs/design/payment-incident.md#2-1단계-끝나지-않은-결제의-지표와-결제의-로그-이벤트)에 있다. 인덱스는 [`docs/migrations/2026-11-27-drop-payment-status-index.sql`](../migrations/2026-11-27-drop-payment-status-index.sql)이 지운다. 더한 파일은 그대로 둔다.)

| 항목 | 값 |
| --- | --- |
| 이름 | `commerce.payment.unresolved`. Prometheus에서는 `commerce_payment_unresolved` |
| 종류 | gauge |
| label | `status` 하나. 값은 `UNKNOWN`과 `PENDING` 둘뿐이고, 두 시계열은 시작부터 0으로 있다 |
| 출처 | 이 프로세스의 메모리. 결제 상태가 커밋으로 바뀔 때마다 `OrderPaymentSaga`가 요구 포트를 부르고(2.2절), 어댑터가 결제가 들어간 상태의 시계열에 1을 더하고 떠난 상태의 시계열에서 1을 뺀다. scrape는 두 수를 읽을 뿐 질의를 돌리지 않는다 |
| 관찰 범위 | 이 프로세스가 시작한 뒤로 본 것. 그 상태에 들어간 결제의 수에서 그 상태를 떠난 결제의 수를 뺀 것이라, 인스턴스 하나의 값은 음수일 수 있다 |
| 갱신 주기 | 없다. 바꾼 트랜잭션이 커밋하고 돌아온 바로 뒤, 응답이 돌아가기 전에 바뀐다. 그래서 응답을 받은 뒤의 scrape 한 번이 그 값을 읽는다 |
| 읽는 법 | 인스턴스마다 제가 본 것만 세므로 `sum(commerce_payment_unresolved)`로 모은다. 음수도 그대로 더한다. 다른 in-process 지표와 같다 |

- 설명(`# HELP`)에 셋을 적었다: 끝나지 않은 결제(`UNKNOWN`이나 `PENDING`)의 수라는 것, 이 프로세스가 시작한 뒤로 본 것만 세므로 인스턴스가 음수일 수 있다는 것, 인스턴스마다 제 것을 세므로 `sum`으로 모은다는 것.
- **시작할 때 DB에서 채우지 않는다.** 채우면 세는 질의가 돌아오고, 인스턴스마다 DB 전체의 수를 채우면 `sum`이 인스턴스의 수만큼 부푼다. **음수를 0에서 막지 않는다.** 막으면 인스턴스 사이의 `sum`이 틀어진다. 둘의 대가가 2.6절의 한계다.
- **스위치가 없다.** 지표는 `test`를 포함한 모든 프로필에서 등록되고, 예약된 작업이 없으므로 다른 장면의 SQL 캡처에 섞일 질의도 없다. `@EnableScheduling`은 다시 릴레이(`OrderEventRelayScheduler`)에만 있어, 릴레이를 끈 인스턴스는 아무것도 예약하지 않는다.
- 어댑터는 끝나지 않은 상태마다 `AtomicLong` 하나를 두고, 그것을 Micrometer의 `obj, fn` 꼴로 등록한다. 그 꼴은 객체를 약하게 잡지만 singleton 빈의 맵이 강하게 잡는다. 끝난 상태(`SUCCESS`, `FAILED`)에는 시계열이 없어 그 쪽의 증감은 버린다. 어느 상태를 세는지는 어댑터만 정한다. 포트와 saga는 상태가 바뀌었다는 것만 안다.

| 부분 | 클래스 | 하는 것 |
| --- | --- | --- |
| 피구동 어댑터 | `adapter.monitoring.UnresolvedPaymentGauge` | 요구 포트를 구현하고 두 시계열을 Micrometer에 등록한다. Micrometer는 어댑터 층에만 있다. `HexagonalArchitectureTest`의 여섯째 어댑터 층 `adapter.monitoring`은 도메인(`PaymentStatus`)과 `application.order.required`에만 닿고, 다른 어댑터 층은 그것에 닿지 못한다 |
| 요구 포트 | `application.order.required.PaymentStatusChangeListener` | `statusChanged(from: PaymentStatus?, to: PaymentStatus)` 하나. `from`이 `null`이면 새 결제다. 결제 상태가 바뀌어 커밋했다는 것만 말하고 gauge를 모른다. 구현이 메모리의 수만 바꾸고 던지지 않으므로 부르는 쪽은 잡지 않는다. (2026-11-27, spec 281의 검토: 결제의 요구 포트에서 주문의 요구 포트로 옮겼다. 부르는 쪽은 주문의 `OrderPaymentSaga`뿐이고, [ADR 0013](../adr/0013-commerce-api-follows-splearn-hexagonal-structure.md)은 묻는 슬라이스가 제 `required`에 포트를 두고 다른 슬라이스의 `required`를 부르지 않게 한다. 커밋을 아는 것도 트랜잭션 밖의 saga뿐이다.) |

### 2.2 부르는 곳

`OrderPaymentSaga`가 부른다. saga는 이미 두 트랜잭션 밖에 서 있으므로, 바꾼 트랜잭션이 돌아온 바로 뒤의 두 자리에서 부르면 커밋한 것만 알린다. Spring의 application event나 transaction synchronization은 쓰지 않았다.

| 자리 | 포트 호출 | 부르지 않는 때 |
| --- | --- | --- |
| TX A가 새 결제를 돌려준 뒤, PG를 부르기 전 | `statusChanged(null, UNKNOWN)` | 같은 멱등 키의 재요청(TX A가 결제를 돌려주지 않는다), 되돌아간 TX A(돌아오지 않는다) |
| `applyAndLog`가 `payment.apply.committed`를 남긴 뒤, 결과가 `ReconcileResult.Changed`일 때 | `statusChanged(from, to)` | `Unchanged`, `Conflicted`, 되돌아간 TX B(`payment.apply.failed`) |

- **세 길을 한 번에 덮는다.** 확정, 고객의 대사, PG의 알림이 모두 TX B로 `applyAndLog`를 지난다. 결제 상태를 쓰는 것은 `OrderPaymentSagaSteps`뿐이고(결제의 제공 포트 `PaymentRecorder`의 `start`, `reconcile`, `reject`를 부르는 곳이 그것 하나다), 그것을 부르는 것은 saga뿐이다.
- **수는 커밋한 결제 상태와 함께 움직인다.** 생성 전 거절은 +1(TX A) 뒤 −1(거절의 TX B, `UNKNOWN` → `FAILED`)이라 제자리다. 결과 미확인에서 처리 중으로 가면 `UNKNOWN`이 1 줄고 `PENDING`이 1 는다. TX B가 되돌아가면 결제는 결과 미확인으로 남고 수도 TX A의 +1 그대로다.
- TX A 뒤의 포트 호출이 PG를 부르기 전이므로, 생성의 답을 잃어 TX B로 가지 않는 결제도 결과 미확인으로 센다. 2단계의 사고가 그것이다(3절).

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

- `BaseRealPortWebApiAdapterTest : BaseWebApiAdapterTest`가 `@SpringBootTest(webEnvironment = RANDOM_PORT)`와 `@LocalManagementPort`의 `managementPort`, management 포트로 보내는 GET `managementGet(path)`를 가진다(ADR 0012: 기반이 annotation을 모두 가진다). 설정은 하나도 두지 않는다. `RANDOM_PORT`에서 Boot가 management 포트를 0으로 두므로 8081을 쓰지 않는다.
- 명세는 "넷째 테스트 기반"이라 했지만 다섯째다. 이미 넷이 있었다(`BaseApplicationServiceTest`, `BaseWebApiAdapterTest`, `BaseCommittingApplicationServiceTest`, `BaseRepositoryTest`). ADR 0012에 날짜 메모를 달았고 `CODING_STANDARDS.md`가 다섯째로 적는다.
- **HTTP는 management만이다.** 주문과 확정, 대사는 지금처럼 테스트 스레드의 `mvc`로 보내고, 물려받은 테스트 트랜잭션이 그것을 되돌린다. management 서버는 제 스레드에서 돌고, gauge는 바꾼 트랜잭션이 커밋한 뒤에만 움직인다(2.2절). 그래서 gauge를 움직일 장면은 테스트 트랜잭션을 떠난다.
- 클라이언트는 JDK의 `HttpClient`다. 404도 던지지 않고 답으로 받아야 한다.
- 명세가 추정으로 적은 "test에서 export가 꺼지지 않는다"는 돌려서 확인했다. 아무것도 더하지 않은 첫 실행이 Prometheus 원문을 받았다. Boot 4에서 테스트의 export를 끄는 것은 `spring-boot-micrometer-metrics-test`이고 commerce-api의 테스트 classpath에 없다.
- 실제 포트가 캐시된 컨텍스트를 하나 더 만든다. `@EnableTestPg`와 `OrderPaymentSagaSteps`의 spy를 단 `UnresolvedPaymentGaugeE2ETest`의 것, `@EnableTestPg`만 단 `PaymentIncidentE2ETest`의 것(3절), `OrderPaymentLogEventTest`의 `OrderPaymentSagaSteps` spy의 것(실제 포트가 아니다)이 또 하나씩이다.

### 2.5 고르지 않은 것

- **나이 지표(가장 오래된 끝나지 않은 결제).** 과제는 업무 지표 하나를 요구한다. 결제별 시각은 runbook의 진단 질의가 DB에서 읽는다.
- **끝나지 않은 결제의 health indicator.** 2절의 머리.
- **분산 추적.** Brave bridge가 `supports/logging`으로 classpath에 있지만, 문맥이 PG와 Kafka로 이어지는지 확인하지 않았다. 주문은 로그의 식별자로만 잇는다.
- **상태 전이의 counter, PG의 답의 counter.** 명세 281의 인터뷰에서 견주었다. 재시작에 안전하지만 "현재의 수"가 아니라 구간의 변화를 말하고, 과제는 "현재 미확인 결제 수"(과제의 말)를 요구한다.
- **시작할 때 DB에서 채우는 것, 음수를 0에서 막는 것, 여러 인스턴스의 값을 맞추는 것.** 2.1절.
- **Spring의 application event, transaction synchronization.** saga가 이미 두 트랜잭션 밖에 서 있다(2.2절).
- **어댑터의 단위 테스트, 빈에서 읽은 gauge, 포트를 부른 수를 세는 mock.** 이음매는 실제 scrape 하나다(2.7절).
- **결제의 상태 시각 컬럼.** 2.6절의 `updated_at`.
- **이벤트마다 level, 이름, 문장을 묶은 enum.** 호출 자리 일곱과 반영의 둘이 그대로 읽힌다.
- **plain console의 패턴에 key-value를 더하는 것.** 테스트는 잡은 이벤트에서 읽는다(2.3절).

### 2.6 한계

- **나이 지표가 없다.** gauge는 끝나지 않은 결제가 몇 건인지만 말하고 얼마나 오래됐는지는 말하지 않는다. 한 건이 오래 남은 것과 새 건이 들고 나는 것을 지표로는 가르지 못한다.
- **health indicator가 없다.** 끝나지 않은 결제가 쌓여도 health는 UP이다. 의도한 것이다(2절의 머리).
- **분산 추적을 하지 않았다.** 한 주문의 경로는 `orderId`, `paymentId`, `pgOrderId`, `transactionKey`로 로그를 걸러서만 잇는다. trace ID로 PG나 Kafka 너머까지 잇는다고 쓰지 않는다.
- **결제의 상태 시각은 `updated_at`이다.** 결제에서 바뀌는 것은 결제 상태와 거래 키뿐이고, 거래 키는 `Payment.reconcile`이 상태를 옮길 때만 적는다. 그래서 `updated_at`은 상태가 마지막으로 바뀐 때다. 결제에 상태와 함께 바뀌지 않는 컬럼이 생기면 이 가정은 깨진다.
- **다시 시작한 인스턴스는 0에서 시작한다.** DB에서 채우지 않는다(2.1절). 그래서 재시작은 `sum`을 그 인스턴스가 들고 있던 값만큼 옮긴다. 값이 양수였으면(시작했지만 아직 끝나지 않은 결제) `sum`은 그만큼 적게 세고, 그 결제가 끝나도 차이는 남는다. 끝낸 인스턴스가 1을 빼기 때문이다. 값이 음수였으면(다른 인스턴스가 시작한 결제를 끝낸 것) `sum`은 그만큼 많이 센다. 시작한 인스턴스의 +1이 남아, 끝나지 않은 결제가 없어도 `sum`이 0보다 클 수 있다. 커밋한 뒤 포트를 부르기 전에 프로세스가 죽은 결제도 이 차이에 든다. (2026-11-27 PR 285의 리뷰: 처음에는 적게 세는 쪽만 적었다. 음수를 든 인스턴스의 재시작은 없는 적체를 만든다.)
- **인스턴스의 값이 음수일 수 있다.** 이 프로세스가 시작을 보지 못한 결제(재시작 전이나 다른 인스턴스에서 시작한 것)를 여기서 끝내면 그 시계열이 0 아래로 간다. 막지 않는다(2.1절). 인스턴스 하나의 값에는 뜻이 없고 `sum`만 읽는다.
- **그래서 "몇 건이고 어느 주문인가"의 근거는 결제의 행이고 gauge는 경보다.** 2.11절의 "3단계에 남기는 말"이 그것을 runbook에 넘긴다.
- **한 scrape가 결제 하나의 상태 옮김을 반만 볼 수 있다.** 어댑터는 떠난 상태에서 1을 뺀 뒤 들어간 상태에 1을 더하고, 두 시계열은 scrape 안에서 저마다 읽힌다. 그 사이에 읽으면 그 결제가 어느 시계열에도 없다. 다음 scrape가 바로잡는다.
- **saga 밖에서 결제 상태를 쓰는 길은 세지 않는다.** 지금은 그런 길이 없다(2.2절). 관리자의 결제 수정 같은 길이 생기면 그 길도 커밋한 뒤 포트를 불러야 한다.
- **`cause`에 PG 응답 본문의 일부가 실릴 수 있다.** 그 메시지가 파싱 오류일 때다. 예외들의 KDoc이 그렇게 적고, 전의 WARN도 그랬다. (2026-11-26 PR 273의 리뷰: 틀렸다. `cause`는 HTTP 상태(`HTTP 503`), 예외 사슬의 맨 안쪽 타입 이름(`NoHttpResponseException`), `circuit open` 가운데 하나다. 어댑터의 `failureKind`가 Spring 예외의 메시지를 버린다([결제 설계](./payments.md) 3.5). 예외들의 KDoc은 그 메시지를 쓰지 않는 까닭을 적은 것인데, 이 단계가 그것을 `cause`의 내용으로 잘못 읽었다.)
- **`payment.apply.failed`는 예외를 싣고, DB의 메시지는 값을 인용할 수 있다.** 싣지 않는 것의 장면은 key-value를 보고 예외의 글은 보지 않는다. TX B는 행을 고치기만 하므로 중복 키의 메시지는 나오기 어렵다.
- **실패한 TX B의 HTTP 오류를 읽는 장면이 없다.** 장면들은 다시 던진 예외가 같은 인스턴스인지를 고정하고, 컨트롤러 advice는 바뀌지 않았다.
- **이주 SQL 둘은 테스트 밖의 스키마에서 돌려 보지 않았다.** 인덱스를 더한 것과 지운 것이다(1.3절).

### 2.7 장면

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| management 포트의 실제 HTTP | `ManagementEndpointE2ETest` | health가 UP이고 Prometheus 원문에 있던 지표(`jvm_memory_used_bytes`, `resilience4j_circuitbreaker_state`)가 있다. `env`와 `heapdump`가 404다. 2개 |
| 실제 scrape의 gauge | `UnresolvedPaymentGaugeE2ETest` | 아래. 8개 |
| 층의 규칙 | `HexagonalArchitectureTest` | `adapter.monitoring`이 도메인과 `application.order.required`에만 닿고, 다른 어댑터 층이 그것에 닿지 않는다. 있던 규칙을 고쳤다 |
| 인덱스가 없다 | `PaymentRepositoryTest` | `the payment table has no non-unique index`. 결제의 표에 유일하지 않은 인덱스가 없다. 있던 장면을 뒤집었다. 1개 |
| 로그 이벤트 | `OrderPaymentLogEventTest` | 아래. 메서드 18개, 매개변수를 펼쳐 22개 |

`UnresolvedPaymentGaugeE2ETest`(실제 포트의 기반, `@EnableTestPg`, `OrderPaymentSagaSteps`의 spy)의 장면. 값은 모두 실제 HTTP로 받은 Prometheus 원문의 줄에서 읽는다. 빈에서 읽지 않고, 포트를 부른 수를 mock으로 세지 않는다.

- **차이로 단언한다.** gauge는 장면 사이에 `DatabaseCleanUp`이 비우는 행보다 오래 남는다. 장면은 두 시계열을 먼저 읽고, 그 읽은 값에서 얼마나 움직였는지를 단언한다(`changeSince`). 수는 응답이 돌아오기 전에 움직이므로 어느 장면도 기다리지 않는다. 운영 코드에 테스트만 쓰는 초기화 메서드가 없고 `@DirtiesContext`도 없다.
- **장면은 제가 남긴 것을 거둔다.** 끝나지 않게 둔 결제를 장면 안에서 끝내고 시작한 값으로 돌아왔다고 단언한다. 그러지 않는 것은 시작을 보지 못한 결제의 장면 하나이고, 다른 장면이 모두 시작한 값으로 돌아오므로 그 장면은 이 컨텍스트에서 0에서 시작한다. 남긴 장면은 제 마지막 단언에서 먼저 붉다.

| 장면 | 일으키는 것 | 단언 |
| --- | --- | --- |
| 두 시계열과 health | 없다 | health 200, UP. 원문에 두 `status`의 `commerce_payment_unresolved`가 있다. `# HELP`가 `unresolved payments`, `since this process started`, `can be negative`, `aggregate with sum`을 싣는다 |
| 잃은 생성의 응답 | `losesCreateResponse`로 확정(202) | `UNKNOWN` +1, `PENDING` 0. health 200, UP. 성공한 PG 거래를 찾은 고객의 대사(200)가 시작한 값으로 되돌린다. 생성 수 1, stub을 비껴간 요청 0 |
| 처리 중인 생성 | 받아들여진 `PENDING`의 생성(202) | `UNKNOWN` 0, `PENDING` +1. PG가 아직 `PENDING`이라 말하는 대사(202, `Unchanged`의 TX B)는 움직이지 않는다. `SUCCESS`를 찾은 대사가 되돌린다 |
| 생성 전 거절 | `rejectsBeforeCreate`로 확정(200) | 시작한 값 그대로. TX A의 +1과 거절의 −1 |
| 같은 멱등 키의 재요청 | 잃은 응답의 확정 뒤, 같은 키로 다시 확정(202) | 재요청 앞에서 읽은 값 그대로, 생성 수 1. 대사가 첫 확정의 +1을 거둔다 |
| 되돌아간 TX A | `failTxAOnceAfterItsWrites`로 확정(500) | 시작한 값 그대로, 생성 0. 다음 PG 주문 번호의 거절로 다시 확정하면(200) 생성 1, 그대로. 다음 번호에만 stub이 있으므로, 되돌아간 TX A가 첫 번호를 가져갔어야 초록이다 |
| 되돌아간 TX B | 받아들여진 `SUCCESS`의 생성 뒤 `failTxBOnceAfterItsWrites`(500) | `UNKNOWN` +1(TX A가 둔 그대로). 같은 PG 거래를 반영하는 대사가 되돌린다 |
| 시작을 보지 못한 결제 | 기반의 `startPaymentWithoutConfirm`이 결제의 제공 포트로 결과 미확인의 결제를 준비하고, 고객의 대사가 그것을 끝낸다(200) | `UNKNOWN`의 차이가 −1이다. 0에서 막으면 차이가 0이라 붉다(2.8절). 장면은 차이만 단언하고 원문의 절대값 `-1.0`은 단언하지 않는다. 재시작한 인스턴스와 다른 인스턴스가 시작한 결제를 대신한다 |

`OrderPaymentLogEventTest`(`BaseCommittingApplicationServiceTest`와 테스트 PG)의 장면. 이벤트마다 key-value의 맵 전체를 단언하므로(`containsExactlyInAnyOrderEntriesOf`) 남는 필드도 붉다.

- 일곱 `pg.*` 이벤트마다 필드, level, `trigger`. `outcome-unknown`의 `cause`는 잃은 응답의 `NoHttpResponseException`과 503의 `HTTP 503` 둘이다. `found`는 `reconcile`과 `callback` 둘이고, `unavailable`은 고객의 대사와 PG의 알림을 나눠 PG의 알림이 여전히 `PG_UNAVAILABLE`을 던지는 것까지 본다.
- `payment.apply.committed`: `changed`(생성의 PG 상태가 성공, 실패, 처리 중인 셋, `from`은 `UNKNOWN`), 생성 전 거절 뒤의 `changed`(`UNKNOWN` → `FAILED`, `transactionKey` 없음), `unchanged`, WARN의 `conflicted`. 이벤트의 차례도 단언한다.
- `payment.apply.failed`: `OrderPaymentSpies`의 `failTxBOnceAfterItsWrites`로 받아들여진 생성 뒤의 TX B를 던지게 한다. 확정은 이벤트가 실은 것과 같은 `DataAccessResourceFailureException` 인스턴스를 던지고(`isSameAs`), 결제는 결과 미확인 그대로 거래 키가 없다. 생성 전 거절의 입구(`failRejectionTxBOnceAfterItsWrites`)와 찾은 조회 뒤의 대사에도 하나씩이다.
- **가설을 가르는 두 줄기.** 잃은 응답: 이벤트는 `pg.create.outcome-unknown` 하나이고 뒤에 `payment.apply.*`가 없다. 반영 실패: `pg.create.accepted` → `payment.apply.failed`, `pg.lookup.found` → `payment.apply.failed`, `pg.create.rejected` → `payment.apply.failed`.
- 싣지 않는 것: 장면 하나가 `accepted`, `committed`, `unavailable`, `found`, `failed`(실패한 PG 알림의 반영)를 남기고 모든 key와 값을 본다. 사용자, 멱등 키, 금액, 카드를 가리키는 key가 없고, 멱등 키와 카드 번호·종류, 금액의 값이 없다. ID가 1부터 다시 시작해 사용자 ID가 주문 ID와 같을 수 있으므로 사용자 ID는 key로만 본다.
- 같은 멱등 키의 재요청은 생성 수가 1 그대로이고, 첫 확정 뒤에 잡은 것에 `event`가 없다.

있던 장면은 응답과 상태를 바꾸지 않고 지나간다. `OrderPaymentIntegrationTest`는 옛 WARN의 글을 읽던 곳을 고쳤다: 잃은 응답의 장면은 WARN의 key-value에서 `event`와 `orderId`를 읽고, PG 거래 여럿의 행은 새 문장을 읽고, 충돌의 장면은 그 충돌을 말하는 WARN을 읽을 때 `conflicted`의 반영 이벤트를 뺀다. `OrderEventRecovererTest`는 이제 `OrderEventRecovery`의 logger만 잡는다. 그것이 준비하는 주문이 같은 패키지에 `pg.create.accepted`를 남기기 때문이다.

### 2.8 붉은 확인

커밋하지 않은 로컬 수정으로 돌리고 되돌렸다. 1단계(2026-11-26, 명세 266)의 것 가운데 지금의 코드에 남은 둘과, gauge를 바꾼 명세 281(2026-11-27)의 다섯이다. 티켓마다의 기록은 [1단계의 글감](../research/week10-payment-observability-writing-material.md)과 [gauge를 바꾼 글감](../research/week10-in-memory-unresolved-gauge-writing-material.md)에 있다. 282의 넷은 모두 `UnresolvedPaymentGaugeE2ETest` 전체를, 283의 하나는 `PaymentRepositoryTest`를 돌렸다.

| 수정 | 붉어진 장면 | 티켓 |
| --- | --- | --- |
| TX A 뒤의 포트 호출을 트랜잭션 안으로(`OrderPaymentSagaSteps.startPayment`의 `paymentRecorder.start` 뒤로) | 되돌아간 TX A의 장면. `UNKNOWN`이 0이 아니라 +1이다. 그 전에는 이 길을 잡는 장면이 없어 이 확인이 장면을 더했다. 첫 실행은 그른 까닭으로 초록이었다: PG 주문 번호를 마련하지 않아 `PaymentRecorder.start`가 결제를 만들기 전에 던졌다. 그래서 장면이 번호를 마련하고, 다음 번호로만 맞는 재시도로 끝난다 | 282 |
| TX B 뒤의 포트 호출을 `Changed`가 아닌 결과에도 하기 | 처리 중인 생성의 장면. 아직 처리 중이라 말하는 대사 뒤 `PENDING`이 +1이 아니라 +2다. 그 대사는 이 확인을 위해 더했다. `Unchanged`의 TX B를 지나는 장면이 없었다 | 282 |
| `from`의 −1을 빼먹기 | 여섯 장면: 잃은 생성, 처리 중인 생성, 생성 전 거절, 재요청, 되돌아간 TX B, 시작을 보지 못한 결제 | 282 |
| 1을 뺄 때 수가 0 아래로 가지 않게 막기 | 시작을 보지 못한 결제의 장면. 차이가 −1이 아니라 0이다 | 282 |
| 인덱스가 없다는 장면을 먼저 뒤집고 엔티티의 인덱스를 둔 채 돌리기 | `PaymentRepositoryTest`의 그 장면. `Expecting empty but was: ["IDX_PAYMENT_ON_STATUS:1:status"]`. 엔티티의 `@Table`에서 인덱스를 걷자 지나갔다 | 283 |
| saga의 payload에 `put("idempotencyKey", payment.idempotencyKey)` | `OrderPaymentLogEventTest`의 12개 가운데 11개. 싣지 않는 것의 장면이 key `idempotencyKey`를 찾았고, 이벤트마다의 장면은 남는 필드를 찾았다. 재요청의 장면은 이벤트를 남기지 않으므로 초록이다 | 270 |
| `payment.apply.failed`의 로그 호출을 막기 | 21개 가운데 셋: 받아들여진 생성 뒤의 실패, 찾은 조회 뒤의 실패, 싣지 않는 것의 장면(그 이벤트 목록에 실패한 반영이 없다). 생성 전 거절 입구의 장면은 이 확인 뒤에 더했다 | 271 |

명세 밖의 하나. 267에서 `@TestPropertySource`로 노출을 `health,prometheus,env`로 넓히면 `ManagementEndpointE2ETest`의 노출하지 않는 장면이 `expected: 404 but was: 200`으로 붉다. 404는 `monitoring.yml`의 노출 목록에서 오는 것이고, 맞는 경로가 없어서가 아니다.

### 2.9 끝의 확인

```
scripts/final-check --app commerce-api
```

- 2026-11-26 `22d77d50`의 깨끗한 트리에서 돌렸다(07:55:52Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(1 s).
- commerce-api는 124개 클래스, 1244개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 18 s). 9주차의 마지막 확인([주간 랭킹 설계](./weekly-ranking.md) 12.8)이 118개 클래스와 1208개 테스트였으므로, 이 단계가 6개 클래스(그때의 2.7절의 새 클래스)와 36개 테스트를 더했다.
- 이 단계는 commerce-streamer와 commerce-batch를 바꾸지 않았으므로 돌리지 않았다.
- `22d77d50`의 코드는 `81c7a44b`의 것이고, 그 뒤로는 문서만 바뀌었다.
- (2026-11-26 명세 266의 닫는 리뷰: 리뷰의 고침 `4a1183b5`(KDoc의 새 문장을 영어로)와 `c96fa48d`(saga의 로그 도우미가 `trigger`와 결제를 먼저 받는다) 뒤에 `c96fa48d`의 깨끗한 트리에서 다시 돌렸다(08:35:59Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 124개 클래스, 1244개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 1 s). 고침은 테스트를 더하거나 빼지 않았다. `c96fa48d` 뒤로는 문서만 바뀌었다.) (2026-11-26 PR 273의 리뷰: `ed21c9a4`가 gauge의 장면 하나가 WARN을 기다리게 고쳐 테스트가 바뀌었으므로, `bf124279`의 깨끗한 트리에서 다시 돌렸다(08:52:13Z 무렵 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 124개 클래스, 1244개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 11 s). `bf124279` 뒤로는 문서만 바뀌었다.)
- (2026-11-27 명세 281: gauge를 메모리로 바꾼 브랜치의 끝의 확인이다. issue 284가 `4c65ad3f`의 깨끗한 트리에서 돌렸다(01:42:39Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 123개 클래스, 1248개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 57 s). 2단계의 마지막 확인(3.11절, 126개 클래스와 1250개 테스트)에서 issue 282가 gauge의 장면 하나와 클래스 하나(`UnresolvedPaymentGaugeDisabledTest`, 1개)를 걷고 gauge의 장면 다섯을 더했고(125개, 1253개), issue 283이 클래스 둘(`PaymentFinderTest` 3개, `UnresolvedPaymentGaugeEnabledPropertyTest` 2개)을 걷었다. 이 실행의 사고 실행 `20261127T014458Z-e8a33044`가 3.5절이 저장소에 실은 것이다. `4c65ad3f`의 코드는 `a4c0f519`의 것이고, 그 뒤로는 문서와 증거만 바뀌었다. 확인은 깨끗한 트리에서 시작했고, 도는 동안 이 문서를 작업 트리에 썼다. Gradle이 읽는 파일은 바뀌지 않았다. commerce-streamer와 commerce-batch는 바뀌지 않아 돌리지 않았다.)
- (2026-11-27 명세 281의 닫는 리뷰: 리뷰의 고침 `449b88a1`(포트를 주문의 요구 포트로 옮겼다) 뒤에 `67a89f5f`의 깨끗한 트리에서 다시 돌렸다(02:18:34Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 123개 클래스, 1248개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 6 s). 고침은 테스트를 더하거나 빼지 않았다. 이 실행의 사고 실행 `20261127T022026Z-78422b9c`는 3.5절이 실은 실행과 같은 값(`UNKNOWN` 0, 3, 0, `PENDING` 0, health UP)을 단언하고 시각, PG 주문 번호, 해시만 달라 증거를 바꾸지 않았다. `67a89f5f` 뒤로는 문서만 바뀌었다.)
- (2026-11-27 PR 285의 리뷰: `063f325e`가 PG에 조회를 보내는 gauge의 장면 넷에 `unmatchedRequests()`가 비었다는 단언을 더해 테스트가 바뀌었으므로, `5e030981`의 깨끗한 트리에서 다시 돌렸다(02:39:40Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 123개 클래스, 1248개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 14 s). 사고 실행 `20261127T024133Z-c74c819f`는 같은 값을 단언해 증거를 바꾸지 않았다. `5e030981` 뒤로는 문서만 바뀌었다.)

### 2.10 명세와 다른 것

2026-11-26 구현을 마치고 명세 266과 견준 것 가운데 지금의 코드에 남은 것과, 2026-11-27 명세 281과 견준 것이다. 걷은 gauge의 세 줄은 옛 판에 있다(2.1절의 날짜 문단).

| 명세 | 구현 | 어디서 |
| --- | --- | --- |
| "넷째 테스트 기반" | 다섯째다. 테스트 기반은 이미 넷이었다(2.4절) | 267 |
| `payment.apply.failed`는 "예외를 남긴 뒤 그대로 다시 던진다" | 로그는 TX B 안이 아니라 프록시 호출의 둘레에서 남긴다. 그래서 TX B의 입구가 `AppliedAnswer`를 돌려주도록 바뀌었다(2.3절) | 271 |
| 첫 티켓은 옛 질의를 쓰이지 않은 채 남기고, 둘째 티켓이 그것과 딸린 것을 걷는다 | 282가 283의 몫 가운데 포트가 거짓으로 만든 것을 가져갔다: 어댑터의 예약과 그 `@EnableScheduling`, `@ConditionalOnProperty`, 실패의 WARN, 꺼진 gauge의 테스트, 릴레이의 KDoc의 한 문장 | 282 |
| gauge의 장면 일곱 | 여덟이다. 붉은 확인이 되돌아간 TX A의 장면을 더했다(2.8절) | 282 |
| 장면 3은 처리 중인 생성이 `PENDING`을 1 올리는 것 | 아직 처리 중이라 말하는 대사(`Unchanged`)도 지난다. 그것이 없으면 `Changed`가 아닌 결과의 포트 호출을 잡는 장면이 없다(2.8절) | 282 |
| 장면 7의 결제는 결제의 제공 포트로 준비한다 | 기반의 도우미 `startPaymentWithoutConfirm`이 준비한다. 장면이 `PaymentRecorder`를 직접 주입하는 것은 `CODING_STANDARDS.md`가 막는다 | 282 |
| `PaymentFinderTest`의 세는 장면과 EXPLAIN 장면을 지운다 | 그 클래스의 장면은 그 셋뿐이라 클래스를 지웠고, 그 클래스만 부르던 기반의 도우미 둘(`confirmOrderRejectedBeforeCreate`, `confirmOrderLosingCreateResponse`)도 지웠다 | 283 |
| 결제의 요구 포트에 `PaymentStatusChangeListener`를 더한다 | 주문의 요구 포트(`application.order.required`)에 있다. 부르는 쪽은 주문의 `OrderPaymentSaga`뿐이고, [ADR 0013](../adr/0013-commerce-api-follows-splearn-hexagonal-structure.md)은 묻는 슬라이스가 제 `required`에 포트를 두게 한다(2.1절) | 닫는 리뷰 |

### 2.11 뒤 단계가 넘겨받는 것

- **실제 포트의 테스트 기반.** `BaseRealPortWebApiAdapterTest`. 사고 테스트는 이것을 이어 주입된 management 포트로 health와 Prometheus 원문을 가져온다. 요청은 `mvc`로 보내고, gauge를 움직일 쓰기는 테스트 트랜잭션 밖에서 커밋한다(2.4절). gauge는 응답이 돌아오기 전에 움직이므로 scrape 한 번이 그 값을 읽는다. 잃은 생성의 장면(2.7절)이 그 하네스가 돈다는 것을 보였다.
- **주문별 경로를 읽는 법.** 한 주문의 이벤트를 `orderId`(또는 `paymentId`, `pgOrderId`)로 걸러 차례대로 읽는다. `trigger`가 어느 길이 물었는지 말한다(확정, 고객의 대사, PG의 알림). 과제 4절의 두 가설은 이렇게 갈린다: `pg.create.outcome-unknown` 뒤에 반영 이벤트가 없으면 PG의 답을 잃은 것이고, `pg.create.accepted`나 `pg.lookup.found` 뒤에 `payment.apply.failed`가 있으면 받은 답을 반영하지 못한 것이다. 생성 전 거절의 반영도 TX B라서 `pg.create.rejected` 뒤의 `payment.apply.failed`도 반영의 실패다. 복구는 `trigger`가 `reconcile`인 `pg.lookup.found` 뒤의 `payment.apply.committed`(`result=changed`, `from=UNKNOWN`)로 보인다. key-value는 `test`의 console에 찍히지 않으므로 증거는 잡은 이벤트에서 읽는다(2.3절).
- **남은 위험.** 분산 추적을 하지 않았다(로그의 식별자로만 잇는다). 나이 지표가 없다. 실패한 반영의 예외 글은 내용을 검사하지 않는다. gauge는 재시작에 0에서 시작하므로 `sum`이 재시작 뒤 적게도 많이도 셀 수 있고, 인스턴스의 값이 음수일 수 있다. 한 scrape가 결제 하나의 상태 옮김을 반만 볼 수 있다. saga 밖에서 결제 상태를 쓰는 새 길은 세지 않는다. 모두 2.6절이다. 셋째 브랜치의 "기능별 검증 범위와 남은 위험"이 이것을 잇는다.
- **3단계에 남기는 말: runbook은 DB를 근거로, gauge를 경보로 삼는다.** (2026-11-27 명세 281) runbook은 "몇 건이고 어느 주문인가"의 근거를 결제의 행에 보내는 DB 질의로 삼는다. 2단계의 복구가 대상을 읽은 그 질의(`payment.status in ('UNKNOWN', 'PENDING')`, 3.4절)다. gauge `sum(commerce_payment_unresolved)`는 경보로 삼는다. 0보다 크게 머물면 끝나지 않은 결제가 있다는 신호이지만, 재시작 뒤에는 적게도 많이도 세고 인스턴스 하나의 값은 음수일 수 있어 수를 세는 근거가 아니다(2.6절). 그래서 경보가 울리면 runbook은 먼저 DB 질의로 끝나지 않은 결제가 정말 있는지 본다. 없으면 재시작이 남긴 차이다. (2026-11-27 PR 285의 리뷰가 많이 세는 쪽을 더했다.) runbook 자체는 이 브랜치가 쓰지 않는다.
- (2026-11-26 명세 266의 닫는 리뷰: **로그 이벤트를 saga 밖의 클래스로 옮기는 것은 미뤘다.** `trigger`와 결제가 도우미 넷을 함께 다니고, `OrderPaymentSaga`가 saga와 함께 이벤트의 필드도 만든다. 2단계가 이벤트를 어떻게 읽는지 본 뒤 정한다. 티켓은 없다.)

## 3. 2단계: 사고의 재현과 복구

결정의 출처는 [명세 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274)다(2026-11-26 인터뷰, 질문 28개). 과제의 몫은 [과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 1, 3, 4, 5, 7절이다: 정상과 장애의 구간, 같은 주문의 복구, 가설의 판정, 복구의 증명, 증거 파일. **운영 코드는 바뀌지 않았다.** 테스트, 증거, 문서뿐이다. ADR은 없다. 정한 것은 테스트의 구조와 증거의 배치이고 커밋 하나로 바꿀 수 있다. 용어집도 바꾸지 않았다. 사고, 기준 구간, 장애 구간, 복구는 테스트 실행의 말이라 이 문서에만 쓴다. (2026-11-27 [issue 289](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/289): [runbook](../runbook/week10.md)도 사고와 복구를 쓴다. 과제가 정한 절의 이름(복구 확인)이고, 용어집에 더하지 않았다.) 필요한 도메인 용어(결과 미확인, 끝나지 않은 결제, 대사, 알림)는 이미 있다.

이 절의 **운영자**는 사고를 맡아 진단하고 복구하는 사람이다. 용어집의 관리자(관리자 API를 부르는 역할)가 아니다. 이 단계의 운영자는 관리자 API를 하나도 부르지 않는다(3.8절). (2026-11-27 [issue 289](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/289): 운영자는 이제 [용어집](../../CONTEXT.md)의 말이다. 관리자의 Avoid에서 빠졌다. 이 절의 정의는 그것과 같고, [runbook](../runbook/week10.md)도 그 말을 쓴다.)

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

- **기반.** `BaseRealPortWebApiAdapterTest`(2.4절)를 잇고 `@EnableTestPg`를 단다. 확정이 요청마다 커밋해야 gauge가 그 행을 세므로 테스트 트랜잭션에서 빠지고(`Propagation.NOT_SUPPORTED`) 표를 스스로 비운다. `UnresolvedPaymentGaugeE2ETest`의 방식이다. (2026-11-27 명세 281: gauge는 이제 행을 세지 않고 커밋한 뒤 메모리에서 움직인다. 빠지는 까닭은 같다. 확정이 커밋해야 gauge가 움직인다. 이 컨텍스트를 함께 쓰는 다른 클래스가 없어 남은 수가 흘러들지 않으므로, 사고 테스트는 차이가 아니라 절대값 0, 3, 0을 단언한다.)
- **준비.** 소유자 한 명, 상품 하나(가격 1,000, 재고 20). 소유자는 포인트 잔액 50,000과 발급된 미사용 쿠폰 하나를 가지고 시작하고, 20건 가운데 어느 주문도 그것을 쓰지 않는다. 주문은 주문 포트로 만들고(`prepareOrder`, 1개씩), 그 뒤로는 `mvc`로만 움직인다. 테스트는 결제나 주문의 상태를 DB에 쓰지 않고, 테스트의 SQL은 모두 `select`다.
- **기준 구간.** 응답 유실이 꺼져 있다고 단언한다. 주문 10건을 차례로 확정한다(202. 생성이 `PENDING`으로 받아들여진다). 테스트가 그 주문의 확정 바로 뒤에 알림을 보내면(200), 커머스의 조회가 `SUCCESS`를 찾아 끝난다.
- **장애 구간.** 다른 주문 10건이다. 장애 구간에서 3·6·9번째로 만든 주문을 DB에서 읽은 실제 주문 ID로 가리킨다. 10건의 PG 거래를 응답 유실을 켜기 전에 모두 등록한다. 셋은 잃을 주문(`registerLost`, PG 거래는 `SUCCESS`)이고 일곱은 정상 주문이다. 응답 유실을 켜고 10건을 차례로 확정한다. 일곱은 기준 구간처럼 알림으로 끝난다. 셋은 202, 결제 중인 주문, 결과 미확인으로 남는다. 테스트는 그 셋의 알림을 보내지 않는다.
- **보류할 것은 그것뿐이다.** 테스트 PG는 스스로 알림을 보내지 않고 대사하는 scheduler도 없다. 장애 구간의 기록이 그것을 보인다: 세 PG 주문 번호에 커머스가 보낸 목록 조회가 0건이다.
- **관찰.** 단계마다 같은 `observe`다. management 포트에서 health와 Prometheus를 한 번씩 받아, gauge가 그 단계의 값을 읽을 때까지(10 s까지) 되풀이한다. 바뀐 수는 갱신 한 번 뒤에야 원문에 닿는다. 마지막으로 받은 것에서 기록을 만들고, 기록과 두 원문을 파일로 쓰고, 그 기록에 단언한다. (2026-11-27 issue 282: 되풀이하지 않는다. gauge가 응답이 돌아오기 전에 움직이므로 scrape 한 번이 그 단계의 값을 읽어야 한다. 그 scrape에서 기록을 만든다.)

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
| `baseline.json`, `fault.json`, `recovery.json` | 단계의 기록: 시각, 응답 유실, 주문 집합, 잃은 주문, DB의 끝나지 않은 결제, 주문마다 주문 ID·멱등 키·PG 주문 번호·거래 키·주문 상태·결제 상태·예약 수량·생성 수·목록 조회 수, 상품 재고, PG 거래 수, 생성 수, 두 번 생성된 PG 주문 번호, stub을 비껴간 요청 수, health의 상태, gauge의 두 값. `recovery.json`과 `follow-on.json`(4절)이 `faultFileSha256`을 더 싣는다 | 단계의 관찰 |
| `<phase>-health.json`, `<phase>-prometheus.txt` | 기록과 같은 scrape의 health와 Prometheus 원문, 받은 그대로 | 단계의 관찰 |
| `fault-log-events.json` | 잃은 세 주문의 잡은 이벤트(level, key-value, 예외) | 장애 구간의 관찰 뒤 |
| `hypotheses.json` | 가설과 예상, `writtenAt` | 첫 조회 전 |
| `verdict.json` | 가설, 주문마다의 조회와 관찰과 판정(3.3절) | 진단 끝 |
| `failure.json` | 실패한 단계, 예외의 클래스와 메시지, stack trace | 실패했을 때만, `finally` |

- 잃은 주문의 거래 키는 `null`로 쓴다. 프로젝트의 Jackson은 `null` 필드를 빼므로 주문의 기록은 `@JsonInclude(ALWAYS)`다. 파일은 테스트가 적기를 잊은 것이 아니라 커머스가 그 키를 몰랐다고 말한다.
- 예약 수량은 주문 상태에서 온다. 결제 중인 주문이면 품목의 수량이고 아니면 0이다(용어집의 재고 예약). 예약이 정말 남았다는 것은 상품 재고가 보인다.
- **단언은 파일에 쓴 값에 한다.** 단계마다 기록 하나를 만들어 쓰고 그 기록에 단언한다. health와 Prometheus 원문은 기록의 gauge와 health를 읽은 그 scrape의 것이다. 파일이 있다는 것만으로는 아무것도 증명하지 않는다.
- `@BeforeEach`에서 실패하면 디렉터리가 아직 없어 `failure.json`을 쓸 곳이 없다.

**저장소에 실은 실행.** gauge를 메모리로 바꾼 브랜치의 끝의 확인(2.9절의 2026-11-27 확인)의 사고 실행 `20261127T014458Z-e8a33044`를 [`docs/evidence/week10/20261127T014458Z-e8a33044/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/d0dba7cc/docs/evidence/week10/20261127T014458Z-e8a33044)에 통째로 실었다. 파일 열두 개이고, Prometheus 원문 셋도 줄이지 않았다. `failure.json`은 없다. 실행은 `4c65ad3f`의 깨끗한 트리에서 돌린 끝의 확인의 것이고, 그 코드는 `a4c0f519`(issue 283)의 것이다. 원문의 `# HELP`는 지금의 설명(2.1절)이다. 2단계가 실었던 실행 `20261126T135338Z-47f59811`(`7902fb9f`의 코드, 걷은 gauge)은 지웠다. 그 실행은 git의 기록에 있다. 단언하는 값은 같고, 시각, PG 주문 번호, `# HELP`, 해시가 다르다. (2026-11-26 명세 274의 닫는 리뷰가 issue 279의 실행 `20261126T120602Z-0c742bb1`를 그 실행으로 바꾼 적이 있다.) (2026-11-27 issue 290: 저장소에 실은 실행은 이제 3단계의 끝의 확인(4.10절)의 `20261127T045326Z-5f278390`이고 후속 단계의 파일 다섯을 더 싣는다(4.3절). 이 절이 말한 `20261127T014458Z-e8a33044`는 지웠고, 위의 링크는 그것을 지우기 전의 커밋 `d0dba7cc`를 가리킨다. 아래 표의 값은 그 실행의 것이다. 새 실행도 주문 ID와 앞의 네 단계가 단언하는 값은 같고 시각, PG 주문 번호, 해시가 다르다.) (2026-11-27 명세 286의 닫는 리뷰: 리뷰가 사고 테스트를 고쳤으므로 그 실행을 `f854f588`의 끝의 확인의 `20261127T053810Z-324a6dcf`로 바꿨다(4.3절).) (2026-11-27 PR 291의 리뷰: 다시 `a55e8448`의 끝의 확인의 `20261127T055722Z-c9dcca96`으로 바꿨다(4.3절).)

| 무엇 | 실은 실행의 값 |
| --- | --- |
| 주문 | 기준 구간 1–10, 장애 구간 11–20. 잃은 주문 13, 16, 19 |
| 기준 구간 | 01:44:58.933Z. 10건 모두 확정된 주문, 결제 성공, 생성 1, 목록 조회 1(알림 뒤 커머스의 것). 상품 재고 10, PG 거래 10, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, 응답 유실 꺼짐 |
| 장애 구간 | 01:44:59.672Z. 일곱은 기준 구간과 같다. 13, 16, 19는 결제 중인 주문, 결과 미확인, 예약 1, 생성 1, 목록 조회 0, 거래 키 `null`. DB의 끝나지 않은 결제도 13, 16, 19. 상품 재고 0, PG 거래 20, gauge `UNKNOWN 3.0`·`PENDING 0.0`, health `UP`, 응답 유실 켜짐. 세 주문의 이벤트는 저마다 `pg.create.outcome-unknown` 하나(WARN, `trigger=confirm`, `cause=NoHttpResponseException`) |
| 진단 | 가설은 01:44:59.686Z에 썼고, 조회는 01:44:59.698Z, .703Z, .706Z다. 셋 모두 조회가 `["SUCCESS"]`, 이벤트가 `[pg.create.outcome-unknown]`이고 A 기각, B 채택, C 기각 |
| 복구 | 01:44:59.804Z. 11–20 모두 확정된 주문, 결제 성공, 예약 0, 생성 1. 13, 16, 19는 목록 조회 2. 끝나지 않은 결제 없음. 상품 재고 0, PG 거래 20, 생성 20, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, 응답 유실 꺼짐. `faultFileSha256`의 `fault.json`은 `4ba0993e…`로 시작한다 |

### 3.6 복구가 실패하는 경우

복구가 성공하지 않는 경우는 새 장면을 두지 않았다. 대사가 PG 거래를 찾지 못하거나, 여럿을 찾거나, 맞지 않는 것을 찾거나, 조회할 수 없거나, 처리 중을 다시 찾으면 결제를 성공으로 바꾸지 않는다. 5주차와 1단계의 장면이 그것을 고정했다([결제 설계](./payments.md) 13.2, 14절).

| 경우 | 고정한 장면 | 결제의 끝 |
| --- | --- | --- |
| 조회할 수 없다(503, 응답 한도를 넘긴 멈춤, 열린 브레이커) | `OrderPaymentIntegrationTest`의 `a reconcile whose lookup is unavailable answers the stored state and changes nothing`, `the PG's reconcile throws PG_UNAVAILABLE on an unavailable lookup while the customer's answers the stored state`, `the PG's reconcile throws PG_UNAVAILABLE on an open breaker without a lookup, the customer's answers stored state`. HTTP는 `OrderConfirmationApiTest`의 `reconciling while the PG lookup is unavailable returns 202 with the location and changes nothing` | 저장된 상태 그대로. 고객의 대사는 그것으로 답하고 알림은 `PG_UNAVAILABLE` |
| PG 거래가 없다(0건) | `a restart before the create left keeps the payment unknown through a replay and an empty reconcile, without a create`(5주차의 장면 8), `lookups that find no transaction or several are not retried and never open the breaker` | 결과 미확인 그대로. 0건은 PG 거래가 없다는 증거가 아니다(ADR 0022 규칙 3) |
| PG 거래가 여럿이다 | `a reconcile that finds several or mismatched transactions applies none and logs a warning`의 `several transactions`(장면 9) | 결과 미확인 그대로, WARN 한 줄 |
| 금액이 다르다 | 같은 장면의 `another amount`(`reason=AMOUNT_MISMATCH`). 다른 PG 주문 번호(`PG_ORDER_ID_MISMATCH`)도 같은 장면이다 | 충돌. 결과 미확인 그대로, WARN 한 줄 |
| 처리 중을 다시 찾는다 | `OrderPaymentLogEventTest`의 `a TX B that leaves the payment as it was leaves payment apply committed at INFO as unchanged`(1단계). 처리 중인 결제를 대사하자 조회가 `PENDING`을 찾고 `result=unchanged`다. 그 장면은 이벤트만 단언하고 결제의 행은 다시 읽지 않는다. 대사가 처리 중을 찾은 뒤의 행을 단언한 장면은 5주차에도 1단계에도 없다. 5주차의 장면 2는 받아들여진 `PENDING` 생성 뒤 결제 중인 주문으로 남는 것을 보지만, 그 장면의 대사는 성공을 찾는다 | 처리 중 그대로(이벤트의 `unchanged`에서 읽은 것) |

대사는 어느 경우에도 생성을 보내지 않고 결제를 실패로 끝내지 않는다([결제 설계](./payments.md) 13.2). 그런 결제는 운영자가 PG에서 결과를 확인할 때까지 끝나지 않은 결제로 남고 gauge에 보인다. (2026-11-27 명세 281: 그 결제를 시작한 인스턴스가 그 뒤로 다시 시작하지 않았다면. 어느 주문인지는 DB에서 읽는다(2.11절).)

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
- **진단하려면 PG에 직접 닿아야 한다.** gauge는 몇 건인지만 말한다. (2026-11-27 명세 281: 그 수도 재시작 뒤에는 틀어지므로(2.6절) 몇 건인지의 근거도 DB다. gauge는 경보다(2.11절).) 어느 주문인지는 DB의 끝나지 않은 결제에서, 왜인지는 PG의 목록 조회와 그 주문의 로그 이벤트에서 읽는다. 커머스는 PG의 답을 보여 주는 endpoint가 없으므로, 운영자는 PG 계약의 `X-USER-ID`로 PG에 직접 조회해야 한다. 로그의 key-value는 dev·qa·prd의 logstash JSON에만 있다(2.3절).

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
| 사고 fixture | `IncidentPgTest` | 등록한 정상 주문이 `PENDING`으로 확정되고 알림으로 끝난다. 유실이 켜진 동안 등록한 잃을 주문이 결과 미확인으로 확정되고 생성 1, 목록 조회 0이다. 유실은 시작 전에 꺼져 있고 시작 뒤에 켜져 있으며 `finally`의 해제 뒤 잃을 주문의 생성이 답을 받는다. 두 번째 생성을 받은 PG 주문 번호를 돌려주고 한 번 받은 것은 돌려주지 않는다. 4개. (2026-11-26 PR 280의 리뷰: `orderId` 없는 생성은 등록한 주문의 stub이 있어도 맞지 않아 stub을 비껴간 요청으로 남고, 만든 PG 주문 번호에서 빠진다. 같은 생성에 `orderId`를 실으면 받아들여진다. 5개) |
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
| (2026-11-26 PR 280의 리뷰) 기준 구간의 첫 주문을 잃을 주문으로 등록하고 응답 유실을 켠다 | `PaymentIncidentE2ETest`의 기준 구간. 확정의 응답에서 `expected: "PENDING" but was: "UNKNOWN"`. 그 전에는 202만 보아 알림이 결과 미확인도 성공으로 끝내 지나갔다. `"phase" : "baseline"`의 `failure.json`이 남는다 | PR 280 |
| (2026-11-26 PR 280의 리뷰) `createdPgOrderIds`가 `orderId` 없는 생성에 던지게 되돌린다 | `IncidentPgTest`의 새 장면. `PathNotFoundException: No results for path: $['orderId']`. 사고 테스트의 `finally`가 이것을 부르므로, 던지면 단계의 실패를 가리고 `failure.json`을 쓰지 못한다 | PR 280 |

### 3.11 끝의 확인

```
scripts/final-check --app commerce-api
```

- 2026-11-26 `a0429979`의 깨끗한 트리에서 돌렸다(12:03:50Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(1 s).
- commerce-api는 126개 클래스, 1249개 테스트, 실패 0, 오류 0, 건너뜀 0이다(7 min 29 s). 1단계의 끝의 확인(2.9절)이 124개 클래스와 1244개 테스트였으므로, 이 단계가 2개 클래스(`IncidentPgTest` 4개, `PaymentIncidentE2ETest` 1개)와 5개 테스트를 더했다. 테스트 0개, 건너뜀, 연결 실패가 없다([과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 8절).
- 이 실행의 사고 실행이 `20261126T120602Z-0c742bb1`이다. (2026-11-27 명세 281: 저장소에 실은 사고 실행은 이제 2.9절의 2026-11-27 확인의 것이다(3.5절).)
- (2026-11-26 명세 274의 닫는 리뷰: 리뷰의 고침 `3136770a`(사고 테스트가 주문 ID를 `prepareOrder`가 돌려준 주문에서 읽고, 알림의 요청 JSON을 `json`이라 부르고, PG 계약의 사용자 ID를 `TestPg`에서 함께 쓴다)와 `7902fb9f`(테스트 설정에서 잰 사고의 걸린 시간을 뺀다) 뒤에 `7902fb9f`의 깨끗한 트리에서 다시 돌렸다(13:51:41Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 126개 클래스, 1249개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 25 s). 고침은 테스트를 더하거나 빼지 않았다. 사고 실행은 `20261126T135338Z-47f59811`이고 3.5절이 저장소에 실은 것이다. `7902fb9f` 뒤로는 문서와 증거만 바뀌었다.)
- (2026-11-26 PR 280의 리뷰: `201052bf`가 정상 주문의 확정이 처리 중으로 답하는지, 잃을 주문의 확정이 결과 미확인으로 답하는지 단언하고, `orderId` 없는 생성을 만든 PG 주문 번호에서 빼며 그 장면을 `IncidentPgTest`에 더했다(3.9절, 3.10절). `201052bf`의 깨끗한 트리에서 다시 돌렸다(14:25:10Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 126개 클래스, 1250개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 38 s). 장면 하나가 늘었다. 이 실행의 사고 실행 `20261126T142707Z-1ff555ca`는 파일 열두 개, `failure.json` 없음이다. 3.5절의 실행과 단언하는 값이 같고, 파일마다 다른 것은 시각(`observedAt`, `writtenAt`, `lookedUpAt`), PG 주문 번호, 그리고 그것들에서 오는 해시뿐이다. 고침은 단언을 더하고 기록을 읽는 방식만 바꿔 파일이 싣는 필드는 그대로이므로, 3.5절의 실행을 그대로 둔다. 도는 동안 이 절과 3.9절, 3.10절을 작업 트리에 썼고, Gradle이 읽는 파일은 바뀌지 않았다. `201052bf` 뒤로는 문서만 바뀌었다.) (같은 리뷰의 둘째 보고: `7ff6d2e4`가 `orderId` 없는 생성의 장면에 등록한 주문의 stub과, `orderId`를 실은 같은 생성이 받아들여지는 대조를 더했다(3.9절). `7ff6d2e4`의 깨끗한 트리에서 다시 돌렸다(14:40:17Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-api는 126개 클래스, 1250개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 46 s). 사고 실행 `20261126T144218Z-9cea88b6`도 파일 열두 개, `failure.json` 없음이다. `7ff6d2e4` 뒤로는 문서만 바뀌었다.)
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
- **한계 둘.** 운영자가 고객으로서 대사한다는 것, 진단에 PG에 직접 닿아야 한다는 것(3.8절). runbook이 여기서 시작한다. (2026-11-27 명세 281: runbook이 DB와 gauge를 어떻게 나눠 읽는지는 2.11절의 "3단계에 남기는 말"이다.)
- **남은 위험.** 3.8절의 나머지와 2.6절. 기능별 검증 범위와 남은 위험이 이것을 잇는다.

## 4. 3단계: 후속 확인과 runbook

결정의 출처는 [명세 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286)이다(2026-11-27 인터뷰, 질문 21개). 과제의 몫은 [과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 6, 7, 8절이다: 복구한 주문의 후속 업무, 둘째 것 가운데 runbook, 기능별 검증 범위와 끝의 확인. **운영 코드는 바뀌지 않았다.** 테스트, 증거, runbook, 문서뿐이다. ADR은 없다. 정한 것은 테스트의 구조와 문서이고 커밋 하나로 바꿀 수 있다. 용어집은 운영자 하나를 더했고 관리자의 Avoid에서 뺐다(3절의 머리).

### 4.1 3단계가 만든 것

| 티켓 | 만든 것 |
| --- | --- |
| [287](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/287) | `PaymentIncidentE2ETest`의 고정한 시계, 후속 단계의 중계(복구한 주문 하나를 두 번 발행한다), commerce-streamer의 구매 활동 20건. 클래스의 토픽 `order-events-payment-incident`와 consumer group. |
| [288](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/288) | 같은 단계의 주간 job 실행과 랭킹 API의 확인, `follow-on.json`과 랭킹의 본문, 닫는 관찰, `@Test`의 새 이름, 랭킹의 표를 새로 만드는 `@BeforeEach`. |
| [289](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/289) | [`docs/runbook/week10.md`](../runbook/week10.md)와 [용어집](../../CONTEXT.md)의 운영자. |
| [290](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/290) | 이 절, 기능별 검증 범위(4.5절), 세 앱의 끝의 확인(4.10절), 저장소에 실은 증거(4.3절). |

네 티켓 모두 `src/main`의 파일을 바꾸지 않았다. 사고 테스트는 운영 코드의 틈을 드러내지 않았고, 그래서 이 명세 안에 새 티켓은 없다. 티켓마다 구현 중에 정한 것은 [3단계의 글감](../research/week10-payment-incident-follow-on-writing-material.md)에 있다.

### 4.2 후속 단계

`@Test`는 하나 그대로이고 복구 뒤에 `runFollowOn`을 돈다. 앞의 네 단계의 단언은 바뀌지 않았다. 이름은 `lost responses leave three payments unknown under health UP, a PG lookup tells why, reconcile recovers them once, and the weekly ranking counts all twenty`다. 이름이 만드는 168자의 줄(들여쓰기 포함) 때문에 그 함수 하나에만 ktlint의 줄 길이를 끈다(저장소의 유일한 suppression, `CODING_STANDARDS.md`의 날짜 메모).

**시계.** 테스트가 처음부터 `TestClock`을 고정한다. 랭킹 기간은 2026-09-07(월)부터의 한 주다.

| 때 | 시각 | 그때 하는 것 |
| --- | --- | --- |
| `CONFIRMED_AT` | 2026-09-08T10:00Z(화) | 기준 구간과 장애 구간의 확정, 알림 |
| `RECOVERED_AT` | 2026-09-10T10:00Z(목) | 고객의 대사로 하는 복구 |
| `REPEATED_AT` | 복구 + 1 s | 복구의 되풀이(두 번째 대사와 알림). 되풀이가 주문을 다시 확정하면 다른 `confirmed_at`을 쓰게 되어 두 단언이 본다 |
| `RELAYED_AT` | 복구 + 5 s | 중계의 첫 회차 |
| `PAST_THE_LEASE` | 중계 + 61 s | 멈춘 회차의 lease가 지난 뒤의 둘째 회차 |

고정을 먼저 하고 테스트를 그대로 돌리자 지나갔다(287). 명세가 준비한 물러설 길(실제 시계와 행에서 구한 랭킹 기간)은 쓰지 않았다. 증거의 시각(`observedAt` 따위)은 실제 시계다.

**중계와 토픽.** 테스트가 컨텍스트의 `OrderEventRelayer`에서 `publishDueEvents()`를 부른다. `order-events.relay.enabled`는 끈 그대로다. 클래스는 `@TestPropertySource`로 제 토픽을 쓰고, consumer group은 commerce-streamer에 넘기는 상수다(`OutboxDeliveryE2ETest`의 방식). 다른 클래스의 record가 이 클래스의 streamer에 닿지 않는다.

- `RELAYED_AT`의 한 회차가 20건을 모두 가져간다(`CLAIM_BATCH_SIZE` 20, `nextAttemptAt, id`의 차례). 09-08에 확정한 17건이 먼저이고 09-10에 복구한 셋이 뒤다.
- 그 회차는 마지막 행, **복구한 셋째 주문**의 broker 확인 뒤, 발행됨으로 표시하기 전에 멈춘다. 그 행만 던지는 클래스 안의 stub이다. 19건은 `PUBLISHED`(시도 1), 그 한 건은 `PENDING`(시도 1)이다.
- `PAST_THE_LEASE`의 둘째 회차가 그 한 건을 다시 발행한다. 20건 모두 `PUBLISHED`이고 그 한 건만 시도 2다.

**commerce-streamer.** 후속 단계에서만 bootJar로 시작하고(`CommerceStreamerProcess`), 그 단계의 `finally`와 `@AfterEach`(표를 비우기 전, `try` 안)에서 끝낸다. 테스트는 토픽에서 읽은 record의 수만큼 streamer의 전달을 기다린 뒤, streamer가 쓴 표를 `select`로만 읽는다. 구매 활동의 단언이 토픽의 단언보다 앞이다. 빠진 행이 기다림의 시간 초과가 아니라 업무의 수(19)로 붉게 하려는 것이다.

**주간 랭킹.** `@BeforeEach`가 랭킹의 표와 Batch의 표를 새로 만든다(`WeeklyRankingBatchTables.reset`). 랭킹 API를 읽고, commerce-batch를 bootJar로 한 번 돌리고(`CommerceBatchProcess`, `periodStart=2026-09-07`, 스냅샷 `2026-09-07-r1`, 차수 1), 다시 읽는다. 차수 2는 없다. 늦은 차수와 chunk 재시작은 9주차의 장면에 두고 끝의 확인이 그것을 돌린다(4.5절).

**단언.** 모두 쓴 기록에서 읽는다(3.5절의 규칙).

| 본 것 | 값 |
| --- | --- |
| 아웃박스 | 20건의 주문마다 행 하나, 모두 `PUBLISHED`. 두 번 발행한 주문만 시도 2 |
| 따로 세는 네 수 | 토픽의 record 21(서로 다른 이벤트 ID 20, 두 번 온 것은 복구한 셋째 주문의 것), 아웃박스 행 20, 구매 활동 20, 처리한 주문 확정 20 |
| 구매 활동 | 주문마다 하나, 그 상품, 수량 1. 기준 구간 10, 장애 구간 10. `confirmed_at`은 주문의 것이고 그 주 안이다. 복구한 세 주문은 주문 ID로 하나씩, `confirmed_at`은 `RECOVERED_AT` |
| 주문의 `confirmed_at` | 복구한 셋은 `RECOVERED_AT`, 나머지 17은 `CONFIRMED_AT`. 되풀이한 대사와 알림 뒤에도 그대로다 |
| 랭킹 API, 실행 전 | `NOT_PUBLISHED`, `snapshotId` 없음, 항목 없음 |
| 랭킹 API, 실행 뒤 | `PUBLISHED`, `2026-09-07-r1`, 항목 하나: 1위, 그 상품, 점수 20 |
| 스냅샷 | `weekly_ranking_snapshot`의 행 하나: 차수 1, `FROZEN`, `input_count` 20 |
| 닫는 관찰 | 같은 `observe`. health `UP`, gauge `UNKNOWN 0.0`·`PENDING 0.0` |
| 다시 읽은 복구의 증명 | 상품 재고 0, PG 주문 번호마다 생성 수 1, PG 거래 20(두 번 생성된 것 없음, stub을 비껴간 요청 0), 20건 모두 확정된 주문·결제 성공과 PG의 거래 키, 소유자의 포인트와 쿠폰의 행이 실행의 처음과 같다, 장애 구간의 파일의 SHA-256이 `recovery.json`의 것과 같다 |

### 4.3 증거

쓰는 곳과 규칙은 3.5절 그대로다. 후속 단계가 같은 실행의 디렉터리에 파일 다섯을 더한다.

| 파일 | 내용 | 쓰는 때 |
| --- | --- | --- |
| `follow-on.json` | `observe`의 단계 기록(3.5절의 필드)에 `followOn`을 더한 것: 주문마다 이벤트 ID, 아웃박스의 상태와 시도 수, 구매 활동의 행(목록이라 두 번 기록된 행이 있으면 보인다), 스냅샷의 행, 소유자의 행, 네 수 | 후속 단계의 관찰 |
| `follow-on-ranking-before-run.json` | job 전의 랭킹 API 본문, 받은 그대로(UTF-8) | job 전 |
| `follow-on-ranking.json` | job 뒤의 랭킹 API 본문, 받은 그대로 | job 뒤 |
| `follow-on-health.json`, `follow-on-prometheus.txt` | 기록과 같은 scrape의 원문 | 후속 단계의 관찰 |

**저장소에 실은 실행.** 이 단계의 끝의 확인(4.10절)의 사고 실행 `20261127T055722Z-c9dcca96`을 [`docs/evidence/week10/20261127T055722Z-c9dcca96/`](../evidence/week10/20261127T055722Z-c9dcca96/)에 통째로 실었다. 파일 열일곱 개(3.5절의 열두 개와 위의 다섯)이고 Prometheus 원문 넷도 줄이지 않았다. `failure.json`은 없다. 실행은 `a55e8448`의 깨끗한 트리에서 돌린 끝의 확인의 것이다. 앞서 실었던 `20261127T014458Z-e8a33044`(3.5절)는 지웠다. 그 실행은 git의 기록에 있다. (2026-11-27 명세 286의 닫는 리뷰: 리뷰가 사고 테스트에 단언 하나를 더했으므로 issue 290이 실은 `d0dba7cc`의 실행 `20261127T045326Z-5f278390`([지우기 전](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/64e4977f/docs/evidence/week10/20261127T045326Z-5f278390))을 `f854f588`의 실행 `20261127T053810Z-324a6dcf`로 바꿨다. 값은 같고 시각, PG 주문 번호, 해시만 다르다.) (2026-11-27 PR 291의 리뷰: 리뷰가 스냅샷의 상태를 단언에 더했으므로 그 실행([지우기 전](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/190f4721/docs/evidence/week10/20261127T053810Z-324a6dcf))을 다시 이 실행으로 바꿨다. 값은 같고 시각, PG 주문 번호, 해시만 다르다.)

| 무엇 | 실은 실행의 값 |
| --- | --- |
| 주문 | 기준 구간 1–10, 장애 구간 11–20. 잃은 주문 13, 16, 19 |
| 기준 구간 | 05:57:22.516Z. 10건 모두 확정된 주문, 결제 성공. 상품 재고 10, PG 거래 10, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, 응답 유실 꺼짐 |
| 장애 구간 | 05:57:22.942Z. 13, 16, 19가 결과 미확인이고 DB의 끝나지 않은 결제도 그 셋. 상품 재고 0, PG 거래 20, gauge `UNKNOWN 3.0`·`PENDING 0.0`, health `UP`, 응답 유실 켜짐 |
| 진단 | 가설은 05:57:22.953Z에 썼고, 조회는 .961Z, .965Z, .967Z다. A 기각, B 채택, C 기각 |
| 복구 | 05:57:23.033Z. 끝나지 않은 결제 없음, 상품 재고 0, PG 거래 20, 생성 20, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`. `faultFileSha256`의 `fault.json`은 `b56d7472…`로 시작한다 |
| 후속 | 05:57:34.757Z. 아웃박스 20건 모두 `PUBLISHED`, 주문 19만 시도 2. record 21, 아웃박스 행 20, 구매 활동 20, 처리한 주문 확정 20. 구매 활동의 `confirmed_at`은 13, 16, 19가 2026-09-10T10:00Z, 나머지 17이 2026-09-08T10:00Z. 스냅샷 `2026-09-07-r1`(차수 1, `FROZEN`, 입력 20, `frozen_at` 05:57:34.581Z). 랭킹은 실행 전 `NOT_PUBLISHED`, 실행 뒤 `PUBLISHED`, 1위 상품 1 점수 20. 상품 재고 0, 생성 20, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP` |

### 4.4 runbook

[`docs/runbook/week10.md`](../runbook/week10.md)는 한국어이고 증상 하나를 다룬다: health가 `UP`인 동안 끝나지 않은 결제가 남는다. 절은 과제의 여섯(증상, 진단 질의, 조치 전제, 중단 조건, 복구 확인, 재진입)에 복구와 한계를 더한 여덟이다.

- **DB가 근거이고 gauge는 경보다.** 2.11절의 "3단계에 남기는 말" 그대로다. 경보 `sum(commerce_payment_unresolved)`가 울리면 먼저 결제의 행에 보내는 `select`로 끝나지 않은 결제가 정말 있는지 본다.
- **명령은 placeholder와 함께 싣고, 명령마다 사고 테스트가 그것을 어디까지 돌렸는지 적는다.** 테스트는 셸을 부르지 않는다. 같은 질의와 요청을 JDBC와 HTTP 클라이언트로 보내고, 어떤 것은 runbook의 것보다 좁거나 넓다. host와 자격 증명은 없다.
- **DB에 쓰는 SQL과 다시 결제하는 명령이 없다.** 결제를 바꾸는 명령은 고객의 대사 하나다. PG가 실패를 말해도 손으로 반영하지 않는다.
- 아웃박스 행이 없는 복구한 주문의 다음 걸음은 누락 복구의 시작 속성 `order-events.recovery.order-ids`다. 사고 테스트는 행을 빠뜨리지 않으므로 그 속성은 7주차의 `OrderEventRecoveryRunnerTest`가 고정한 것을 인용한다.

### 4.5 기능별 검증 범위와 남은 위험

[과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 8절의 표다. "돌린 클래스"는 이 단계의 끝의 확인(4.10절)이 돌린 것 가운데 그 기능을 맡은 것이고, 모두 지나갔다. 클래스를 모두 적지 않는다. 기능을 대표하는 것과 과제가 이름으로 든 것(`OrderPaymentIntegrationTest`, `CouponIssueIntegrationTest`, `WeeklyRankingJobRestartTest`)이다. "확인한 것"은 그 클래스의 장면이 이 실행에서 단언한 것이고, "확인하지 못한 것"은 어느 장면도 돌리지 않은 것이다. 이 단계는 새 회귀 테스트를 쓰지 않았다. 이 단계가 바꾼 것은 마지막 줄뿐이다.

| 기능 | 돌린 클래스 | 이번 실행이 확인한 것 | 확인하지 못한 것 |
| --- | --- | --- | --- |
| 카탈로그와 관리자 | commerce-api: `BrandAdminApiTest`, `BrandApiTest`, `ProductAdminApiTest`, `ProductApiTest`, `LikeApiTest`, `OrderAdminApiTest`, `CouponCampaignAdminApiTest`, `ProductCacheTest`, `ProductCacheRedisFailureTest`, `ProductCacheDatabaseFailureApiTest`, `ProductQueryPerformanceApiTest`, `LockFailureApiTest` | 브랜드·상품·좋아요의 API와 관리자가 아닌 요청의 403. 관리자의 쓰기가 커밋한 뒤 상품 목록 캐시가 새 값을 보이는 것, Redis가 멈추거나 느릴 때 목록이 DB에서 한도 안에 답하는 것. 시드 위에서 `(brand_id, price, id DESC)` 인덱스를 보이지 않게 해도 같은 첫 페이지인 것, 페이지마다 SQL 셋. 잠금을 기다리다 넘긴 쓰기의 409 | 운영 규모의 데이터와 그 위의 실행 계획(시드는 로컬의 것이다). Redis의 cluster와 장애 조치. 동시 부하 아래의 캐시 적중률. 관리자는 요청자 헤더의 사용자 역할(`ADMIN`)로만 가린다. 그 헤더를 싣는 게이트웨이의 인증은 범위 밖이다 |
| 포인트·주문·PG | commerce-api: `OrderPaymentIntegrationTest`, `OrderConfirmationApiTest`, `OrderConfirmerConcurrencyTest`, `PointChargerConcurrencyTest`, `LostUpdateControlTest`, `PaymentGatewayTest`, `ConnectTimeoutPaymentGatewayTest`, `ExhaustedPoolPaymentGatewayTest`, `UnresolvedPaymentGaugeE2ETest`, `OrderPaymentLogEventTest` | 확정이 PG에 생성 한 번을 보내고, 잃은 응답이 결제를 결과 미확인으로 두며 대사가 생성 없이 끝내는 것. 조회 재시도와 조회의 circuit breaker(열림, 60 s의 기다림, 다시 닫힘), 그동안 health `UP`. 같은 답의 두 반영이 주문과 재고를 한 번 바꾸는 것. 다섯 개 재고에 동시 확정 여덟이 다섯 건만 확정되는 것, 포인트의 동시 충전. gauge가 커밋한 뒤 움직이는 것, PG의 답마다의 로그 이벤트 | 실제 PG(모두 WireMock의 stub이다). 여러 인스턴스의 gauge `sum`과 재시작 뒤의 차이(2.6절). 운영의 네트워크에서 잰 시간 한도. 부하 |
| 이벤트와 아웃박스 | commerce-api: `OrderEventOutboxTest`, `OrderEventOutboxAtomicityTest`, `OrderEventOutboxClaimTest`, `OrderEventRelayerTest`, `OrderEventRelayFailedSendTest`, `OrderEventRelaySchedulerTest`, `OutboxDeliveryE2ETest`, `OrderEventRecoveryE2ETest`, `OrderEventRecoveryRunnerTest`. commerce-streamer: `OrderEventsListenerTest`, `OrderEventsListenerFailureTest`, `OrderEventsListenerStopTest`, `RedeliveryAfterCommitTest`, `KeyPartitioningTest` | 아웃박스 행이 확정과 함께 커밋되고 함께 되돌아가는 것. 두 회차의 claim이 겹치지 않는 것, 행마다의 실패와 20번째 실패의 막힌 이벤트. broker의 확인 뒤 다시 발행한 주문이 streamer에 두 번 닿고 구매 활동을 한 번 남기는 것. 처리할 수 없는 record에서 consumer가 멈추고 offset을 커밋하지 않는 것. 6주차의 두 경우를 재시작의 누락 복구가 복구하는 것 | broker 여럿의 cluster(로컬은 broker 하나다). broker의 재시작이 행을 실패로 만드는 것은 받아들였다(7주차). 중계를 두 인스턴스에서 켠 경우. 지연과 처리량 |
| 쿠폰 | commerce-api: `CouponIssueIntegrationTest`, `CouponIssueSlotsIntegrationTest`, `CouponIssueAdmissionTest`, `CouponIssueAdmissionClosingTest`, `CouponIssueAdmissionApiTest`, `CouponIssueRedisFailureApiTest`, `CouponIssueAdmissionStartupRunnerTest`, `CouponApiTest` | 첫 발급이 캠페인 행을 잠그고 쿠폰 하나를 커밋하는 것, 되풀이한 요청이 같은 쿠폰으로 답하는 것. 다섯 장의 캠페인에 동시 발급 여덟이 다섯 장과 소진 거절 셋인 것. Redis Set의 처리 자리와 그 Lua, 자리를 놓는 것. Redis가 멈추거나 느릴 때 새 사용자는 503이고 보유자는 같은 쿠폰을 받는 것, 시작 속성으로만 다시 여는 것 | Redis의 cluster와 장애 조치. 여러 인스턴스가 처리 자리를 나눠 쓰는 것(장면은 한 프로세스다). 실제 트래픽의 몰림 |
| 주간 랭킹 | commerce-batch: `WeeklyRankingFreezeTest`, `WeeklyRankingScoreTest`, `WeeklyRankingPublishTest`, `WeeklyRankingJobRestartTest`, `WeeklyRankingLatePurchaseTest`, `SnapshotInputDigestTest`. commerce-api: `WeeklyRankingApiTest`, `WeeklyRankingFinderTest`, `WeeklyRankingFlowTest`, `CommerceBatchProcessRunTest` | 랭킹 기간을 고정하고, 점수를 매기고, 게시하는 것. 실패한 chunk에서 재시작한 실행이 과제의 기대 랭킹의 checksum에 닿는 것, 게시의 쓰기에서 실패한 실행의 재시작. 늦은 구매가 뒤 차수로만 랭킹에 닿는 것. HTTP로 이은 흐름에서 다시 발행한 이벤트가 한 번 세어지는 것 | job을 누가 언제 시작하는가(테스트가 시작한다). 운영 규모의 구매 활동. commerce-batch는 랭킹 기간이 끝났는지 보지 않는다 |
| 이번 사고 | commerce-api: `PaymentIncidentE2ETest`, `IncidentPgTest`, `ManagementEndpointE2ETest` | 기준 구간 10건과 장애 구간 10건, 잃은 응답 셋이 health `UP` 아래 `UNKNOWN 3.0`으로 남는 것, 세 가설의 판정, 고객의 대사로 같은 셋을 생성 없이 복구하는 것. 복구한 주문이 중계, Kafka, 구매 활동을 지나 그 주의 랭킹에 점수 20으로 닿는 것, 두 번 발행한 한 건이 record 21과 구매 활동 20으로 갈리는 것(4.2절) | 한 꼴의 사고뿐이다(4.7절). 실제 PG의 알림. runbook의 명령 자체는 아무도 돌리지 않았다. 운영자가 고객의 식별자를 싣는 길 |

이 표는 로컬에서 통제한 사고 하나와 관련 회귀가 지나갔다는 것을 말할 뿐이다. **운영 배포, 실제 계정, 부하, 모든 장애의 검증을 말하지 않는다.** 모든 장면은 노트북의 Testcontainers(MySQL, Redis, Kafka broker 하나)와 WireMock의 PG에서 돌았다.

### 4.6 고르지 않은 것

- **운영자의 대사 endpoint, 커머스의 진단 endpoint, 자동 대사 scheduler.** 3.7절 그대로다. 그 대가가 runbook의 첫 두 한계다.
- **중계, commerce-streamer, commerce-batch의 새 지표나 로그.** 후속 단계는 그것들이 쓴 행을 읽는다.
- **`order-events.relay.enabled`를 켜는 것.** scheduler가 앞 단계에서 기준 구간의 행을 발행해 3.4절의 "행이 그대로다"를 깬다.
- **복구 전에 17건만 발행해 점수 17을 먼저 보이는 것.** 다음 주에 고정한 붉은 확인이 17을 보인다(4.9절).
- **차수 2, 늦은 구매, chunk 재시작의 새 장면.** 9주차의 장면이 있고 끝의 확인이 돌린다.
- **새 회귀 테스트.** 4.5절의 표는 있던 장면에서 읽는다.
- **runbook의 글을 자동으로 확인하는 것.** 4.7절의 한계다.

### 4.7 한계

runbook이 적는 한계가 먼저다([runbook](../runbook/week10.md) 8절).

- **운영자는 고객으로서 대사하고, 그것이 운영자였다는 기록은 남지 않는다.** 로그 이벤트의 `trigger`도 고객의 것과 같은 `reconcile`이다(3.8절).
- **진단하려면 PG에 직접 닿아야 한다.** 운영자는 PG의 주소와 PG 계약의 사용자 ID를 알아야 하고, 로그의 key-value는 logstash JSON에만 있다(3.8절).
- **runbook의 글을 자동으로 확인하는 것이 없다.** 열이나 endpoint가 바뀌면 테스트는 따라 바뀌어도 runbook은 그대로일 수 있다.
- **사고 테스트는 한 꼴의 사고만 돌렸다.** 생성의 응답을 잃은 결과 미확인 셋이고 PG 거래는 모두 `SUCCESS`였다. runbook의 중단 조건의 경우들, 처리 중으로 남은 결제, 누락 복구는 사고 테스트에서 돌리지 않았다.

고정한 시계가 바꾼 것.

- **시계를 따르는 것은 commerce-api가 주입받는 `Clock`뿐이다.** 주문의 `confirmed_at`, 구매 활동의 `confirmed_at`(이벤트가 싣는 것), 아웃박스의 `next_attempt_at`과 lease가 그것을 따른다. `BaseEntity`의 `created_at`·`updated_at`은 `Instant.now()`라 실제 시각이다. 그래서 실은 증거의 행은 `created_at`(2026-11-27)이 `confirmed_at`(2026-09)보다 늦다. runbook의 진단 질의가 상태 시각으로 읽는 결제의 `updated_at`(2.6절)도 테스트에서는 실제 시각이다.
- **commerce-streamer와 commerce-batch는 자식 프로세스라 실제 시계다.** 스냅샷의 `frozen_at`과 게시의 시각은 2026-11-27이다. commerce-batch는 기간이 끝났는지 보지 않으므로 지난 주의 랭킹 기간을 그대로 고정한다.
- **테스트 PG의 시각과 증거의 시각(`observedAt` 따위)도 실제 시계다.** 장애와 복구는 업무 시각으로 이틀이 떨어져 있지만, 증거의 시각으로는 1 s 안팎이다.
- **복구의 되풀이가 1 s 뒤로 옮겨 갔다.** 3.4절의 되풀이는 이제 `REPEATED_AT`에 돈다. 되풀이가 주문을 다시 확정하면 다른 시각을 쓰게 하려는 것이다.

나머지.

- **두 번 발행한 것은 복구한 셋째 주문이다.** 회차가 마지막에 저장하는 행만 두 번 발행할 수 있다(4.11절). 첫째나 둘째 복구 주문을 다시 발행하는 것은 보지 않았다. 6주차와 7주차의 장면(`OrderEventsListenerTest`, `OutboxDeliveryE2ETest`)이 순서와 관계없이 같은 이벤트를 한 번 세는 것을 본다.
- **streamer는 파티션 사이의 차례를 지키지 않는다.** 그래서 테스트는 구매 활동의 차례가 아니라 주문마다의 결과를 단언한다.
- **랭킹은 상품 하나다.** 순위의 차례(점수, 상품 ID)는 9주차의 장면이 본다.
- 3.8절의 나머지(알림을 보류한 PG, 응답 유실의 한 꼴, 좁은 가설 C, 한 사용자와 한 상품)는 그대로다.

### 4.8 장면

| 이음매 | 클래스 | 장면 |
| --- | --- | --- |
| 사고의 lifecycle과 후속 | `PaymentIncidentE2ETest` | 3.2–3.4절과 4.2절. 1개 |

다른 클래스는 바뀌지 않았고 모두 그대로 지나간다(4.10절). `CommerceStreamerProcess`, `CommerceBatchProcess`, `WeeklyRankingBatchTables`, `TopicReader`, `TestClock`은 있던 것을 그대로 쓴다. 중계를 멈추는 stub은 이 클래스 안에 있다. 한 클래스만 쓰는 stub은 그 클래스에 둔다(`CODING_STANDARDS.md`).

### 4.9 붉은 확인

커밋하지 않은 로컬 수정으로 돌리고 되돌렸다(2026-11-27). 명세 286이 정한 셋이다. 셋 모두 `"phase" : "follow-on"`의 `failure.json`을 남겼다. 기록은 [3단계의 글감](../research/week10-payment-incident-follow-on-writing-material.md)에 있다.

| 수정 | 붉어진 장면 | 티켓 |
| --- | --- | --- |
| 중계가 복구한 첫 주문의 행을 건너뛴다(모든 claim에서 빼는 임시 stub) | `PaymentIncidentE2ETest`의 후속 단계. 구매 활동에서 `Expected size: 20 but was: 19`. 실패와 함께 찍힌 행에서 주문 13은 시도 2 뒤에도 `PENDING`이고 구매 활동에 없다 | 287 |
| 복구를 다음 주에 고정한다(`RECOVERED_AT` = 2026-09-15T10:00Z) | 같은 단계. `Expecting actual: ["rank 1 product 1 score 17"] to contain exactly: ["rank 1 product 1 score 20"]`. 쓴 본문도 점수 17이다. 랭킹의 단언이 행마다의 확인보다 앞에 있어서 점수가 보인다. 뒤에 있으면 `confirmedAt`의 주의 경계가 먼저 붉다 | 288 |
| commerce-streamer의 "이미 기록함" 확인을 비껴간다(`findByEventId`가 아무것도 찾지 않게 하고, 테스트 task가 bootJar를 다시 만든다) | 같은 단계. 다시 온 주문 19의 이벤트가 "같은 주문의 다른 이벤트 ID" 갈래에 닿고, 충돌이 consumer를 멈춘다(ADR 0033). 두 번째 구매 활동은 쓰지 않는다. `Waited 30 s for 21 recorded order confirmed events. State: 20 recorded`, 파티션 1은 10건 가운데 9건을 읽었다. 명세의 두 예상 가운데 "consumer가 멈춘다" 쪽이다 | 288 |

### 4.10 끝의 확인

```
scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer
```

- 2026-11-27 `d0dba7cc`의 깨끗한 트리에서 돌렸다(04:51:01Z 시작). 셸에 `BOOTSTRAP_SERVERS`는 없었다. ktlint가 통과했다(1 s).
- commerce-batch는 9개 클래스, 65개 테스트, 실패 0, 오류 0, 건너뜀 0이다(29 s). 9주차의 마지막 확인([주간 랭킹 설계](./weekly-ranking.md) 14.10)과 같은 수다. `WeeklyRankingJobRestartTest`는 12개다.
- commerce-api는 123개 클래스, 1248개 테스트, 실패 0, 오류 0, 건너뜀 0이다(6 min 34 s). gauge를 바꾼 브랜치의 마지막 확인(2.9절)과 같은 수다. 이 단계는 `PaymentIncidentE2ETest`의 `@Test` 하나를 늘렸을 뿐 클래스도 테스트도 더하지 않았다. 사고의 `@Test`는 이 실행에서 14.6 s, `WeeklyRankingFlowTest`의 한 장면은 14.9 s였다. 4.5절의 이름 있는 클래스: `OrderPaymentIntegrationTest` 63개, `CouponIssueIntegrationTest` 12개, `OutboxDeliveryE2ETest` 2개, `OrderEventRecoveryE2ETest` 1개.
- commerce-streamer는 18개 클래스, 68개 테스트, 실패 0, 오류 0, 건너뜀 0이다(50 s). 9주차의 마지막 확인과 같은 수다. 이 브랜치는 commerce-streamer와 commerce-batch의 파일을 바꾸지 않았다. 사고 테스트가 두 bootJar를 자식 프로세스로 돌리고, 9주차의 재시작과 늦은 차수의 장면을 건너뛰지 않으려고 셋을 모두 돌렸다.
- 세 앱 모두 테스트가 0개가 아니고, 건너뛴 것이 없으며, `check`가 종료 코드 0으로 끝났다([과제](https://app.notion.com/p/10-41e5f76d26548008a94ce9d57c7f67d4) 8절). 실행 뒤 `pgrep -fl "commerce-(batch|streamer)"`가 찾은 것이 없었다.
- 이 실행의 사고 실행은 `20261127T045326Z-5f278390`이다.
- `d0dba7cc`의 코드는 `90131aa2`(288)의 것이고, 그 뒤로는 문서만 바뀌었다. 확인은 깨끗한 트리에서 시작했고, 도는 동안 이 절을 작업 트리에 썼다. Gradle이 읽는 파일은 바뀌지 않았다.
- (2026-11-27 명세 286의 닫는 리뷰: 리뷰의 고침 `d1561f37`(후속 단계가 마지막 쓰기 뒤에 장애 구간의 파일을 다시 해시해 `recovery.json`의 값과 견준다. 복구가 하던 대로다)과 `f854f588`(runbook의 중단 조건이 재진입을 가리키고 다시 발행과 다시 전달을 함께 말하며, 랭킹 기간을 용어집의 말로 고정하고, 3.5절이 `follow-on.json`의 해시를 적는다) 뒤에 `f854f588`의 깨끗한 트리에서 다시 돌렸다(05:35:50Z 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고(1 s), commerce-batch는 9개 클래스, 65개 테스트(27 s), commerce-api는 123개 클래스, 1248개 테스트(6 min 24 s), commerce-streamer는 18개 클래스, 68개 테스트(51 s)이고 셋 모두 실패 0, 오류 0, 건너뜀 0이다. 고침은 단언 하나를 더했을 뿐 테스트를 더하거나 빼지 않았다. 사고의 `@Test`는 13.2 s, `WeeklyRankingFlowTest`의 한 장면은 15.2 s였고, 4.5절의 이름 있는 클래스의 수는 위와 같다. 실행 뒤 `pgrep -fl "commerce-(batch|streamer)"`가 찾은 것이 없었다. 사고 실행은 `20261127T053810Z-324a6dcf`다. `f854f588` 뒤로는 문서와 증거만 바뀌었다.)
- (2026-11-27 PR 291의 리뷰: 리뷰의 고침 `3d02f9df`(후속 단계가 스냅샷의 `FROZEN`을 단언한다. 4.2절의 표가 이미 그렇게 적었다)와 `a55e8448`(runbook의 재진입은 [runbook](../runbook/week10.md) 6.1절, 6.2절이 찾은 것만 받고, PG의 조회와 대사의 `curl`이 HTTP 상태를 찍고, 구매 활동을 주문과 상품마다 센다) 뒤에 `a55e8448`의 깨끗한 트리에서 다시 돌렸다(05:54:59Z 무렵 시작, `BOOTSTRAP_SERVERS` 없음). ktlint가 통과했고, commerce-batch는 9개 클래스, 65개 테스트(28 s), commerce-api는 123개 클래스, 1248개 테스트(6 min 28 s), commerce-streamer는 18개 클래스, 68개 테스트(48 s)이고 셋 모두 실패 0, 오류 0, 건너뜀 0이다. 사고의 `@Test`는 13.3 s, `WeeklyRankingFlowTest`의 한 장면은 14.3 s였다. 실행 뒤 `pgrep -fl "commerce-(batch|streamer)"`가 찾은 것이 없었다. 사고 실행은 `20261127T055722Z-c9dcca96`이고 4.3절이 저장소에 실은 것이다. `a55e8448` 뒤로는 문서와 증거만 바뀌었다.)

### 4.11 명세와 다른 것

2026-11-27 구현을 마치고 명세 286과 견준 것이다.

| 명세 | 구현 | 어디서 |
| --- | --- | --- |
| 복구한 주문 가운데 첫째의 이벤트를 두 번 발행한다. 있는 spy 도우미 `stopRelayAfterBrokerAckBeforeMarkingPublished`로 멈춘다 | 셋째의 것이다. 한 회차가 20건을 모두 가져가고 저장은 claim의 차례이므로, 공유 도우미가 던지는 첫 저장(기준 구간의 주문 1)에서 회차가 끝나면 20건 모두가 다시 발행되어 record가 40이다. 마지막에 저장하는 행만 한 건으로 남길 수 있다. 그 행만 던지는 클래스 안의 stub을 썼다. 사용자가 두 대안(던지지 않고 저장 하나를 잃는 것, 공유 도우미로 40건)보다 이것을 골랐다 | 287 |
| `follow-on.json`은 주문마다의 행, 스냅샷의 행, 네 수 | 그것들을 `followOn`에 담은 `observe`의 단계 기록이다. `observe`가 이미 `<phase>.json`을 쓰므로 같은 이름의 둘째 파일은 `CREATE_NEW`에 실패한다. 단계 기록의 필드(health, gauge, 재고, 생성 수 따위)가 함께 실린다 | 288 |
| runbook이 적는 한계 셋 | 넷이다. 사고 테스트가 한 꼴의 사고만 돌렸다는 것을 더했다(4.7절) | 289 |
| 조치 전제 3(PG의 조회가 금액이 맞는 `SUCCESS` 하나를 보인다)을 테스트가 돌린다 | 진단은 조회의 PG 상태만 단언한다. 금액과 PG 주문 번호가 맞는다는 것은 충돌 없이 반영한 복구의 대사에서 읽는다. runbook이 그렇게 적는다 | 289 |
