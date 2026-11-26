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
