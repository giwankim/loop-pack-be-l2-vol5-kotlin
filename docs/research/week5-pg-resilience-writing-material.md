# Week 5 PG resilience: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 3 ticket's closing comment. The rules behind it are in [ADR 0030](../adr/0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md) and [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md), on top of step 2's [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) through [ADR 0029](../adr/0029-pg-order-id-is-minted-per-payment.md). Step 2's material is in [the order payment writing material](./week5-order-payment-writing-material.md).

## Issue 162: a restarted commerce sends no create and finishes pending payments

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/162#issuecomment-6357044670) of [issue 162](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/162). The rules are rules 2 and 3 of [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) (TX A's commit is the send record; create is never resent), and the scene is the "재시작 (장면 8)" section of [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159). The code is:

- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the scene and its `restarted` helper
- [`TestPg`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPg.kt): `createCount(pgOrderId)`
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the second-context technique, under 도우미

### Landed

Two commits on `worktree-issue-162` (local, not yet pushed or merged into `feat/reconcile`), on top of `feat/reconcile`'s `c9144f6`:

- [`c34f72a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c34f72a) is the test, the `TestPg` helper and the standards entry. No production code changes.
- [`a7c94c1`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/a7c94c1) creates this writing-material file.

### Acceptance criteria

- [x] **Starting the second context sends 0 creates and 0 lookups to the test PG.** The test records `requestCount()` before the restart. Once the second context is up, the count is unchanged, with one create (the lost response's) and zero lookups. Nothing runs at startup, and there is no scheduler.
- [x] **The second context's orchestrator and gateway are different instances from the first context's.** `OrderConfirmer` and `PaymentGateway` are compared through `AopTestUtils.getUltimateTargetObject`, so the proxies' targets are compared, not just the proxies. `OrderPaymentSaga` is `OrderConfirmer`'s only implementation.
- [x] **The payment stopped before sending is still outcome unknown after the restart, with its reservation held and create at 0. The new context's reconcile leaves it the same, still with create 0.** The first context's `PgTransactionService` spy throws `ProcessStopped` before sending. After the restart the payment reads UNKNOWN with stock 3. The new reconcile sees `[]` and answers PAYMENT_PENDING / UNKNOWN. The final read shows no transaction key, stock 3 and `createCount(stoppedPgOrderId) == 0`.
- [x] **For the payment with a lost create response, the new context's reconcile confirms the order with the PG's transaction key. Create stays at 1 and the stock is consumed once.** The first context's confirm answers UNKNOWN with create 1. After the restart the payment is still UNKNOWN with stock 3. The new reconcile answers CONFIRMED / SUCCESS. The final read shows `TRANSACTION_KEY` recorded, stock 3 and `createCount(lostPgOrderId) == 1`. There are two lookups in total (one per reconcile) and no unmatched request.
- [x] **After the second context closes, the rest of the class and suite run unaffected.** `OrderPaymentIntegrationTest` ran 33 of 33 with the scene 26th, so seven tests ran after it. The full suite passed (below).
- [x] **`CODING_STANDARDS.md` records the second-context technique.** It is a new sub-bullet under 도우미, next to the bullet for classes that confirm through the PG.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes, with executed test counts checked.** 72 classes, **865 tests, 0 failures, 0 skipped**. `OrderPaymentIntegrationTest` has 33 (was 32).

### Decisions made while implementing

- **The second context overrides four properties, as command-line args.**
  - `SpringApplicationBuilder.properties(...)` sets *default* properties, the lowest-precedence source. The test profile's `ddl-auto: create` in `jpa.yml` and its `pg.base-url` in `application.yml` beat them, and the second context recreated the schema anyway. Command-line args rank above config files.
  - `spring.jpa.hibernate.ddl-auto=none`. **Red check:** without it, the second context runs `drop table … / create table …` against the shared container, and the scene fails with "주문의 결제가 없습니다". A production restart does not recreate the schema either.
  - `pg.base-url` is the first context's test PG, taken from the WireMock server the class already injects (`server.baseUrl()`). wiremock-spring-boot wrote the same address into the first context's `pg.base-url`. Its stubs and request journal stay, so no second server is needed.
  - `server.port=0` and `management.server.port=0`, because the second context starts real Tomcats.
- **No extra wiring for the Testcontainers properties.** The `DynamicPropertyRegistrar` beans in `MySqlTestContainersConfig` and `RedisTestContainersConfig` are applied outside the test framework too, because `spring-boot-testcontainers`' `TestcontainersPropertySourceAutoConfiguration` registers the initializer that calls them. An explicit `DynamicPropertyRegistrarBeanInitializer` source was tried first and proved redundant: the scene passed without it.
- **`LoggingApplicationListener` is left out of the second context.** Logging is one per JVM and the first context owns it. A probe showed that with the listener kept, closing the second context strips Boot's "initialized" marker from the shared logback `LoggerContext` (`null` after the close), so the next test context to start would reset and reconfigure logback. With the listener removed, the marker survives.
- **`TestPg.createCount(pgOrderId)`** counts creates whose body `orderId` matches. With two payments in one scene, the global count cannot show "create 0" for one and "create 1" for the other.
- **The second context starts no payment.** It has neither the first context's `@MockkSpyBean`s nor `@EnableTestPg`'s PG order ID generator, so it only reconciles.
- **Review.** A two-axis review ran before the commit.
  - **Standards:** `PgProperties` was constructor-injected only for setup, against "준비만을 위해 … 주입받지 않는다". The URL now comes from the injected WireMock server. The scene binds its products as locals instead of reading the base class's `product` field. An import in `TestPg` was put back in order.
  - **Spec:** "start sends nothing" was only inferred, and the lost payment's UNKNOWN state before the reconcile was assumed. The test now records `requestCount()` before the restart and asserts the lost payment is UNKNOWN with stock 3 after the confirm and after the restart.
  - **Kept:** the overlap between the `restarted` KDoc and the standards bullet (local practice), and the shared `postRequestedFor(urlEqualTo(PAYMENTS_PATH))` in the two `createCount` overloads.

### For later tickets

- **The payments design doc ticket** should carry the restart scene's test pair. It is scene 8, strengthened from "a new request to the same context" to "a new context over the same DB and PG". The step 2 scene 8 test (`a restart before the create left keeps the payment unknown …`) stays as the same-context replay.
- **If the resilience ticket lands first,** the second context also has the `pg-lookup` breaker. Its registry is per context, so the second context's breaker starts CLOSED, and the scene's two lookups succeed.

## Issue 161: a callback reconciles its payment through the PG's own order port

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/161#issuecomment-6357280134) of [issue 161](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/161). The rules are [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md) and the "알림" section of spec 159. The code is:

- [`PaymentApi`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApi.kt), [`PaymentApiSpec`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApiSpec.kt) and [`PaymentCallbackRequest`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentCallbackRequest.kt)
- [`OrderPaymentReconciler`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/provided/OrderPaymentReconciler.kt) and [`OrderPaymentSaga`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSaga.kt)
- [`OrderFinder`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/provided/OrderFinder.kt) (`findForPg`), [`PaymentFinder`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/provided/PaymentFinder.kt) and [`PaymentRepository`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/required/PaymentRepository.kt) (`findByPgOrderId`)
- [`ErrorType`](../../apps/commerce-api/src/main/kotlin/com/loopers/support/error/ErrorType.kt) (`PAYMENT_NOT_FOUND`, `PG_UNAVAILABLE`)
- [`PaymentApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApiTest.kt) and [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)

