# Week 6 order events: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 1 ticket's closing comment. The rules behind it are in [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) and the [order events design](../design/order-events.md). The step is [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167).

## Issue 169: the confirmation instant comes from a Clock bean the tests can pin

Recorded 2026-10-27 from the closing comment drafted for [issue 169](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/169). The rule is rule 8 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) (the confirmation time comes from a clock bean the reproduction can pin), and the decisions are the "시계 (ADR 0032 규칙 8)" section of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167). The code is:

- [`ClockConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/support/time/ClockConfig.kt): the `Clock.systemUTC()` bean
- [`Order`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/Order.kt): `completePayment(confirmedAt)`
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt): `applyResult` passes `clock.instant()`
- [`TestClock`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestClock.kt) and [`TestPgLookupBreakerConfig`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPgLookupBreakerConfig.kt): the breaker's clock as the `@Primary` `Clock`, and `pin`
- [`OrderTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/order/OrderTest.kt) and [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the two seams
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the shared clock and `pin`, under 도우미 next to the breaker's clock

### Landed

Two commits on `worktree-issue-169` (local, not yet pushed or merged into `feat/order-events`), on top of `feat/order-events`' `23a9244`:

- [`3df8325`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/3df8325) is the code, the tests and the standards sentences.
- The commit after it creates this writing-material file. Ticket 168 was to create it, but it had not landed yet.

### Acceptance criteria

- [x] **`Order.completePayment(confirmedAt)` stores exactly the given instant truncated to micros.** `OrderTest` confirms at `2026-10-27T01:02:03.123456789Z` and reads back `2026-10-27T01:02:03.123456Z`.
- [x] **A second `completePayment` is still refused with `OrderStatusConflictException`.** The parameterized test covers DRAFT, CONFIRMED and PAYMENT_FAILED. The CONFIRMED order was confirmed at 01:00 and is retried at 02:00, so an overwrite would show. `confirmedAt` is unchanged.
- [x] **In `OrderPaymentIntegrationTest`, a confirmation made with the test clock advanced by a known duration shows that instant as the order's `confirmedAt` in a fresh transaction.** The new scene pins the `TestClock` at `2026-10-27T01:02:03.123456789Z` and advances it one day. It then confirms through the test PG, and a fresh transaction reads `2026-10-28T01:02:03.123456Z`.
  - **Red check:** while the saga step still passed `Instant.now()`, the first version of the scene failed. The stored value was `2026-10-27T07:44:14.828320Z`, a day before the clock's window.
- [x] **There is exactly one `Clock` bean in the test context and it is the `TestClock` the breaker tests already use.** This one holds with a deviation in wording. The breaker and the confirmation share one `TestClock` instance, and the breaker's wait tests pass with only the field rename (`OrderPaymentIntegrationTest` 63, `PaymentApiTest` 23).
  - The context still holds the main configuration's `Clock.systemUTC()` as a second `Clock` bean, because the ticket also says to expose the `TestClock` "as a `@Primary` `Clock`". `@Primary` only makes sense beside a second bean.
  - Spec 167 frames the goal as "두 시계가 생기지 않게" (so that two clocks don't arise), meaning no second `TestClock`. That is what holds.
  - The new scene proves the sharing: it moves the `TestClock` the breaker scenes inject and sees that instant stored.
- [x] **No remaining `Instant.now()` in `Order.kt`.** `grep -c` gives 0.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes, with executed test counts checked.** 73 classes, **918 tests, 0 failures, 0 skipped**. `feat/order-events` had 917. The one new test is the clock scene, and the changed `OrderTest` test replaced the old one (26 tests either way).
- **"Existing tests that assert `confirmedAt` is 'about now'":** none exist. Every other `confirmedAt` assertion is `isNotNull`, `isNull`, or two answers compared with each other, so all were left as they were.

### Decisions made while implementing

- **The `Clock` bean is in `support.time`.**
  - The other configuration classes in commerce-api each sit next to the adapter they configure. The clock configures nothing, and the application layer reads it.
  - Every layer may use `support` (`HexagonalArchitectureTest`). The shared modules also keep cross-cutting configuration under `config.<concern>`.
- **`@Primary`, not a single bean.**
  - Boot disables bean-definition overriding.
  - `@ConditionalOnMissingBean` on a user configuration depends on registration order, and Boot recommends it only for auto-configuration.
  - `@Primary` resolves the same way in every context. In a context without `@EnableTestPg` the system clock is the only `Clock`, and nothing there confirms through the PG.
  - Spring prefers the primary bean over the parameter-name fallback. So `clock: Clock` in `OrderPaymentSagaSteps` gets the `TestClock` even though the main bean is named `clock`, and the scene shows it.
- **The test clock runs unless a test pins it.** By default `TestClock` is the system clock plus what `advance` added, so two confirmations in one scene get different instants.
  - A clock frozen by default would make the scenes that check "the second application keeps the first `confirmedAt`" pass by construction. These are two reconciles applying the same answer, a reconcile racing confirm's TX B, a callback before TX B, a callback after a reconcile, and a callback racing a reconcile.
  - `pin(instant)` stops the clock, `advance` still moves it from there, and `reset` unpins it. The base class already resets the clock before each test.
  - The scene's first draft only bracketed the confirmation between two reads of the moving clock. The review pointed to the design doc §8 ("확정 시각을 단언하는 테스트는 시계를 고정한다", tests that assert the confirmation time pin the clock) and to spec 167's "`confirmedAt`을 고정해 단언한다" (pin `confirmedAt` and assert it) for tickets 170 and 171. So `pin` was added, and the scene asserts a literal.
- **Truncation stays in the entity.** Spec 167 has the step pass `clock.instant().truncatedTo(MICROS)`. The ticket keeps the truncation in `Order` and has the step pass `clock.instant()`, so the cut happens in one place.
- **`pgLookupClock` became `testClock`** for the bean, `clock` for the constructor parameters of `OrderPaymentIntegrationTest` and `PaymentApiTest`, and `clocks` for the base class's `ObjectProvider`. The clock now also decides the confirmation time, and a confirmation scene reading `pgLookupClock.advance` would mislead.
- **The scene follows the fixture rules.**
  - It accepts the create with `acceptNextCreate`, because it does not assert the transaction key.
  - It leaves the stock to the fixture default.
  - Price 1,000 × quantity 2 stays, because the stubbed amount 2,000 depends on it.

### Left for later

- Ticket 170 can pin the clock and assert `OrderConfirmedEvent.confirmedAt` as a literal. Ticket 171 can do the same for the broker record's `confirmedAt`.
- Ticket 173 (docs) could add to design doc §8 that a PG test context holds two `Clock` beans with the `TestClock` primary, and that `TestClock` gained `pin`.
- `BaseEntity` in `modules:jpa` still stamps `createdAt`, `updatedAt` and `deletedAt` with `Instant.now()`. That is outside this ticket.
