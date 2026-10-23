# Week 5 PG resilience: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 3 ticket's closing comment. The rules behind it are in [ADR 0030](../adr/0030-pg-lookups-retry-once-inside-an-annotated-circuit-breaker.md) and [ADR 0031](../adr/0031-a-callback-is-reconciled-by-a-lookup-of-its-pg-order-id.md), on top of step 2's [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) through [ADR 0029](../adr/0029-pg-order-id-is-minted-per-payment.md). Step 2's material is in [the order payment writing material](./week5-order-payment-writing-material.md).

## Issue 162: a restarted commerce sends no create and finishes pending payments

Recorded 2026-10-23 from the closing comment of [issue 162](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/162). The rules are rules 2 and 3 of [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) (TX A's commit is the send record; create is never resent), and the scene is the "재시작 (장면 8)" section of [spec 159](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/159). The code is:

- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the scene and its `restarted` helper
- [`TestPg`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPg.kt): `createCount(pgOrderId)`
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the second-context technique, under 도우미

### Landed

Two commits on `feat/reconcile`, on top of `c9144f6`:

- [`c34f72a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c34f72a) is the test, the `TestPg` helper and the standards entry. No production code changes.
- The commit that adds this file.

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