### Landed

Two commits pushed to `feat/reconcile` on top of `1ca3ef0` (the restart scene of issue 162):

- [`e243069`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/e243069) adds the endpoint, the payment lookup by PG order ID, the PG's order port with the two error types, their tests, and the dated notes.
- [`f189f52`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/f189f52) appends this section to the writing material.

### Acceptance criteria

- [x] **A new HTTP test class (MockMvc, `NOT_SUPPORTED`, `@EnableTestPg`) covers the answers.** `PaymentApiTest`, 22 tests:
  - A missing, `null`, empty or blank `orderId`, or a body that isn't JSON: 400 `Bad Request`, and the test PG received no request at all.
  - An unknown PG order ID: 404 `PAYMENT_NOT_FOUND`, no lookup, and the other payment's detail is unchanged.
  - An unavailable lookup (a PG 503 or a lost response): 503 `PG_UNAVAILABLE`, with the order detail byte-for-byte unchanged and the stock still reserved. The lookup count is not pinned, because issue 160 owns it.
  - 200 for: found and applied (success, failure); unchanged (lookup says PENDING); no PG transaction; several PG transactions; a transaction with another amount; a finished payment (no lookup).
- [x] **A callback saying `SUCCESS` while the lookup says PENDING leaves the order payment-pending and the reservation held** (stock 8 of 10).
- [x] **A callback whose `transactionKey` differs from the PG's applies the PG's transaction.** The payment ends with the PG's transaction key. A lookup by the body's key would be an unmatched request, and every lookup test asserts `unmatchedRequests()` is empty. A missing, `null` or unknown `transactionKey` or `status`, and an extra unknown field, all get 200 and confirm the order. No request carries `X-USER-ID`.
- [x] **Two identical callbacks confirm once; for FAILED the reservation comes back once** (stock 10, not 12). The second callback meets a finished payment, so the PG receives one lookup, not two.
- [x] **At the port level (`OrderPaymentIntegrationTest`, 33 → 40 tests):**
  - The PG port finishes a pending payment as success (stock 3) and as failure (stock 5), with one lookup and no second create.
  - On a finished payment it sends no lookup, twice in a row.
  - On an unavailable lookup (503 and a stall) it throws `PG_UNAVAILABLE`, while the customer's reconcile of the same order answers the stored PENDING state. Payment and stock are unchanged.
  - Not asked for: a draft order is rejected as `PAYMENT_NOT_STARTED` with no PG request.
- [x] **ADR 0028 rule 6 and ADR 0029 rule 3 get dated notes, keeping the original text.** Two more statements this change touched also got notes:
  - Payments design 17's callback item said `PaymentRepository` has no lookup by PG order ID. Its transaction-key-index item is now closed by ADR 0031 rule 4.
  - ADR 0013's 2026-10-23 note says the customer's `OrderPaymentReconciler` was folded into `OrderConfirmer.reconcile`. ADR 0031 reuses that name for the PG's port, so a new note tells the two apart.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes:** 73 classes, 894 tests (865 + 29), 0 failures, 0 skips, on the final tree. Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `PaymentApiTest` | 22 (new) |
  | `OrderPaymentIntegrationTest` | 40 (33 + 7) |
  | `OrderConfirmationApiTest` | 45 (unchanged) |
  | `HexagonalArchitectureTest` | 7 |

### Decisions made while implementing

