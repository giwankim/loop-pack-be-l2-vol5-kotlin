# Week 10 payment observability: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266), then the spec's own.

## Issue 267: real health and Prometheus responses on an injected management port

Recorded 2026-11-26 from [issue 267's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/267#issuecomment-7183601115). The text below is the comment's.

Implemented as `2cb3bd1d`, with review fixes in `93b3b76f`, on top of `be09832a`, which commits the glossary's 끝나지 않은 결제 on its own. `a8a0a45d` starts the week's design doc (`docs/design/payment-incident.md`, one section per step) and this document. `scripts/final-check --app commerce-api` on `93b3b76f`, a clean tree, started 2026-11-26T05:16:46Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 119 classes, 1210 tests (5 min 41 s), 0 failures, 0 errors, 0 skipped**. Only this document changed after `93b3b76f`.

### Acceptance criteria

- [x] **The glossary edit is committed first, on its own.** `be09832a`, unchanged from the working tree.
- [x] **A new test base runs the application on a real port and exposes the injected management port to its subclasses. No test assumes 8081 is free.** `BaseRealPortWebApiAdapterTest : BaseWebApiAdapterTest` carries `@SpringBootTest(webEnvironment = RANDOM_PORT)` and gives `managementPort` (`@LocalManagementPort`) and `managementGet(path)`, a GET over the JDK's `HttpClient`. `monitoring.yml` sets `management.server.port: 8081`, and under `RANDOM_PORT` Boot sets it to 0: the run's log shows Tomcat on two free ports (54074 and 54075 in the first run).
- [x] **A scene fetches `/actuator/health` over HTTP on the management port and reads status UP.** `ManagementEndpointE2ETest`, first scene: 200 and `"status":"UP"`. The main endpoint shows only the overall status, so the body has no component status to match by mistake.
- [x] **The same scene fetches `/actuator/prometheus` over HTTP and finds a metric that already exists.** It finds `jvm_memory_used_bytes{` and `resilience4j_circuitbreaker_state{`.
- [x] **`env` and `heapdump` are still not exposed.** Second scene: both answer 404 on the management port.
- [x] **The writing material records whether export was on without extra configuration.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Prometheus export under test: on, with nothing added

Spec 266 concluded from reading jars that export stays on under test. A run now confirms it. Boot 4 turns metrics export off in tests only through `spring-boot-micrometer-metrics-test`, and that module is not on commerce-api's test class path. The first run of the scene was green with no property, annotation or dependency added for this base. Nothing was needed.

### Red check

Not committed: a `@TestPropertySource` exposing `health,prometheus,env` makes the not-served scene fail with `expected: 404 but was: 200`. The 404 comes from `monitoring.yml`'s exposure list, not from a path that matches nothing. The file was restored before the commit.

### Decisions made while implementing

- **Only the management reads go over HTTP.** Application requests still go through `mvc` on the test thread, so the inherited test transaction still rolls them back. The management server answers on its own threads and sees only committed rows. The gauge scenes that need their writes counted have to leave the test transaction, and the base's KDoc says so.
- **The JDK `HttpClient`, not `RestClient` or a test client.** `managementGet` must return a 404 as an answer rather than throw. `TestPgWarmUpConfig` already uses this client in the tests.
- **The class is `ManagementEndpointE2ETest`.** `CODING_STANDARDS.md` names an HTTP test after its adapter, which a management endpoint doesn't have, and marks a test that requests a real server (`BrandApiE2ETest`). The first name, `…ApiTest`, was changed in the review.
- **It is the fifth base, not the fourth.** Spec 266 calls it "넷째 테스트 기반", but four bases already existed (`BaseApplicationServiceTest`, `BaseWebApiAdapterTest`, `BaseCommittingApplicationServiceTest`, `BaseRepositoryTest`), as the spec's own problem statement says ("네 테스트 기반이 모두 mock 웹 환경"). ADR 0012 gains a dated note, since its revisit condition named this base. `CODING_STANDARDS.md` lists it.
- **The gauge's properties are not in the base yet.** Spec 266 says the base turns the gauge on and shortens its interval. The gauge doesn't exist, so the gauge ticket adds both to this base (ADR 0012: the base carries them).
- **The real port splits off one more cached context.**

## Issue 268: count 끝나지 않은 결제 by status through an index

Recorded 2026-11-26 from [issue 268's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/268#issuecomment-7183945834). The text below is the comment's.

Implemented as `a0aa265c`. `scripts/final-check --app commerce-api` on `a0aa265c`, a clean tree, started 2026-11-26T05:49:07Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 120 classes, 1214 tests (5 min 50 s), 0 failures, 0 errors, 0 skipped**.

### Acceptance criteria

- [x] **With payments in all four payment statuses stored, the method returns only the 결과 미확인 and 처리 중 counts, and zero for a status with no rows.** `PaymentFinder.countUnresolved()` returns `UnresolvedPaymentCount(unknown, pending)`. `PaymentFinderTest` makes one payment of each status through real confirmations against the test PG: an accepted create answered `SUCCESS`, one answered `PENDING`, a rejection before the create, and a lost create response. The setup checks that the four statuses came out, and the count is `(1, 1)`. A second scene stores two pending payments and gets `unknown = 0`.
- [x] **An EXPLAIN scene shows the count uses `IDX_PAYMENT_ON_STATUS`.** It runs `EXPLAIN FORMAT=TREE` on the statement the port sent, bound to the same values: `Group aggregate: count(0)` → `Filter: (payment.status in ('UNKNOWN','PENDING'))` → `Covering index range scan on payment using IDX_PAYMENT_ON_STATUS`. Without the index, the plan was `Table scan on payment` and an `Aggregate using temporary table`.
- [x] **`PaymentRepositoryTest` pins the new index beside the existing constraints.** The table's only non-unique index is `IDX_PAYMENT_ON_STATUS:1:status`.
- [x] **The migration SQL for the index is in `docs/migrations/`.** [`2026-11-26-payment-status-index.sql`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/a0aa265c/docs/migrations/2026-11-26-payment-status-index.sql).
- [x] **Red check.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.**

### Red check

This change was not committed: adding `and deleted_at is null` to the count. The EXPLAIN scene failed, but not the way spec 266 predicted. MySQL **still chose `IDX_PAYMENT_ON_STATUS`**, as `Index range scan … with index condition`, under a `Filter: (payment.deleted_at is null)`. It reads each payment's row to check `deleted_at`. A check on the index name alone would have stayed green. The scene asserts `Covering index` together with `using IDX_PAYMENT_ON_STATUS`, and "Covering" is the word that tells the two plans apart. The query was restored before the commit.

### Decisions made while implementing

- **The count is a native query.** `Payment` carries `@SQLRestriction("deleted_at is null")`, and Hibernate adds it to every JPQL query on the entity, aggregate queries included. A derived `countBy…` or a JPQL `@Query` would put `deleted_at` back.
- **The statuses are listed, not derived.** The service passes `UNKNOWN` and `PENDING`, one per field of `UnresolvedPaymentCount`. Deriving them from `!isFinished` would query a new unfinished status and then silently drop it. The review changed this.
- **The port passes status names as strings.** The native query binds names. Keeping the enum inside the port would rely on Spring Data converting a native projection, which was not tried.
- **The scan's kind is not pinned.** On four rows, whether MySQL picks a range scan or a full scan is the optimizer's choice. Both answer from the index alone, and both fail the red check.
- **ADR 0014 gets a dated memo on rule 1** for the exception. The design doc's §2 (issue 272) still has to state it, along with "revisit the count if payments can be deleted".
- **Two base helpers.** `confirmOrderRejectedBeforeCreate` and `confirmOrderLosingCreateResponse` exist so that `PaymentFinderTest` does not inject `OrderConfirmer` just to prepare data. `confirmOrder` shares their body. `CODING_STANDARDS.md` has dated notes for both, and for the class's `JdbcTemplate`.
- **The migration adds the index with `ALGORITHM = INPLACE, LOCK = NONE`.** It was not run against a non-test schema.

## Issue 269: the count as the gauge commerce.payment.unresolved over a real scrape

Recorded 2026-11-26 from [issue 269's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/269#issuecomment-7184749772). The text below is the comment's.

Implemented as `79a7c758`. `scripts/final-check --app commerce-api` on `79a7c758`, a clean tree, started 2026-11-26T06:09:40Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 123 classes, 1222 tests (5 min 47 s), 0 failures, 0 errors, 0 skipped**.

### Acceptance criteria

- [x] **Over real HTTP, the Prometheus text contains `commerce_payment_unresolved` for `status="UNKNOWN"` and `status="PENDING"`.** `UnresolvedPaymentGaugeE2ETest`, first scene, on `BaseRealPortWebApiAdapterTest`. The `# HELP` line carries the three things the description must state: what it counts, "can be one interval old", and "aggregate with max, not sum".
- [x] **One-order rehearsal.** `losesCreateResponse`, confirm over `mvc` (202): the scraped `UNKNOWN` value becomes `1.0` within a bounded wait, health answers 200 and `UP`, `PENDING` stays `0.0`. With the successful PG transaction stubbed, the customer's reconcile answers 200 and `UNKNOWN` returns to `0.0`. The PG's create count stays 1 and no request missed a stub.
- [x] **A 처리 중 payment shows as 1 under `status="PENDING"`.** A create accepted as `PENDING`; `UNKNOWN` stays `0.0`.
- [x] **A failed count turns both series to `NaN`, and the next good count restores the values.** A `PaymentQueryService` spy throws from `countUnresolved`. Both series scrape as `NaN` and the adapter logged a WARN with the exception. With the stub lifted, `UNKNOWN` returns to `1.0` and `PENDING` to `0.0`.
- [x] **In the default `test` context the gauge's bean and its metric are absent.** `UnresolvedPaymentGaugeDisabledTest` (on `BaseApplicationServiceTest`, the shared context) finds no `UnresolvedPaymentGauge` bean and no meter named `commerce.payment.unresolved`. `UnresolvedPaymentGaugeEnabledPropertyTest` reads the real `application.yml`, as `OrderEventRelayEnabledPropertyTest` does: on under local, dev, qa and prd, off under test, and 10s under the deployed profiles.
- [x] **No scene reads the gauge from a bean or a status JSON in place of the Prometheus text.** Every value is a line of the scraped text. The disabled scene checks only that the meter is absent.
- [x] **Red check.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Red check

Not committed: deleting `count.set(null)` from the refresh's `catch`, so a failed count keeps the last value. The scene `a failed count turns both series to NaN with a WARN, and the next good count restores the values` failed at its wait for `NaN` (`ConditionTimeoutException`: not fulfilled within 10 seconds). The scraped value stayed `1.0`. The source was restored before the commit.

### Decisions made while implementing

- **One stored count, both series read it.** The refresh stores an `UnresolvedPaymentCount?` in one `AtomicReference`, and `null` is `NaN` for both. A scrape never mixes two refreshes. The gauges are registered with Micrometer's supplier form, which holds the state strongly; the `obj, fn` form holds a weak reference.
- **The failure lasts until the scene has seen `NaN`.** The spec says the spy fails the count once. At the base's 100 ms interval the next tick overwrites a single failure, and a scrape can miss it. So the stub throws until the scene scrapes `NaN`, and the scene then lifts it. `CODING_STANDARDS.md` has a dated note for the spy.
- **`adapter.monitoring` is a sixth adapter layer** in `HexagonalArchitectureTest`, and it reaches only provided ports, like the relay's trigger. That keeps Micrometer out of the application layer (story 24). Without the layer, `ensureAllClassesAreContainedInArchitecture` fails.
- **The `test` profile turns it off, and every other profile has it on.** `matchIfMissing = true` plus `enabled: true` in the default document, `false` in the test document. The real-port base sets it on at 100 ms, so its context (and `ManagementEndpointE2ETest`'s) counts in the background. Neither captures SQL.
- **Two KDocs changed.** `OrderPaymentSaga` and `OrderPaymentIntegrationTest` said no metric finds an unknown payment, and the review found that `OrderEventRelayScheduler` said an instance with the relay off has no scheduling at all. The gauge now enables scheduling everywhere but `test`. The WARNs' new shape is issue 270's.
- **Requests go through `mvc`, as the spec says.** The confirm and reconcile helpers are a fourth copy of `OrderConfirmationApiTest`'s, like the earlier ones the issue 262 note records.
- **The real-port context with `@EnableTestPg` and the spy is one more cached context.**

## Issue 270: a log event with the order's identifiers for every PG answer

Recorded 2026-11-26 from [issue 270's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/270#issuecomment-7185332074). The text below is the comment's.

Implemented as `5a86efa7`. `scripts/final-check --app commerce-api` on `5a86efa7`, a clean tree, started about 2026-11-26T07:07:49Z (its logs' creation time) with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 124 classes, 1234 tests (6 min 14 s), 0 failures, 0 errors, 0 skipped**. The first run on the commit before its amend failed two `OrderEventRecovererTest` scenes. See the decisions below.

### Acceptance criteria

- [x] **Each of the seven events has at least one scene asserting its fields, its level and its `trigger`.** `OrderPaymentLogEventTest`, 12 scenes on `BaseCommittingApplicationServiceTest` with the test PG. Each asserts the event's exact key-value map (`containsExactlyInAnyOrderEntriesOf`), so an extra field fails it too.
- [x] **`pg.create.outcome-unknown` carries the failure kind in `cause` for a lost response and for an HTTP error status.** `NoHttpResponseException` for `losesCreateResponse`, `HTTP 503` for a 503 answer.
- [x] **The three `trigger` values each appear on their own path.** `confirm` on the create scenes. `reconcile` and `callback` on the found lookup (parameterised) and on two unavailable-lookup scenes. The callback one still throws `PG_UNAVAILABLE` after the event.
- [x] **No captured event has a key or value holding the user ID, the 멱등 키, the amount or card data.** One scene leaves `pg.create.accepted`, `pg.lookup.unavailable` and `pg.lookup.found`, the events carrying every added field. No key names the user, the key, the amount or the card. No value holds the 멱등 키, the card number or type, or the amount as a whole value. The user ID is checked by key only, because IDs restart at 1 and the user's ID can equal the order's.
- [x] **A replayed confirm with the same 멱등 키 leaves no event.** The create count stays 1, and nothing captured after the first confirm has an `event` key.
- [x] **Existing confirm and reconcile scenes pass unchanged, apart from any that asserted the old WARN text.** `OrderPaymentIntegrationTest` changed in two places. The lost-response scene reads `event` and `orderId` from the WARN's key-value pairs. The several-transactions row matches the new fixed sentence, because its CSV siblings still read TX B's `Conflicted` WARN text (issue 271's).
- [x] **Red check.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Red check

Not committed: `put("idempotencyKey", payment.idempotencyKey)` added to the saga's payload. 11 of 12 scenes failed. Among them, `no event carries the user ID, the idempotency key, the amount or card data` found the key `idempotencyKey`, and every per-event scene found an unexpected entry. The replay scene stayed green, since it leaves no event. The source was restored before the commit.

### Decisions made while implementing

- **One private helper, `logPgAnswer`, builds every event.** It takes a fixed Korean sentence, the level, the event name, the `Trigger` and the payment, plus optional fields. Null fields are dropped, so `transactionKey` shows only when known. kotlin-logging's `payload` reaches Logback as SLF4J key-value pairs (`LogbackLoggerWrapper` / `addKeyValue`), which is what the tests read.
- **The lookup events are left inside `retrieveTransaction`.** It is the one place both reconciles share. The unavailable event is logged before the rethrow, so the customer's path still answers the stored state, and the callback's path still turns it into `PG_UNAVAILABLE`. The adapter's lookup retry is inside the one call, so one reconcile leaves one event.
- **`transactionKey` is the found transaction's on `found` and `accepted`, and otherwise the payment's if known.** `pgStatus` goes only on `accepted` and `found`, the events that received a transaction.
- **`pg.lookup.several` carries no count.** The spec lists no such field. The old WARN's "PG 거래가 2개다" text is gone.
- **`cause` is the exception's message, as the spec says.** The exceptions' KDoc warns that a parse error's message can hold part of the response body, so `cause` can too. The old WARN already did this. Issue 272's design section should state it.
- **`OrderEventRecovererTest` now captures only `OrderEventRecovery`'s logger.** It read every INFO line in `com.loopers.application.order`, and the orders it prepares now leave `pg.create.accepted` there. Its KDoc already said it captured recovery's lines.
- **Review changes.** Every lookup scene now asserts no unmatched PG request (`CODING_STANDARDS.md`, 테스트 › 단언). The unavailable scene split into the customer's and the PG's, so the callback's `PG_UNAVAILABLE` is asserted instead of swallowed. The amount check matches whole values. `Trigger.field` became `Trigger.value`. A `?.let` guard became a braced `if`. Not taken: an enum bundling each event's level, name and sentence. Seven call sites read clearly as they are.

## Issue 271: a log event for TX B's result, so a failed apply differs from a lost response

Recorded 2026-11-26 from [issue 271's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/271#issuecomment-7185945672). The text below is the comment's.

Implemented as `81c7a44b`. `scripts/final-check --app commerce-api` on `81c7a44b`, a clean tree, started 2026-11-26T07:37:22Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 124 classes, 1244 tests (6 min 27 s), 0 failures, 0 errors, 0 skipped**.

### Acceptance criteria

- [x] **`payment.apply.committed` is asserted for each result.** `OrderPaymentLogEventTest`: changed to 성공, 실패 and 처리 중 (one parameterised scene over the create's PG status, `from` = `UNKNOWN`), unchanged (a lookup finding the pending transaction again), and conflicted at WARN (a callback finding a transaction under another key). Each asserts the exact key-value map and the event order.
- [x] **A 생성 전 거절 leaves `pg.create.rejected` followed by `payment.apply.committed`.** `changed`, `UNKNOWN` → `FAILED`, no `transactionKey`.
- [x] **With TX B forced to throw through `OrderPaymentSpies`, `payment.apply.failed` is logged with the order's identifiers, the response is the same error, and the payment is unchanged.** `failTxBOnceAfterItsWrites` after an accepted create: the confirm throws the same `DataAccessResourceFailureException` instance the event carries (`isSameAs`), and the payment is still 결과 미확인 with no transaction key. The review added the rejection entry's scene, with a new sibling stub `failRejectionTxBOnceAfterItsWrites`.
- [x] **Lost-response sequence.** `losesCreateResponse`: the only event is `pg.create.outcome-unknown`.
- [x] **Failed-apply sequence.** `pg.create.accepted` → `payment.apply.failed` on confirm, `pg.lookup.found` → `payment.apply.failed` on the customer's reconcile, and `pg.create.rejected` → `payment.apply.failed`.
- [x] **No captured apply event holds the user ID, the 멱등 키, the amount or card data.** The personal-data scene now leaves `pg.create.accepted`, `payment.apply.committed`, `pg.lookup.unavailable`, `pg.lookup.found` and `payment.apply.failed` (a failed callback apply), and checks every key and value as before.
- [x] **Red check.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.**

### Red check

Not committed: the `payment.apply.failed` log call guarded off. 3 of 21 scenes failed: `a TX B that fails after an accepted create leaves payment apply failed at WARN and rethrows the same exception`, `a TX B that fails after a found lookup leaves payment apply failed after pg lookup found`, and `no event carries the user ID, the idempotency key, the amount or card data` (its event list lacks the failed apply). The rejection-entry scene was added after the check. The source was restored before the commit.

### Decisions made while implementing

- **The saga logs around the proxy call, not inside TX B.** `applyAndLog` calls the `OrderPaymentSagaSteps` entry; a normal return means Spring has committed, and a commit-time failure throws out of the same call. Logging in `applyResult` would claim a commit before it happened.
- **TX B's entries return `AppliedAnswer(info, result)`.** The `ReconcileResult` was dropped once mapped to `OrderInfo`. `OrderPaymentSagaSteps` is not a provided port, so its return type was free to change. `failTxBOnceAfterItsWrites` needed no change.
- **The reconciles read the payment before the lookup.** `retrieveTransaction` now takes the payment, so the apply events (including `failed`) carry its identifiers. `logPgAnswer` became `logEvent`, with an optional `cause`. The private wrapper is `applyTransactionAndLog` (review rename).
- **The catch is `Exception`, and it only observes.** It rethrows the same instance. The exact catches around the PG calls stay exact, because there a wide catch could turn 결과 미확인 into 실패.
- **Apply events carry the applied transaction's `transactionKey`, not `pgStatus`.** `from` and `to` say what changed; the preceding `pg.*` event already carries `pgStatus`. The rejection entry has no key. A conflicted event carries no reason: `PaymentModifyService`'s own WARN still names it.
- **`OrderPaymentIntegrationTest`'s mismatch scene leaves out the conflicted apply event** when it reads the one WARN naming the mismatch, so it stays strict about that WARN.
- **One more cached context.** The `OrderPaymentSagaSteps` spy in `OrderPaymentLogEventTest`. `CODING_STANDARDS.md` has a dated note.
- **The design doc's §1.2 gains rows for 270 and 271.** 270's was missing.
- **Limits, for issue 272's section.** `payment.apply.failed` carries the exception, and a database message can quote values; the personal-data scene checks key-value pairs, not exception text. TX B only updates rows, so a duplicate-key message is unlikely. No scene reads the HTTP error of a failed TX B: the scenes pin the rethrown instance, and the controller advice is unchanged.

## Issue 272: record step 1 and its final check in the design doc

Recorded 2026-11-26 from [issue 272's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/272#issuecomment-7186188098). The text below is the comment's.

Implemented as `38006365`, docs only. `scripts/final-check --app commerce-api` on `22d77d50`, a clean tree whose code is `81c7a44b`'s, started 2026-11-26T07:55:52Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 124 classes, 1244 tests (6 min 18 s), 0 failures, 0 errors, 0 skipped**. Only documents changed after `22d77d50`.

### Acceptance criteria

- [x] **`docs/design/payment-incident.md` has a step 1 section covering the metric, the count and its index, the gauge's switch, the nine log events and the real-port test base.** [§2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/38006365/docs/design/payment-incident.md#2-1단계-끝나지-않은-결제의-지표와-결제의-로그-이벤트): 2.1 (name, `status` label, source, 10 s `fixedDelay`, `NaN`, `max`, the switch and the scheduler thread), 2.2 (the native count, `IDX_PAYMENT_ON_STATUS`, the EXPLAIN plan), 2.3 (the nine events with level and fields), 2.4 (the real-port base).
- [x] **The section states the limits.** 2.6: no age metric, no health indicator, no distributed tracing (identifiers only), `updated_at` as the status time and why it holds (`Payment.reconcile` sets the transaction key only when it moves the status), one scheduler thread shared with the relay, the count ignoring `deleted_at`, the ADR 0014 exception. It also carries the three limits the ticket closings left for this issue: `cause` can hold PG body text, `payment.apply.failed`'s exception text is unchecked for personal data, and no scene reads the HTTP error of a failed TX B.
- [x] **The four red checks are recorded with the scene each one turned red.** 2.8, plus issue 267's exposure check as a fifth outside the spec.
- [x] **The section lists what the next branches inherit.** 2.11: the real-port base, reading an order's path by `trigger` and the nine events (including how the two hypotheses split), and the remaining risks.
- [x] **The final check is recorded with its commit, timings and test counts.** 2.9.
- [x] **`scripts/check-doc-links` and `scripts/check-doc-notes` pass.** Both exit 0 under the pre-commit hook's `--warn-changed`. The three CHANGED lines are the skeleton's placeholders and its `### 2.1` heading, now §2.11; nothing linked to the old anchor.
- [x] **The closing comment is appended to the writing material.**

### Decisions made while implementing

- **The section follows `weekly-ranking.md` §12's shape**: 고르지 않은 것, 한계, 장면, 붉은 확인, 끝의 확인, 명세와 다른 것, then what the next branches inherit. The status line and §1.1's status cell got dated notes rather than rewrites.
- **2.10 lists five spec deviations** from the ticket closings: the fifth base, the gauge properties added in 269, the `deleted_at` red check losing only "Covering", the spy failing until `NaN` is scraped, and logging around the proxy call with `AppliedAnswer`.
- **Review fixes.** 불일치 (on 충돌's Avoid list) became 충돌. The scheduler-thread limit had `max` backwards: `max` picks the largest value, not the freshest, so a lagging instance shows a stale higher count while payments resolve. §2.11 adds `pg.create.rejected` → `payment.apply.failed` as a failed apply.
- **The final check's delta is against week 9's last count** (118 classes, 1208 tests): 6 classes and 36 tests.

## Spec 266: the closing review

Recorded 2026-11-26 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266#issuecomment-7186916095) of [spec 266](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266). The text is the comment's, with its blob URLs.

Step 1 of week 10 is built, and its closing review is done. `scripts/final-check --app commerce-api` on `c96fa48d`, a clean tree, started 2026-11-26T08:35:59Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 124 classes, 1244 tests (6 min 1 s), 0 failures, 0 errors, 0 skipped**. Week 9's last check ended commerce-api at 118 classes and 1208 tests, so this step adds 6 classes and 36 tests. The review's fixes add and remove no test, so the counts match the run on `22d77d50` that issue 272 recorded. Only documents change after `c96fa48d`.

Tickets 267–272 are closed, each with its own comment. [Design 1.2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/design/payment-incident.md#12-1단계가-만든-것) lists what the step built and [design 2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/design/payment-incident.md#2-1단계-끝나지-않은-결제의-지표와-결제의-로그-이벤트) holds the step. Production code changed in commerce-api only, and there is no ADR.

### Where the implementation differs

[Design 2.10](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/design/payment-incident.md#210-명세와-다른-것) has five rows, all from the tickets; this review added none. The ones that change what a reader would assume:

| The spec said | What is there | How it was found |
| --- | --- | --- |
| "넷째 테스트 기반" | The fifth. Four bases already existed | Issue 267 |
| Adding `deleted_at IS NULL` makes the EXPLAIN scene red because the index alone can't answer | Red, but MySQL still picks `IDX_PAYMENT_ON_STATUS`; only "Covering" is lost, so the scene asserts `Covering index` | Issue 268 |
| The spy fails the count once | The spy fails until the scene has scraped `NaN`, because at a 100 ms interval the next refresh can cover one failure before a scrape sees it | Issue 269 |
| `payment.apply.failed` is logged, then the exception rethrown | Logged around the proxied TX B call, not inside TX B, so the two TX B entries now return `AppliedAnswer` | Issue 271 |

**Now proven:** the gauge `commerce_payment_unresolved` is in the real Prometheus text on the injected management port, with both `status` series at 0; one order whose create response is lost moves `UNKNOWN` to 1 while health answers UP, and the customer's 대사 brings it back to 0 with one PG create; a pending payment shows under `PENDING`; a failed count shows `NaN` and the next success restores it; the count reads `IDX_PAYMENT_ON_STATUS` as a covering index; each of the nine log events carries its fields, level and `trigger`; a lost create response leaves `pg.create.outcome-unknown` with no `payment.apply.*` after it, and a failed apply leaves `payment.apply.failed` after `pg.create.accepted`, `pg.create.rejected` or `pg.lookup.found`, with the same exception rethrown.

**Proven by a red check, not by a committed test:** issue 267's exposure check. Widening the exposure to `env` turned the 404 scene red, so the 404 comes from `monitoring.yml`'s list ([design 2.8](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/design/payment-incident.md#28-붉은-확인)).

**Not proven here:** the HTTP error of a failed TX B (the scenes pin the rethrown instance at the service; the controller advice is unchanged), the user ID by value in the no-personal-data scene (IDs restart at 1, so only its key is checked), the text of `cause` and of a failed apply's exception, distributed tracing, the gauge's lag on an instance whose relay shares the scheduler thread, and the migration SQL outside the test schema ([design 2.6](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/design/payment-incident.md#26-한계)).

### The closing review

A two-axis review of `week10...feat/payment-observability` at `8ee05385` (18 commits, 31 files), with one reviewer on the standards and one on this spec. Standards found 1 soft breach of a documented rule and 7 possible smells. Spec found 3 partial requirements, 3 additions and no requirement implemented wrongly. Each was checked against the code and decided.

#### What changed

- **`4a1183b5`: the saga's new sentences are English.** The KDoc of `OrderPaymentSaga.confirm` and of the lost-response scene in `OrderPaymentIntegrationTest` each had one new Korean sentence about the gauge and the WARN event. Each is now its own English sentence, so no paragraph switches language halfway. Old Korean KDoc that the branch only reworded stays Korean.
- **`c96fa48d`: every logging helper of the saga takes `trigger` and `payment` first.** `applyTransactionAndLog` took them last and `retrieveTransaction` took them reversed, while `applyAndLog` and `logEvent` took them in that order.

#### Left as they were

- **A separate class for the log events.** `trigger` and the payment travel together, and the saga builds the events' fields beside the saga itself. Deferred until step 2 shows how it reads the events; design 2.11 carries a dated note.
- **`failRejectionTxBOnceAfterItsWrites` copies `failTxBOnceAfterItsWrites`** except for the stubbed entry. Each scene reads as one sentence; a shared helper would take the entry as a lambda.
- **`"UNKNOWN"` and `"PENDING"` written out in the gauge.** They are the Prometheus label values dashboards and alerts match on; derived from the enum, a rename would silently rename the series.
- **The unresolved statuses in three places** (the service's list, `UnresolvedPaymentCount`'s fields, the gauge's two series). A new unresolved status needs a new series and a spec change anyway.
- **`payment.apply.failed`, next to the 실패 payment status.** The spec chose the name.
- **The log appender's setup repeated in two new test classes,** as in about 11 existing ones. Moving it into `support/` would be a repo-wide refactor.
- **The spec's three partial items** (the user ID checked by key, the failed TX B checked at the service, two more cached contexts than "하나") are already stated in design 2.4 and 2.6.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/research/week10-payment-observability-writing-material.md#spec-266-the-closing-review).

## Draft PR body

The body of the PR from `feat/payment-observability` into `week10`, written at the spec's closing review (2026-11-26) in `/mattpocock-skills:pr`'s shape, with the final check on `c96fa48d` and the comment's link. It makes no throughput or lag claim; none was measured.

### Summary

Week 10 step 1. While a payment's result is unknown, health still answers UP. This branch makes such payments visible from outside commerce-api: a gauge counts 끝나지 않은 결제 by status, and the payment saga leaves one log event, with the order's identifiers, for every PG answer and every TX B result. A test base on a real port fetches the real health and Prometheus responses.

- **Spec:** issue 266, with tickets 267–272, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/266#issuecomment-7186916095)
- **Design:** [`docs/design/payment-incident.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/design/payment-incident.md) section 2: 2.1 the metric, 2.2 the count and its index, 2.3 the nine events, 2.4 the real-port base, 2.6 the limits, 2.8 the red checks, 2.10 what differs from the spec, 2.11 what steps 2 and 3 inherit
- **Writing material:** [`docs/research/week10-payment-observability-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-observability/docs/research/week10-payment-observability-writing-material.md), which holds every ticket's closing comment
- **Glossary:** 끝나지 않은 결제 (unresolved payment) in `CONTEXT.md`: a payment whose status is 결과 미확인 or 처리 중
- **No ADR.** ADRs 0012 and 0014 got dated notes (the fifth test base; the count as a Finder exception).

The gauge counts in the database, on a schedule, so the number holds across restarts and instances:

```text
UnresolvedPaymentGauge (adapter/monitoring, fixedDelay 10 s, off under `test`)
  PaymentFinder.countUnresolved()
    select status, count(*) … where status in ('UNKNOWN','PENDING') group by status   # covering IDX_PAYMENT_ON_STATUS
  keep UnresolvedPaymentCount, or null on failure                                    # null scrapes as NaN, never 0
GET /actuator/prometheus
  commerce_payment_unresolved{status="UNKNOWN"} / {status="PENDING"}                 # every instance reports the same DB count: read with max
```

The saga's events, and how they tell the assignment's two hypotheses apart:

```diff
 confirm                                         trigger=confirm
   PG create
+    accepted        → pg.create.accepted        → TX B → payment.apply.committed | payment.apply.failed
+    rejected        → pg.create.rejected        → TX B → payment.apply.committed | payment.apply.failed
+    outcome unknown → pg.create.outcome-unknown   (no TX B: the response was lost)
 reconcile                                       trigger=reconcile | callback
   PG lookup
+    found           → pg.lookup.found           → TX B → payment.apply.committed | payment.apply.failed
+    none | several | unavailable → pg.lookup.*
```

Every event carries `event`, `trigger`, `orderId`, `paymentId`, `pgOrderId`, plus the fields design 2.3 lists. None carries the user, the 멱등 키, the amount or the card. `payment.apply.failed` is logged around the proxied TX B call and rethrows the same exception, so responses are unchanged.

### Evidence

**Before:** nothing counted unresolved payments, the code had no business metric, and a rolled-back TX B left only a message and a stack trace with no order identifier. No test base started a real port, so no test fetched a real Actuator response.

**After:** on real MySQL and the test PG, values read from the scraped Prometheus text and from captured log events' key-value pairs.

- **The incident in one order** (`UnresolvedPaymentGaugeE2ETest`): confirm with a lost create response (202) → `commerce_payment_unresolved{status="UNKNOWN"} 1.0` while `/actuator/health` answers 200 UP → the customer's 대사 (200) → `0.0`. The PG saw one create.
- **`NaN`, not 0, on a failed count**, and the next success restores `1.0` / `0.0`.
- **The plan:** `Covering index range scan on payment using IDX_PAYMENT_ON_STATUS`; without the index, `Table scan on payment`.
- **The two hypotheses** (`OrderPaymentLogEventTest`, 22 scenes): a lost response leaves `pg.create.outcome-unknown` and nothing after it; a failed apply leaves `pg.create.accepted` → `payment.apply.failed`, and the confirm throws the same `DataAccessResourceFailureException` instance.

| Red check (local, never committed) | Result |
| --- | --- |
| `and deleted_at is null` in the count | The EXPLAIN scene: the plan keeps the index but loses `Covering` |
| The refresh keeps the last value on failure | The `NaN` scene: `ConditionTimeoutException` after 10 s, still `1.0` |
| `idempotencyKey` put in the payload | 11 of `OrderPaymentLogEventTest`'s 12 scenes |
| The `payment.apply.failed` log call removed | 3 of 21: both failed-apply branches and the no-personal-data scene |
| Exposure widened to `env` | `ManagementEndpointE2ETest`: `expected: 404 but was: 200` |

`scripts/final-check --app commerce-api` on `bf124279`, a clean tree, after the PR review's test fix, with ktlint passing and only documents changed since (6 min 11 s; the run on `c96fa48d` before the review had the same counts):

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-api | 124 (118 before) | 1244 (1208 before) | 0 |

### Merge Danger

**Door:** two-way

The one schema change is an added index (`docs/migrations/2026-11-26-payment-status-index.sql`, `ALGORITHM = INPLACE, LOCK = NONE`), which a `DROP INDEX` undoes. The gauge, its properties and the log events go away with a revert.

**Blast Radius:** commerce-api

- Every commerce-api instance outside `test` now runs scheduling and one count query every 10 s. Before, only an instance with the relay on had scheduled work. The relay and the gauge share Boot's single scheduler thread, so a long relay round delays the gauge, and a slow count, such as one waiting on an unresponsive database, delays the relay (design 2.6).
- Each PG answer now adds an INFO or WARN line, roughly two lines per confirmation.
- TX B's two entries return `AppliedAnswer` instead of their old types. Only the saga calls them.
- One scrape can mix two refreshes' counts: each series reads the stored count on its own, so a refresh between the two reads shows a payment that just moved in both series or in neither. The next scrape corrects it (design 2.1).
