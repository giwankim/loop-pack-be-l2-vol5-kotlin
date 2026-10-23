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
- [x] **The second context's orchestrator and gateway are different instances from the first context's.** `OrderConfirmer` and `PaymentGateway` are compared through `AopTestUtils.getUltimateTargetObject`, so the proxies' targets are compared, not just the proxies. `OrderPaymentOrchestrator` is `OrderConfirmer`'s only implementation.
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

Recorded 2026-10-23 from the drafted closing comment of [issue 161](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/161). The rules are [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md) and the "알림" section of spec 159. The code is:

- [`PaymentApi`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApi.kt), [`PaymentApiSpec`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApiSpec.kt) and [`PaymentCallbackRequest`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentCallbackRequest.kt)
- [`OrderPaymentReconciler`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/provided/OrderPaymentReconciler.kt) and [`OrderPaymentOrchestrator`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentOrchestrator.kt)
- [`OrderFinder`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/provided/OrderFinder.kt) (`findForPg`), [`PaymentFinder`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/provided/PaymentFinder.kt) and [`PaymentRepository`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/required/PaymentRepository.kt) (`findByPgOrderId`)
- [`ErrorType`](../../apps/commerce-api/src/main/kotlin/com/loopers/support/error/ErrorType.kt) (`PAYMENT_NOT_FOUND`, `PG_UNAVAILABLE`)
- [`PaymentApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/payment/PaymentApiTest.kt) and [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)

### Landed

One commit on `worktree-issue-161` (local, not yet pushed or merged into `feat/reconcile`), on top of `feat/reconcile`'s `1ca3ef0`:

- [`e243069`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/e243069) adds the endpoint, the payment lookup by PG order ID, the PG's order port with the two error types, their tests, and the dated notes.

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