- **The request DTO lives in the web adapter.** Every other request is an application `Request` that the controller binds and passes to a port (catalog design 5.17, option B). The callback's ports take a PG order ID and an order ID, not the body, and two of the body's three fields are never read. 5.17's 대가 clause names exactly this case: when the body and the use-case input part ways, put a request DTO back in the web layer. Its KDoc cites the clause.
- **The names.** The spec left the web adapter, DTO and test names as placeholders. They are `PaymentApi`, `PaymentCallbackRequest` (the glossary's English word for 알림 is callback) and `PaymentApiTest`, which follows the HTTP-test naming rule.
- **A user-less order read is a Finder method named for its caller.** `OrderFinder.findForPg(orderId): Order` follows `findForAdmin`, where the qualifier marks the read that skips ownership. It returns the aggregate, not an `OrderInfo`, because the reconcile needs only the status and the payment comes from the payment slice. `OrderRepository.findWithLineItemsById`'s "admin only" KDoc now says admin and the PG's reconcile.
- **The two reconciles share two private steps.** `checkLookupNeeded(order)` holds the status switch: it rejects a draft and skips finished orders. `retrieveTransaction(order)` finds the payment, looks it up, and turns several PG transactions into the existing WARN plus `null`. The bodies differ only where ADR 0031 rule 6 says they should: the customer catches `PgUnavailableException` and answers the re-read stored state, while the PG converts it to `PG_UNAVAILABLE` and does not re-read. The review named the step `needsLookup` a mysterious name, since it throws. It became `checkLookupNeeded`, after Kotlin's throwing `check`.
- **The callback's 503 is the existing advice's.** The port throws `CoreException(PG_UNAVAILABLE)` and `ApiControllerAdvice` maps the `ErrorType`. The adapter catches nothing, as ADR 0031 asked when it rejected "200 and WARN for an unknown PG order ID".
- **The HTTP test confirms over HTTP to prepare.** `CODING_STANDARDS.md` asks preparation-only confirms to call the base `confirmOrder`, but that helper draws a fresh transaction key and does not return the PG order ID, and both go into the callback body and the lookup stub. The class KDoc says why.

### Review notes left as they are

- The adapter finds the payment by PG order ID, and the port then finds it again by order ID. Today that is the same row, because `order_id` is unique. If a retry after failure ever puts several payments on one order (ADR 0029 rule 5), a callback for an old payment would reconcile the current one. ADR 0031 rule 3 fixed the port's input as the order ID.
- `PgTransaction` construction helpers now exist privately in both `PaymentApiTest` and `OrderPaymentIntegrationTest`, with different defaults. They were not moved to `com.loopers.support`.
- `PaymentFinder.findByPgOrderId` trusts its caller for a non-blank ID. Only the web layer validates it, which is the cost 5.17 accepts for a web-only DTO.

### For later tickets

- Scene 6's callback half can now call `OrderPaymentReconciler.reconcile(orderId)` directly in `OrderPaymentIntegrationTest`: callback first, reconcile first, and both at once.
- When issue 160's breaker lands, the port's "open breaker → 503" test goes next to the unavailable test here. The open breaker's rejection already reaches the port as `PgUnavailableException`.

## Issue 160: PG lookups retry once and share a circuit breaker

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/160#issuecomment-6357618649) of [issue 160](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/160). The rules are [ADR 0030](../adr/0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md) and the "PG 조회의 회복" section of spec 159. The code is:

- [`LocalPgGateway`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGateway.kt)
- [`application.yml`](../../apps/commerce-api/src/main/resources/application.yml) (`resilience4j.*`)
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)
- [`TestPg`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPg.kt), [`TestClock`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestClock.kt), [`TestPgLookupBreakerConfig`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPgLookupBreakerConfig.kt)
- [`BaseApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt)

### Landed

Four commits on `feat/reconcile`, on top of issue 161's `9914568`:

- [`fd6c719`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/fd6c719) adds the dependencies, the annotations and the fallback, the `resilience4j.*` settings, the test clock and breaker reset, `losesFirstLookupResponses`, the scene 10 tests, and the 1 → 2 counts.
- [`4976d0f`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/4976d0f) adds the dated notes (ADR 0022 rule 7, ADR 0023 rule 5, payments design 17), the `CODING_STANDARDS.md` entry and this file.
- The review commit after them tidies the scene tests after the two-axis review. The recovery scene takes fixture defaults for values it never asserts, `succeeded(payment)` builds through `createPgTransaction`, a misleading loop index is renamed, `TestClock` drops an unused constructor parameter, and a KDoc that said the base class moves the clock is corrected.
- The last commit lists the breaker-opening setup in CODING_STANDARDS' exceptions to "never prepare through a port", and records the judgement calls kept after the review.

### Acceptance criteria

- [x] **Two reconciles against a dead PG open the breaker.** The PG receives 4 lookups, the breaker is OPEN, and both reconciles answer the stored state (`PAYMENT_PENDING`, `PENDING`). The payment stays pending and the 2 reserved units stay reserved. This runs twice, once with lost lookup responses and once with a 503 list lookup.
- [x] **A third reconcile while OPEN sends 0 lookups** and answers the stored state. The count stays at 4.
- [x] **The OPEN wait is crossed by the test clock.** After 59 s the reconcile sends 0 lookups and the breaker is still OPEN. After 2 s more (61 s), with the PG still dead, that reconcile sends 2 lookups (the two HALF_OPEN probes), and the breaker is OPEN again. The count goes from 4 to 6.
- [x] **Recovery closes the breaker.** Two pending orders, with the breaker opened by the first one's reconciles. Past 60 s, with the PG back, the first order's reconcile confirms it and the breaker is HALF_OPEN. The second order's reconcile confirms that order too, and the breaker is CLOSED. That's 6 lookups and 2 creates in total.
- [x] **Found nothing or several never opens the breaker.** Five reconciles each send exactly 1 lookup (asserted after every reconcile), the fifth still reaches the PG, and the breaker stays CLOSED. This runs once for an empty array and once for two transactions.
- [x] **One unavailable lookup, then an answer, finishes the payment within one reconcile.** The first lookup's response is lost, the retry finds the successful transaction, and the order is confirmed with 2 lookups and 1 create.
- [x] **Neither the breaker nor a timeout ever fails a payment or releases a reservation.** Every scene above reads the order, the payment and the stock in a new transaction afterwards.
- [x] **The gateway bean is an AOP proxy**, asserted in the retry scene, and the create still sends exactly 1 request in every existing scene.
- [x] **The unavailable-path counts go from 1 to 2:** `OrderPaymentIntegrationTest`'s unavailable reconcile (503 and stall) and `OrderConfirmationApiTest`'s 503 reconcile. The found, empty and multiple paths stay at 1. Issue 161's unavailable callback scenes (`PaymentApiTest`, and the PG port's scene in `OrderPaymentIntegrationTest`) assert no lookup count, so nothing there moved. In the integration scene, the PG's and the customer's reconciles now make 4 failed attempts together, which opens the breaker. Both still answer as before.
- [x] **The gateway contract tests pass unchanged:** `PaymentGatewayTest` 96, `ExhaustedPoolPaymentGatewayTest` 1, `ObservedPaymentGatewayTest` 2 and `ConnectTimeoutPaymentGatewayTest` 2 (ran, not skipped), the same counts as before. Their trimmed contexts have no Resilience4j auto-configuration, so the annotations are inert there and the tests still see one HTTP request per call.
- [x] **Dated notes keep the original text:** ADR 0022 rule 7 and ADR 0023 rule 5 point at ADR 0030. Payments design 17's breaker bullet got one as well, since its "조회 불가" baseline of 1 is now 2. `CODING_STANDARDS.md` records the breaker reset, the test clock, and `losesFirstLookupResponses`.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes:** 73 classes, 901 tests, 0 failures, 0 skips, on the final tree (894 on issue 161's `9914568` plus this ticket's 7). Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `OrderPaymentIntegrationTest` | 47 (40 + 7) |
  | `OrderConfirmationApiTest` | 45 |
  | `PaymentApiTest` | 22 (issue 161, unchanged) |
  | `PaymentGatewayTest` | 96 |
  | `HexagonalArchitectureTest` | 7 |
  | `CommerceApiContextTest` | 1 |

### Decisions made while implementing

- **The proxy check alone proves nothing here.** `LocalPgGateway` has `@Validated`, so it was already a CGLIB proxy before any Resilience4j annotation. The red run of the retry scene showed it: `AopUtils.isAopProxy` passed, and the payment stayed pending after 1 lookup. The request count is what catches inert annotations. The test keeps both checks, as ADR 0030 rule 8 asks.
- **Mutation checks.** Deleting both `@CircuitBreaker` annotations turned 4 tests red (both dead-PG cases, the probe and the recovery). Dropping `fallbackMethod` turned 3 red, each with `CallNotPermittedException` escaping the port. The never-opens test passes either way. It guards the failure classification, not the breaker's presence.
- **One fallback serves both lookups.** Both take one `String` and return `PgTransaction?`, and Resilience4j matches a fallback by name, the original parameter types plus one exception, and assignability. The fallback is private. Resilience4j 2.4.0 makes it accessible and invokes it on the target, not the proxy.
- **Retry fallback only.** A fallback whose parameter is `CallNotPermittedException` matches nothing else, so the last attempt's `PgUnavailableException` and `MultiplePgTransactionsException` pass through unchanged (Resilience4j rethrows when no fallback matches).
- **The breaker name is a constant.** `LocalPgGateway.PG_LOOKUP` is used by both annotations, the test config's customizer and the base class's reset. `application.yml` spells it out, because YAML can't reference it.
- **The test clock is a `Clock` subclass, not a bean of some other type.** No production bean injects a `Clock`, so a `TestClock` bean can't make injection ambiguous. It starts at system time and only adds an offset, so the base class resets it with `reset()`.
- **"Fails N times, then answers" uses a WireMock scenario.** `losesFirstLookupResponses(times)` stubs priority-1 faults that advance a scenario state, then stop matching, so the scene's ordinary lookup stubs answer. `wiremock-spring-boot` calls `WireMockServer.resetAll()` before each test, which also resets scenario state.
- **"The PG is back" relies on stub recency.** In the recovery scene the dead PG is `losesLookupResponse()`, and the later `hasTransactions` stubs have the same priority. WireMock answers with the most recently added of equally ranked stubs. The test's KDoc says so.
- **Opening the breaker is a listed exception to "never prepare through a port".** `openPgLookupBreaker()` reconciles twice against a dead test PG, because ADR 0030 rule 8 forbids `transitionTo*`. The review found this outside CODING_STANDARDS' explicit list, so the list now names it next to `LockFailureApiTest`. The other judgement calls from the review were kept as they were: the registry lookup in both the base class and the scene test (the standard lets a test take the dependencies its assertions use), the two-line pending-answer loop in two tests (assertions are written out in order), and `losesFirstLookupResponses(times)` with only `times = 1` in use (the ticket asked for the count).
- **Health and the WARN line are left to issue 164.** This ticket's settings are ADR 0030 rule 6 only. It sets no health properties and registers no event consumer.

### For later tickets

- [Issue 164](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/164) adds `register-health-indicator: true`, `allow-health-indicator-to-fail: false` and the state-transition WARN under the same `pg-lookup` instance. Its "callback answers 503 while OPEN" test can open the breaker with this ticket's `openPgLookupBreaker` shape: lost lookups, then two reconciles.
- [Issue 165](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/165) owes the step 3 section of the payments design. The counts to cite: 2 lookups per unavailable lookup, 0 while OPEN, 2 probes after 60 s.
- Any new full-context test base inherits the reset from `BaseApplicationServiceTest`. Issue 162's second context now has its own `pg-lookup` breaker too. It starts CLOSED because the scene builds a fresh context, so it needs no reset, but a scene that opens a breaker there has to read that context's registry, not the test's.

## Issue 163: callbacks, reconciles and confirms converge in any order

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/163#issuecomment-6357740985) of [issue 163](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/163). The rules are [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md) rule 8 and [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) rule 5, and the scenes are the "순서와 중복" section and 이음매 1's 장면 6 bullets of [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159). This is the callback half of the assignment's scene 6. The code is:

- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the four scenes and the `afterTheCreateIsAnswered` helper
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the callback-first insertion point, in the committing-test bullet under 도우미

### Landed

Two commits pushed to `feat/reconcile` on top of `e48039f` (issue 160's lookup breaker, which landed while this ticket was in progress; the commits were rebased onto it):

- [`8b1ff0e`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/8b1ff0e) adds the scenes and the standards entry. **No production code changes**, as the ticket expected: every TX B locks the order, re-reads the payment, and `Payment.reconcile` only moves forward. No scene showed a change was needed, so the parent spec gets no dated note for production code.
- [`c09a405`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c09a405) appends this section to the writing material.

### Acceptance criteria

Each scene calls the PG's order port, `OrderPaymentReconciler.reconcile(orderId)`, directly. It is what `PaymentApi` calls once it has found the payment by PG order ID. The port returning normally is the callback's 200 (ADR 0031 rule 6); the HTTP mapping itself is `PaymentApiTest`'s, from issue 161. Every scene runs for a SUCCESS and a FAILED answer and asserts `unmatchedRequests()` is empty.

- [x] **Callback first: confirm answers with the confirmed order (its re-read). The order is confirmed once with one `confirmedAt`, stock goes 5 → 3 and stays 3, and create is 1.** `a callback applied before the confirmation's TX B finishes the payment once and the late create answer changes nothing`, 4 rows.
  - The callback runs after the PG accepted confirm's create and before confirm's TX B. It reads the reserved stock 3, then the callback finishes the payment, then it re-reads the order and the stock.
  - Confirm's TX B then gets the create's answer for a finished payment, which is `Unchanged`. Confirm answers the order TX B re-read: CONFIRMED / SUCCESS with the callback's `confirmedAt`, which is also the persisted one. For FAILED it answers PAYMENT_FAILED / FAILED, and the stock goes 5 → 3 → 5 once.
  - One create, one lookup (the callback's). The payment keeps the PG's transaction key.
  - **Beyond the ticket:** two rows have the late create answer say PENDING while the callback's lookup said SUCCESS or FAILED. That is spec 159's "늦은 생성 응답은 결제를 되돌리지 않는다" (user story 17). The payment stays finished.
- [x] **Lookup first: the later callback answers 200 and changes nothing.** `a callback after the customer's reconcile finished the payment sends no lookup and changes nothing`, 2 rows. The customer's reconcile finishes the payment, then the callback returns normally. `confirmedAt`, the order's `updatedAt` and the stock (3 or 5) equal what the reconcile left. The lookup count stays at 1: a finished order is not looked up again.
- [x] **Together: one TX B waits on the other's order lock, and the order is confirmed once. With FAILED, stock goes 5 → 3 → 5 and never reaches 7.** `a callback and the customer's reconcile applying the same answer together change the order and the stock once`, 4 rows.
  - Step 2's scene 6 probe: the first TX B to apply a transaction is paused inside `PaymentModifyService.reconcile` while it holds the order lock. The other side's call has not finished within `LOCK_WAIT_PROBE`, then the first is released.
  - Both sides take a turn holding the lock: the callback holds and the reconcile waits, and the other way round.
  - The customer's answer and the persisted order share one `confirmedAt`. Stock reads 3 after confirm and ends at 3 (SUCCESS) or 5 (FAILED). There are two lookups, because both sides saw a payment-pending order before either TX B ran, and one create.
- [x] **Lost create response, then a callback: the payment ends with the PG's transaction key, and create stays at 1.** `a callback records the PG's transaction key on a payment whose create response was lost, without another create`, 2 rows. Confirm answers UNKNOWN with no transaction key stored. The callback's lookup by PG order ID finds the transaction, and the payment ends SUCCESS or FAILED with `TRANSACTION_KEY`. Stock is 3 or 5, with one create and one lookup.
- [x] **The together scene fails when TX B's order lock read is mutated to a plain read.** `OrderPaymentSagaSteps.applyTransaction`'s `findForUpdate` became `find`.
  - **All 4 rows went red** at the lock-wait probe: the waiting side finished within `LOCK_WAIT_PROBE`.
  - **With the probe assertion also removed, 3 of 4 rows still went red on the end state.** Both FAILED rows ended with stock **7**, because the reservation came back twice. The callback-holds SUCCESS row ended with a second `confirmedAt` overwriting the first. The reconcile-holds SUCCESS row passed: the reconcile was the last writer, so its answer carries the overwriting `confirmedAt`, and the callback returns no order to compare with. In that row the probe is the only guard.
  - After the rebase onto `e48039f`, with the lookups going through issue 160's retry and breaker, the probe result was checked again: 4 of 4 red. Both mutations were reverted.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes:** 73 classes, **913 tests (901 on `e48039f` + 12), 0 failures, 0 skipped**, on the final tree. Executed counts:

  | Class | Tests |
  | --- | --- |
  | `OrderPaymentIntegrationTest` | 59 (47 + 12) |
  | `PaymentApiTest` | 22 (unchanged) |
  | `OrderConfirmationApiTest` | 45 (unchanged) |

### Other mutation checks

Every scene passed on its first run, since no production change was needed. Each was made to fail by a mutation instead, then the mutation was reverted:

- **Callback first:** dropping `status.isFinished ||` from `Payment.reconcile` turned the two late-PENDING rows red (payment `PENDING`, expected `SUCCESS` or `FAILED`). No other test in the class caught it.
- **Lookup first:** making `checkLookupNeeded` look up finished orders turned both rows red on the lookup count (2, expected 1). The state assertions still held, because TX B's `Unchanged` is a second guard behind the status skip.
- **Lost create:** not recording the transaction key in `Payment.reconcile` turned both rows red (`null`, expected `TRANSACTION_KEY`).

### Decisions made while implementing

- **The callback-first scene does not pause the TX B bean.** The ticket suggested "a spy on the TX B bean" as an example. The `OrderPaymentSagaSteps` spy sits inside the CGLIB transactional proxy, so its stub runs inside the TX B the proxy already opened. From there the PG's port, which is `NEVER`, would throw `IllegalTransactionStateException`. Instead, the existing `PgTransactionService` spy runs the callback after `callOriginal()` returns: the PG has accepted the create, and TX B has not started. `PgTransactionService` is `NEVER` too, so no transaction is open there. The scene stays single-threaded and needs no `Pause`. `whilePaymentPending` stays as it is, because it runs before the create goes out. Spec 159's 장면 6 bullet still says "(기존 `whilePaymentPending`)"; the ticket already corrected it.
- **Both sides hold the lock in the together scene.** The ticket asked only that "one TX B waits on the other's order lock". Running both orders covers "in any order", and the mutation shows they fail differently.

### Review notes left as they are

- The together test has the same shape as step 2's two scene 6 tests, with only the racing calls different. It was not merged into one parameterized "two TX Bs overlap" test, so each scene keeps its own name and KDoc, as the file already does.
- `lockHolder` is a `String`, like the `unavailable` parameter of the unavailable-lookup tests.
- The three `PgTransactionService` stub helpers each repeat the `getUltimateTargetObject` lookup.
- Only the `Unchanged` path of "a late create answer is `Unchanged` or a conflict" is exercised. A create answer that contradicts the callback's lookup (SUCCESS against FAILED) would be `RESULT_MISMATCH`, which `PaymentTest` covers at the domain level.
- Two names were changed after review: the callback-first locals became `orderAfterCallback` / `stockAfterCallback`, and the lost-create test's name was reordered so it no longer reads as "lost with the key".

### For later tickets

- **The payments design doc ticket (165)** should pair the callback half of scene 6 with these four tests and record the mutation results above.
- **Issue 160's breaker** landed first, and these scenes were rebased onto it. None of them has an unavailable lookup, so their lookup counts did not move, and the base class resets the breaker before each one.

## Issue 164: operators see the open lookup breaker, and the PG is told to resend

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/164#issuecomment-6358123727) of [issue 164](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/164). The rules are [ADR 0030](../adr/0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md) rule 7 and [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md) rule 6, and the scenes are the "PG 조회의 회복" section and 이음매 1's WARN and health bullets of [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159). The code is:

- [`LocalPgGatewayConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGatewayConfig.kt): the state-transition WARN
- [`application.yml`](../../apps/commerce-api/src/main/resources/application.yml): the health indicator, the contributor switch and the `pg-lookup` health group
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt) and [`PaymentApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApiTest.kt)
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): reading the WARN, the health view and the group, and the callbacks that open the breaker

### Landed

Two commits on `worktree-issue-164`, written on `feat/reconcile`'s `e48039f` (issue 160) and rebased onto `80431bc` (issue 163):

- [`6a7b657`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/6a7b657) is the WARN consumer, the health settings and group, the tests and the standards entries.
- The commit after it appends this section.

### Acceptance criteria

- [x] **Each transition logs exactly one WARN, and a reconcile that changes no state logs none.** One scene walks two pending orders through the whole cycle against a dead PG and then a recovered one. The first dead reconcile (2 samples, under the minimum of 4) and the reconcile while OPEN (no lookup) log nothing. The WARNs are, in order, CLOSED → OPEN, OPEN → HALF_OPEN, HALF_OPEN → OPEN, OPEN → HALF_OPEN and HALF_OPEN → CLOSED, each with `name=pg-lookup`, `from=` and `to=`. `satisfiesExactly` over every WARN on the adapter package's logger is what proves "none" for the quiet reconciles. 8 lookups in all.
- [x] **With the breaker OPEN, overall health is UP and `pg-lookup` reports OPEN.** `HealthEndpoint.health()` is UP. Under the `pg-lookup` group, the `circuitBreakers` component shows `pg-lookup` with status `CIRCUIT_OPEN` and `state: OPEN`.
- [x] **The meter registry has the state gauge.** `resilience4j.circuitbreaker.state{name=pg-lookup,state=open}` is 1.0 and `state=closed` is 0.0.
- [x] **A callback during OPEN answers 503 `PG_UNAVAILABLE` with 0 lookups, leaving the payment and the reservation unchanged; after the wait the same callback answers 200 and finishes the payment.** In `PaymentApiTest`, two callbacks against a PG that loses lookup responses open the breaker (4 lookups). A third answers 503 `PG_UNAVAILABLE` and the count stays at 4. The order detail is byte-for-byte unchanged and the stock stays at 8 of 10. After 61 s on the test clock, with the PG answering SUCCESS, the same callback answers 200. The order is CONFIRMED, there are 5 lookups and 1 create, and the stock is 8.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes:** 73 classes, **905 tests, 0 failures, 0 skipped** (901 on `e48039f` plus 4, before the rebase onto issue 163). Rebased onto `80431bc`, the same command runs 73 classes, **917 tests** (913 plus 4), 0 failures, 0 skipped, with `OrderPaymentIntegrationTest` at 62. Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `OrderPaymentIntegrationTest` | 50 (47 + 3) |
  | `PaymentApiTest` | 23 (22 + 1) |
  | `OrderConfirmationApiTest` | 45 |
  | `PaymentGatewayTest` | 96 |
  | `HexagonalArchitectureTest` | 7 |
  | `CommerceApiContextTest` | 1 |

### Decisions made while implementing

- **The WARN is a `RegistryEventConsumer<CircuitBreaker>` bean in `LocalPgGatewayConfig`.** The Boot module gathers every such bean into the registry's composite consumer, and the registry calls it for each breaker it creates from `resilience4j.circuitbreaker.instances`. The consumer subscribes to `onStateTransition` only for `pg-lookup`, and again on a replaced entry. The log message is Korean like its neighbours, and the KDoc is English.
- **The base class's reset never reaches the captured log.** `CircuitBreaker.reset()` from OPEN publishes an OPEN → CLOSED transition, so the reset in `BaseApplicationServiceTest`'s `@BeforeEach` does log a WARN. JUnit runs the superclass's `@BeforeEach` first, and the test class attaches its `ListAppender` in its own `@BeforeEach`, so the line falls before the capture.
- **Health needs two switches and has one non-default.** `management.health.circuitbreakers.enabled` has no default, so without it the contributor bean is never created and `healthForPath("circuitBreakers")` is `null` (the red run). `register-health-indicator: true` puts `pg-lookup` in it. **Red check:** the health test also passed with `allow-health-indicator-to-fail` unset, so in 2.4.0 the effective default already does not fail. It is set to `false` anyway, as ADR 0030 rule 7 says.
- **Why OPEN does not turn health DOWN.** With failing disallowed, the indicator reports `CIRCUIT_OPEN` (and `CIRCUIT_HALF_OPEN`), not DOWN. Boot's `SimpleStatusAggregator` drops statuses outside its DOWN / OUT_OF_SERVICE / UP / UNKNOWN order. So `circuitBreakers` rolls up to UNKNOWN, and the overall status stays UP.
- **Operators read the state at `/actuator/health/pg-lookup`.** The spec review pointed out that the main endpoint on the management port (8081) shows only `{"status":"UP"}`. `monitoring.yml` sets no `show-components` or `show-details` there, and Boot's default is `never`. The user chose a dedicated group over details on the main endpoint. The group includes only `circuitBreakers`, with `show-details: always`, in commerce-api's `application.yml`, so DB and Redis details stay hidden and `supports/monitoring` is untouched. The `HealthEndpoint` bean shows every component whatever the exposure, so the test also checks the group through `HealthEndpointGroups`: it holds `circuitBreakers`, not `db`, and shows details to an anonymous caller.
- **Metrics needed no change.** `CircuitBreakerMetricsAutoConfiguration` binds `TaggedCircuitBreakerMetricsPublisher` whenever a `MeterRegistry` exists. The gauge assertion is a characterization test.
- **The 503 during OPEN needed no change either.** Issue 160's retry fallback already turns `CallNotPermittedException` into `PgUnavailableException`, and issue 161's port turns that into `PG_UNAVAILABLE`. The tests pin it at both seams. The port-level side by side (the PG's reconcile throws while the customer's answers the stored state, 0 new lookups) was added after the spec review, as the open-breaker case that issue 161's notes left for after the breaker.
- **Two stale deferrals were closed.** Issue 161's unavailable callback and PG-reconcile scenes said the lookup count was left to the retry ticket. Issue 160 had landed, so they now assert 2 and 4.
- **New comments are in English.** The user said mid-ticket that code comments should be English. The new KDoc and `application.yml` comments are written that way. Existing Korean comments were left alone.

### Review notes left as they are

- **Divergent change in `LocalPgGatewayConfig`:** it now builds the HTTP request factory and also hosts the breaker log. The ticket asked for the consumer "next to the gateway's configuration", and the class KDoc says so.
- **The empty `onEntryRemovedEvent`** is forced by the interface.
- **The cumulative WARN lists** in the transition scene repeat the earlier pairs. Each check states the full expected log so far.
- **`Duration.ofSeconds(61)`** now appears in four tests as "the 60 s wait plus one". It was not extracted to a constant.

### For later tickets

- **[Issue 165](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/165)** should carry the operator view in the step 3 design section: the WARN format, `/actuator/health/pg-lookup` and why the overall health stays UP, and the `resilience4j.circuitbreaker.state` gauge. Counts to cite: the transition scene's 8 lookups, and the OPEN callback's 4 → 4 → 5.
- **Issue 163 landed first.** Its scenes and this ticket's touch different parts of `OrderPaymentIntegrationTest` and `CODING_STANDARDS.md`, and the rebase replayed the code commit without a conflict. Only the two appends to this file met, and this section now follows issue 163's.

## Issue 165: the payments design doc for step 3

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/165#issuecomment-6358614295) of [issue 165](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/165), the last step 3 ticket. The rules are the ticket's acceptance criteria and the "문서" bullets of [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159). The work is:

- [`docs/design/payments.md`](../design/payments.md): sections 18–23, and dated notes in sections 1–17
- [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159): insert-only dated notes for the departures

### Landed

One commit on `feat/reconcile`, on top of issue 164's `9119ce7`:

- [`6a24f1e`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/6a24f1e) adds the step 3 sections and the dated notes. **No code changes.**
- The commit after it appends this section.
- Spec 159's body was edited on GitHub. It gained eight dated notes, next to the one issue 163 added.

### Acceptance criteria

- [x] **The step 3 sections carry everything the ticket lists, and every count matches a full `cleanTest check` run with 0 skipped.**
  - **Section 19, the callback:** the contract (19.1) and the answer table (19.2). 19.3 has the order of checks, the two reconciles side by side and the three new ports. 19.4 covers forged callbacks, ordering and duplicates.
  - **Section 20, resilience:**
    - where the annotations go and in what order (20.1), and the failure rules (20.2)
    - the settings and the per-lookup budget (20.3), and the answers when a lookup fails (20.4)
    - logs, health and metrics (20.5), and the test base (20.6)
  - **Section 21, scenes and tests:**
    - scene 6's callback half (21.1), scene 8 in a second context (21.2) and scene 10 (21.3)
    - the PG's reconcile port with the log and health scenes (21.4), and the callback's HTTP contract (21.5)
    - Every row names a real test method and its parameter-row count.
  - **Section 22, limits:** the four the ticket names, plus what step 3 left out (22.5).
  - **Section 23, names and commands:** 23.1 maps the assignment's names to the repo's: `POST /payments/callback`, `providerOrderId`, the authoritative lookup, "확인 불가" and the rest. `paymentAttemptKey` stays as 16.1 has it. 23.2 has the commands.
  - **Counts.** `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check` on `9119ce7` ran 73 classes and **917 tests, 0 failures, 0 skipped**, read from the JUnit XML:

    | Class | Tests |
    | --- | --- |
    | `OrderPaymentIntegrationTest` | 62 |
    | `PaymentApiTest` | 23 |
    | `OrderConfirmationApiTest` | 45 |
    | `PaymentGatewayTest` | 96 |
    | `ExhaustedPoolPaymentGatewayTest` | 1 |
    | `ObservedPaymentGatewayTest` | 2 |
    | `ConnectTimeoutPaymentGatewayTest` | 2 (ran) |

    The other counts in step 2's commands (16.2) are unchanged. A separate `--tests '*PaymentIntegrationTest' --tests '*PaymentApiTest'` run found exactly those two classes, plus `HexagonalArchitectureTest`'s 7 rules.
- [x] **Every §17 item has a dated note, and spec 159 has insert-only dated notes for each departure.**
  - **§17.** Each item's note says where step 3 settled it:
    - the callback: issues 163 and 164 finished it (§19, 20.4)
    - the transaction-key index: closed (19.4)
    - the breaker and the bounded retry: §20 and 21.3
    - the restart: issue 162, with no `wiremock-standalone`
    - stale outcome-unknown payments: unchanged (22.4)
  - **Original text kept.** A script checked that every pre-existing line of the doc survives in order inside its new version. The one exception is the 1.1 status cell (아직 → 끝남). Step 2 changed its own row the same way.
  - **Spec 159.** Eight notes, each appended to an existing line:
    - the placeholder names: `PaymentApi`, `PaymentCallbackRequest`, `PaymentApiTest`, `OrderFinder.findForPg` and the test helpers
    - the test profile's budget: 0.5 s is the common case, and the upper bound is 1.9 s
    - the health contributor switch and the `pg-lookup` group
    - where the WARN consumer lives
    - the second context's four overrides and its two payments
    - the customer's reconcile now sharing two private steps with the PG's
    - where the design doc's step 3 sections are
    - the proxy check, which proves nothing on its own
  - **Edit history.** GitHub's edit history shows lines added and none removed.
  - **Earlier sections.** Statements step 3 made stale got notes too:
    - the gateway's lookup counts (3.3, 5.4, 6.1, 8) and 6.2's restart sentence
    - 2.1's "the callback adds the lookup", 2.5's stored-state rule, 3.2's exception message and 4.2's proxy
    - 9.1, 9.2, 11.1, 11.2 and 13.2
    - 14: the unavailable scene's lookup count, and the 32 → 62 total
    - 15.1, 15.2 and 16.2
- [x] **The doc uses `CONTEXT.md`'s vocabulary.**
  - There is no 콜백 or 웹훅 and no bare "PG 주문", and 슬라이스 always means an application slice.
  - The only 조각 left are step 2's list pages, which is the meaning CODING_STANDARDS keeps for that word.
  - The review also caught 제공자 and 운영자, both on `_Avoid_` lists. The new text no longer uses either, except 제공자 in §23.1's assignment column, which quotes the assignment.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes.** This is the run above. This ticket changes no code.

### Decisions made while implementing

- **Sections 18–23 follow the shape of 9–16.** 18 is the overview and tickets, 19 the callback, 20 resilience, 21 scenes, 22 limits, and 23 names and commands. There is no "for the next step" section, because step 3 is the last. What it left out is in 22.5, next to the ADRs' revisit conditions.
- **The budget table shows two cases.** Spec 159 put the upper bound at 3.5 s in production and 0.5 s in the test profile. But 0.5 s is ADR 0030's common case: the PG accepts the connection and never answers. If both attempts also use up the pool wait and the connect timeout, the test profile's bound is 1.9 s. 20.3 has both rows, and the spec got a note.
- **The doc only claims what a test asserts.** A draft made four claims no test checks:
  - the timeout scene leaves the breaker CLOSED
  - so does the retry scene
  - the second context's breaker starts CLOSED
  - a half-open breaker reports `CIRCUIT_HALF_OPEN`
  
  All four came out. The timeout row now points at the WARN scene. Its first reconcile is what shows that 2 samples don't open the breaker.
- **Two step 2 scenes are recorded again on purpose.**
  - §14 keeps step 2's scene 8 (a replay in the same context). 21.2 is the second context.
  - 21.3 lists step 2's unavailable scene again as scene 10's timeout row, because its lookup count changed. The 62 = 32 + 30 sum says so.
- **Mutation results sit next to their scenes** (21.1, 21.3). They come from the closing comments of issues 160 and 163.

### Review

A two-axis review covered everything since `ca30760`, so all of step 3 against spec 159. It included the doc while it was still uncommitted.

- **Spec.** Doc errors, all fixed before the commit:
  - The callback-first row described its parameter rows wrong.
  - A §17 note cited 20.6 for the warm-up request, but 20.6 didn't mention it. It does now: the warm-up uses a separate JDK `HttpClient` to `/__admin/health`, so it is never a breaker sample.
  - Three claims had no test behind them.
  - The 62 = 32 + 30 sum was wrong.
  - 19.3 put the `PG_UNAVAILABLE` throw and the WARN on `PgTransactionClient.retrieve`. The orchestrator does both.

  The review also found that the customer's reconcile refactor had no spec note. One is added. Last, the HTTP tests don't check a callback whose lookup names another PG order ID, or its WARN. The doc now says those rows rely on the shared code that scene 9 tests.
- **Standards, in the doc.** All fixed:
  - 제공자 and 운영자 came from `_Avoid_` lists.
  - 끝점 was used where the doc says 엔드포인트.
  - Four bullets held only a dated note. They moved into the sentence or table row they amend.
- **Standards, in the code of issues 160–164.** Not fixed here; they are left for the user to decide:
  - `PaymentApiTest` names its only response body `detail` in six tests. CODING_STANDARDS asks for `body`.
  - `PaymentApiTest.preparePayment` confirms over HTTP instead of calling the base `confirmOrder`. That was issue 161's documented choice. The class KDoc explains it, but CODING_STANDARDS' exception list doesn't. `OrderPaymentIntegrationTest` also repeats the accept-and-confirm setup in five tests.
  - `assertStock` is copied between `PaymentApiTest` and `OrderConfirmationApiTest`. A helper that two classes use belongs in `com.loopers.support`.
  - New KDoc and yml comments from issues 160–162, and part of 164, are Korean. They were written before the 2026-10-23 English-comments correction, and existing comments aren't swept without asking.
  - 운영자 and "operators" in CODING_STANDARDS and in comments collide with 관리자's `_Avoid_` list.
  - Kept as judgement calls:
    - The two unread DTO fields stay, because ADR 0031 rule 1 asks for them.
    - `checkLookupNeeded` keeps the name issue 161's review chose.
    - `PaymentApiTest` compares whole bodies as strings. It is a byte-for-byte check by design.

### For later

- Spec 159 can close once this ticket does. Every ticket under it is then done.
- The PR into `week5` should carry section 22's limits and 23.2's commands.

## Spec 159: the closing review

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159#issuecomment-6359789211) of [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159). It closes the spec after a two-axis review of the step 3 branch.

Tickets 160–165 are closed, and the branch had a two-axis review of `origin/week5...f69a6db` (27 commits). It checked the diff against the repo's standards (`CODING_STANDARDS.md`, `CONTEXT.md`, catalog design §5, ADRs 0009–0031) and against this spec, its six tickets, and ADRs 0030/0031. `feat/reconcile` is at [`aac9401`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/aac9401). `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes: 73 classes, 917 tests, 0 failures, 0 skipped. That is the count design §23 records.

### Spec

All 52 user stories were checked. Every ADR 0030 rule 6 value matches `application.yml`, and the test profile still changes only the 200 ms response limit. Nothing in production code goes beyond the spec, and no requirement was found implemented wrongly.

- **Stories 21 and 46: fixed.** No test read the retry's wait, so `wait-duration: 10s` would have passed. The one-unavailable-lookup scene now subscribes to the `pg-lookup` retry's events. It asserts exactly one retry, with a 100 ms wait read from the event, not timed. A temporary `wait-duration: 150ms` made it fail ("expected: 0.1S but was: 0.15S"). [`9902a1a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/9902a1a), with a dated note on the design 21.3 row in [`aac9401`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/aac9401).
- **Story 42: accepted.** `retrieveTransaction(transactionKey)` carries both annotations, but no resilience test calls it. Nothing in production calls it either (ADR 0031 rule 4). On the path that runs, the retry scene's `isAopProxy` and exact request counts already catch inert annotations.
- **Story 19 at the callback level** rests on scene 9's shared code, as issue 165's closing comment records.
- **Test-only commits** `5e7cd01`, `f744fb0`, `5ee477f`, `50996b4` and `f69a6db` came from issue 165's review list and change no behaviour.

### Standards

- **Fixed:** the first response body of a test is named `body` (`CODING_STANDARDS.md` HTTP tests). The open-breaker callback test and step 2's shortage-retry confirm test named it `error`. That finishes the `5e7cd01` sweep. [`07d524d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/07d524d)
- **Kept after review:**
  - **`checkLookupNeeded`:** it is the use case's "ask the PG?" decision, with an application error code; it is not an `Order` invariant.
  - **The two private `pgTransaction` helpers:** each is used in one class only, and their defaults differ.
  - **The breaker's WARN consumer inside `LocalPgGatewayConfig`:** issue 164's dated note chose that placement.
  - **The string scene selectors:** that is the repo's idiom, used at six sites.
- **Dropped (YAGNI): exact matching of the PG order ID.** Under the servers' `utf8mb4_general_ci`, a callback naming a payment's PG order ID with different case or a trailing space finds that payment. It reconciles it and answers 200 rather than 404. The outcome stays honest, because the lookup uses the payment's stored PG order ID. An exact-match fix was built and then removed: the repo follows the database's collation unless there is a definite need.
