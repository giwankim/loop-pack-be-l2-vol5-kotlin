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

Recorded 2026-11-26 from issue 269's closing comment. The text below is the comment's.

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
