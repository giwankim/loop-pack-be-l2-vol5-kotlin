# Week 5 order payment: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 2 ticket's closing comment. The rules behind it are in [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) through [ADR 0029](../adr/0029-pg-order-id-is-minted-per-payment.md). Step 1's material is in [the PG adapter writing material](./week5-pg-adapter-writing-material.md).

## Issue 148: a PG order ID has at most one PG transaction

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/148#issuecomment-6338583060) of [issue 148](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/148). The decision is the "바꾸는 결정" section of [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md). The code is:

- [`PaymentGateway`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/required/PaymentGateway.kt)
- [`LocalPgGateway`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGateway.kt)
- [`MultiplePgTransactionsException`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/provided/MultiplePgTransactionsException.kt)
- [`Payment`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/payment/Payment.kt)
- [`PaymentGatewayTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/payment/required/PaymentGatewayTest.kt)
- [`PaymentTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/payment/PaymentTest.kt)

### Landed

Three commits on `feat/order-payment` (pushed), on top of `d81f32c`:

- [`82c1381`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/82c1381) moves `PgRejectedException`, `PgOutcomeUnknownException` and `PgUnavailableException` to `application.payment.provided`. Only package lines and imports change.
- [`dcb7c9d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/dcb7c9d) adds `retrieveTransactionByPgOrderId`, `MultiplePgTransactionsException` and `Payment.reconcile(transaction)`, with their tests.
- [`7fecdc1`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/7fecdc1) adds the dated notes.

### Acceptance criteria

- [x] **The gateway contract test covers the lookup by PG order ID.** In `PaymentGatewayTest`:
  - An empty array returns `null`.
  - One transaction is returned as is.
  - Two transactions throw `MultiplePgTransactionsException` after one GET, with the message `PG 주문 번호 하나에 PG 거래가 2개다.`
  - The unavailable cases still throw `PgUnavailableException`: statuses 201, 204, 400, 404, 500 and 503, unreadable bodies, and the response timeout.
  - The query-encoding test passes. It is renamed from "list lookup" to "PG order number lookup", like the other tests of this call.
  - A new body case: a two-element array whose second transaction has an unknown PG status throws `PgUnavailableException`, not `MultiplePgTransactionsException`. A mutation that counted the raw array before reading its elements turned exactly this case red.
- [x] **`PaymentTest` covers every remaining row of the reconcile table with a single transaction.** The class went from 49 to 43 tests. Gone are the empty-list test (four statuses), the two-transaction test, and the `2, …` row of the table-order test. `MULTIPLE_TRANSACTIONS` no longer exists. It survives only in the original text the dated notes keep, and in step 1's writing material.
- [x] **The four PG exceptions live in `application.payment.provided`, and `HexagonalArchitectureTest` passes (7 of 7).** `82c1381` compiles and passes the gateway tests on its own; `PaymentGatewayTest` has 95 there.
- [x] **Dated notes keep the original text.** The ticket asked for ADR 0021 rule 2, ADR 0023 rule 1, and payments design 2.3, 3.2 and 3.3. Four more statements this change made false got notes as well:
  - ADR 0021 rule 3 still named `listTransactions`.
  - Design 2.3's bullet said "0건은 `Unchanged`".
  - Design 2.5 counted six `ConflictReason` values.
  - Design 5.4 said an empty array is an empty list.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes:** 68 classes, 739 tests, 0 failures, 0 skips, on the final tree. Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `PaymentGatewayTest` | 96 (95 − 1 + 2) |
  | `PaymentTest` | 43 |
  | `PaymentRepositoryTest` | 10 |
  | `HexagonalArchitectureTest` | 7 |
  | `ConnectTimeoutPaymentGatewayTest` | 2 (ran, not skipped) |
  | `ObservedPaymentGatewayTest` | 2 |
  | `ExhaustedPoolPaymentGatewayTest` | 1 |

### Decisions made while implementing

- **Check the size before `singleOrNull()`.** `singleOrNull()` returns `null` for an empty list and for a list of two alike. The two-transaction test's red step showed exactly that: nothing was thrown, and two PG transactions read as "not found yet". The adapter checks `size > 1` first.
- **Read every element, then count.** `readPgTransactions()` parses and validates the whole array before the size check. A count that trusted an unreadable element would report a conflict the PG never reliably stated. That case stays `PgUnavailableException`.
- **The exception's shape.** `MultiplePgTransactionsException(count)` carries the count in its message, for the WARN line ADR 0028 rule 3 asks for. It does not carry the transactions: by ADR 0028 rule 5 the order slice moves the payment's values but does not read them. It extends `RuntimeException` directly, with no cause.
- **The wire names stay.** `TestPg.hasTransactions`, `answersList` and `listRequest`, and the adapter's `LIST_PATH`, name the PG's list endpoint, and that endpoint still returns an array. Test names describe the port ("PG order number lookup"), and `unreadableListBodies` became `unreadableArrayBodies`.
- **The conflict still exists, just not in the enum.** `CONTEXT.md` 충돌 still includes two transactions for one PG order ID, and ADR 0028's table logs it as a WARN conflict. The `ConflictReason` KDoc now says the lookup raises that one.
- **The exceptions moved ahead of the port that needs them.** No payment-slice provided port exists yet. The KDoc and notes say the exceptions were placed ahead of it, not that it throws them.
- **The move is its own commit.** The two-axis review suggested reviewing the package move apart from the behaviour change.

### For later tickets

- [Issue 150](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/150) is unblocked.
- The payment slice's provided lookup port (step 2) passes `MultiplePgTransactionsException` and `PgUnavailableException` through as is. The orchestrator catches them by type outside any transaction: a WARN plus the stored state for the first, the stored state for the second (ADR 0028 rule 3).
- `null` from the lookup means: do not call `reconcile`, answer with the stored state.
- `retrieveTransaction(transactionKey)` still has no caller. Step 3's callback decides whether it uses it.

## Issue 149: an order records its payment method and has four states

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/149#issuecomment-6338656608) of [issue 149](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/149). The rules are the 주문 section of [spec 147](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147) and the `CONTEXT.md` entries 결제 중인 주문, 결제에 실패한 주문, 결제수단 and 결제액. The code is:

- [`Order`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/Order.kt)
- [`OrderStatus`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/OrderStatus.kt)
- [`PaymentMethod`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/PaymentMethod.kt)
- [`OrderStatusConflictException`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/OrderStatusConflictException.kt)
- [`OrderResponse`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/order/OrderResponse.kt)
- [`OrderAdminResponse`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/order/OrderAdminResponse.kt)
- [`OrderTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/order/OrderTest.kt)
- [`OrderRepositoryTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/required/OrderRepositoryTest.kt)
- [the step 2 migration](../migrations/2026-10-22-order-payment.sql)

### Landed

Two commits on `feat/order-payment` (pushed), rebased onto issue 148's `4fedc8e`:

- [`c36471c`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c36471c) is the code and tests: the four statuses, `PaymentMethod`, the three transitions and `ensurePaymentPending()`, the computed `paidAmount`, the new CHECK, and `paymentMethod` in both responses.
- [`b40cf98`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/b40cf98) is the docs: [`docs/migrations/2026-10-22-order-payment.sql`](../migrations/2026-10-22-order-payment.sql), the Order section of the points-orders domain doc, and a dated note closing the `paid_amount` hand-off in payments design §8.

### Acceptance criteria

- [x] **`OrderTest` covers the three state changes, each wrong starting state, and the paid amount for every status.** The class went from 6 to 23 tests.
  - `startPayment()` rejects a pending or failed order with `OrderStatusConflictException`. It rejects an order confirmed through the PG or with points with `OrderAlreadyConfirmedException`.
  - `completePayment()` and `failPayment()` each reject a draft, a confirmed order and a failed order.
  - Every rejection leaves the status, payment method and `confirmedAt` as they were.
  - `paidAmount` is null for a draft, a pending order and a failed order. For an order confirmed either way it is the fixed total, 7,000.
- [x] **The repository test shows the database enforcing the new CHECK.** `OrderRepositoryTest` went from 8 to 24 tests.
  - Eleven mismatched rows are rejected with MySQL 3819. These include a pending row with a null or POINT payment method, and a confirmed row without `confirmed_at`.
  - The four valid pairs are accepted and read back through the repository.
  - `information_schema` lists the `orders` columns exactly, so `paid_amount` is gone and `payment_method` is there. `CK_ORDERS_ON_PAYMENT_STATE` is the only CHECK left.
  - The order CHECK cases moved here from `OrderApiTest`, which keeps the line item checks.
- [x] **Over HTTP, the point path and drafts read back as before.** An order confirmed with points reads back from the confirm response and the detail with `paymentMethod: "POINT"` and the same `paidAmount` (7,000). The admin list and detail and the customer list show the same. A draft carries neither field. Jackson's `NON_NULL` policy *omits* nulls, as it already did for `paidAmount`, so the PR should say "omitted".
- [x] **The migration SQL applies to a schema from the previous mapping and leaves confirmed rows as POINT.** Checked on a disposable `mysql:8.4`:
  1. Build a schema from the previous mapping's DDL (Hibernate's `create table` statements from a test run at `d81f32c`).
  2. Insert a draft and a point-confirmed row with `paid_amount = 7000`.
  3. Apply the migration.
  4. Compare with a schema created from the new mapping through `information_schema`: columns with type, nullability and default; CHECK clauses; indexes; keys. **Identical.**

  After the migration, the confirmed row is `CONFIRMED / POINT` with its `confirmed_at` kept and the draft has a null method. The new CHECK rejects a pending row without a payment method and a confirmed row without `confirmed_at`, and accepts `PAYMENT_PENDING / PG`.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree, rebased onto 148: 68 classes, **772 tests, 0 failures, 0 skipped**, which is 148's 739 plus this ticket's 33. The rebase is clean; the two tickets share only `docs/design/payments.md` §8, in different bullets. Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `OrderTest` | 23 (was 6) |
  | `OrderRepositoryTest` | 24 (was 8) |
  | `OrderApiTest` | 53 |
  | `OrderConfirmationApiTest` | 14 |
  | `OrderAdminApiTest` | 11 |

### Decisions made while implementing

- **The migration also widens `status`. The ticket's list didn't have it.** Hibernate maps `@Enumerated(STRING)` to a native MySQL `ENUM`, and the old column was `enum('CONFIRMED','DRAFT')`. Without `ALTER … MODIFY status`, a migrated database cannot store `PAYMENT_PENDING`. Hibernate sorts the values, so the new ones are appended after the existing two and existing rows don't move. `payment_method` is `enum('PG','POINT')` for the same reason.
- **The PG branch of the CHECK also says `payment_method is not null`.** A null makes `payment_method = 'PG'` unknown, and a CHECK lets unknown through. The first version did exactly that: the repository test's pending-without-method row was accepted until the guard went in. A comment on the annotation keeps it from being deleted as redundant, and the migration's CHECK has the same guard.
- **Run the migration over a utf8mb4 connection.** Over the container client's default latin1 connection, MySQL stored the new CHECK's literals as `_latin1'…'`. The JPA schema has `_utf8mb4`, and that was the only schema difference. The script's header says so.
- **The wrong-state exceptions.**
  - A confirmed order keeps `OrderAlreadyConfirmedException`, so `ORDER_ALREADY_CONFIRMED` (409) still means "confirmed", for the point path now and TX A later.
  - A pending or failed order that cannot start payment, or a non-pending order that cannot complete or fail, gets the new `OrderStatusConflictException`. The name follows the `<Subject><State>Exception` pattern; elsewhere here, `Invalid*Exception` means a malformed value.
  - Neither the spec nor the ticket names this exception. It has no HTTP mapping yet, so it would fall back to the generic 400, but nothing in main can reach it.
- **`confirm()` stays and writes POINT.** Its KDoc points to `startPayment()` for PG orders and to ADR 0027 for its removal.
- **Test helpers.**
  - `OrderTest` reaches each state through the real transitions (`orderIn`), never by setting the status.
  - JPA slice tests have no `JdbcTemplate`, so `assertCheckConstraintRejects` gained an `EntityManager` overload.
- **`CODING_STANDARDS.md`** named the dropped `CK_ORDERS_ON_PAID_AMOUNT_POSITIVE` as its single-column CHECK example. It now names `CK_ORDER_LINE_ITEM_ON_QUANTITY_POSITIVE`.

### For later tickets

- [Issue 150](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/150) appends its `payment` statements to the same migration file, under the header's `orders` block. The enum, unknown-in-CHECK and utf8mb4 points above apply to its columns too.
- The tracer ([issue 151](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/151)) calls `startPayment()` in TX A, and `completePayment()` or `failPayment()` in TX B. It decides how `OrderStatusConflictException` maps over HTTP, if it can reach the web boundary at all.
- `paymentStatus` in the responses is still to come, with the payment finder.

## Issue 150: a payment mints its own PG order ID

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/150#issuecomment-6339171998) of [issue 150](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/150). The decisions are [ADR 0029](../adr/0029-pg-order-id-is-minted-per-payment.md) and rule 2 of [ADR 0025](../adr/0025-confirm-requires-an-idempotency-key-and-replays-stored-state.md). The code is:

- [`PgOrderIdGenerator`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/payment/PgOrderIdGenerator.kt)
- [`UuidPgOrderIdGenerator`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/UuidPgOrderIdGenerator.kt)
- [`Payment`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/payment/Payment.kt)
- [`PaymentFixtures`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/payment/PaymentFixtures.kt)
- [`PaymentTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/payment/PaymentTest.kt)
- [`UuidPgOrderIdGeneratorTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/pg/UuidPgOrderIdGeneratorTest.kt)
- [`PaymentRepositoryTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/payment/required/PaymentRepositoryTest.kt)
- [the step 2 migration](../migrations/2026-10-22-order-payment.sql)

### Landed

Three commits on `feat/order-payment` (pushed, `54d2321..3fc7565`), on top of issue 149:

- [`b985e05`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/b985e05) is the code and tests: `PgOrderIdGenerator` in `domain.payment`, `UuidPgOrderIdGenerator` in `adapter.integration.pg`, `Payment.start` with a private constructor, the key rule, the `utf8mb4_bin` key column, `UK_PAYMENT_ON_PG_ORDER_ID`, and fixtures that go through `start`.
- [`9cff4e3`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/9cff4e3) is the docs: the `payment` statements in [`docs/migrations/2026-10-22-order-payment.sql`](../migrations/2026-10-22-order-payment.sql), dated notes in the payments design doc, a dated note on ADR 0025 rule 2, and the fixture rule in `CODING_STANDARDS.md`.
- [`3fc7565`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/3fc7565) is the review follow-up. The two `start` tests became one that asserts every literal it passes, the no-break-space key row is written as ` `, and a fixture variable was renamed.

### Acceptance criteria

- [x] **`PaymentTest`** went from 43 to 51 tests.
  - **`start` takes its PG order ID from the generator and calls it once.** One test passes a counting lambda (`PgOrderIdGenerator` is a `fun interface`). It asserts `UNKNOWN`, the order, the key, the amount, the generator's value, and exactly one call.
  - **Rejected keys:**
    - Blank keys (3 rows) and a 65-character key, as before.
    - Keys containing whitespace (5 rows): inner space, leading space, trailing space, tab, newline.
    - Keys with a character outside visible ASCII (6 rows): Korean, `café`, fullwidth letters, U+00A0, NUL, DEL.
  - **Accepted keys:** one test shows all 94 visible ASCII characters (`!` to `~`, split into two keys) are allowed, so the rule is not stricter than the ADR.
  - **Mutation check:** widening the range past ASCII turned 5 of the 6 outside-ASCII rows red. NUL sits below `!`, so it stays rejected.
  - **Removed:** the derived `LP-ORD-…` tests, including the Thai-locale `padStart` test. No `LP-ORD` is left under `apps/`.
- [x] **The generator's unit test** draws 1,000 values. Each is at least 6 characters and ASCII, and none repeats.
- [x] **`PaymentRepositoryTest`** went from 10 to 14 tests.
  - A second payment with the same PG order ID throws `DataIntegrityViolationException`, and the root cause names `UK_PAYMENT_ON_PG_ORDER_ID`.
  - `Checkout-42` and `checkout-42` are both saved, and each lookup returns its own payment.
  - A lookup by `Checkout-42` finds that payment. `checkout-42` and `CHECKOUT-42` find nothing.
  - `information_schema` shows the key column as `utf8mb4`, `utf8mb4_bin`, length 64, and lists exactly three unique keys. Any case-sensitive collation would pass the behaviour tests, so this pins the one the migration writes.
- [x] **The payments design doc's `pgOrderId` row and `String.format` note** keep their original text and gain dated notes pointing at ADR 0029.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree: 69 classes, **785 tests, 0 failures, 0 skipped**. That is 149's 772 plus this ticket's 13. Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `PaymentTest` | 51 (was 43) |
  | `PaymentRepositoryTest` | 14 (was 10) |
  | `UuidPgOrderIdGeneratorTest` | 1 (new) |
  | `PaymentFixturesTest` | 4 |
  | `PaymentGatewayTest` | 96 |

**The migration.** I checked it on a disposable `mysql:8.4` started with the test container's server flags (`utf8mb4`, `utf8mb4_general_ci`):

1. Build a schema from the previous mapping. I used this ticket's show-sql DDL with its two changes reverted: `idempotency_key varchar(64) not null`, and no `UK_PAYMENT_ON_PG_ORDER_ID`.
2. Seed a payment with `Checkout-42`. Inserting `checkout-42` for another order fails on `UK_PAYMENT_ON_IDEMPOTENCY_KEY`, which is the bug this ticket fixes.
3. Apply the two `payment` statements.
4. Compare with a schema created from the new mapping's DDL in `information_schema`: columns with type, charset, collation and nullability; indexes; constraints. **Identical.**

After the migration, the seeded row is intact, `checkout-42` is accepted, lookups match case exactly, and the new key rejects a duplicate PG order ID.

### Decisions made while implementing

- **`utf8mb4_bin` is PAD SPACE. It tells case apart but not trailing spaces.**
  - On MySQL 8.4, `'abc' = 'abc ' collate utf8mb4_bin` is true. `utf8mb4_0900_bin` is the NO PAD one.
  - The issue says the column separates keys that differ in trailing space, and it does not.
  - The collation stays as specified (the user's call, 2026-10-22). The key rule rejects all whitespace, so no stored key can end in a space.
  - The fact is recorded as a dated note on ADR 0025 rule 2, which keeps its original text.
- **The column uses `columnDefinition`.** The value is `varchar(64) character set utf8mb4 collate utf8mb4_bin`, as the removed `IdempotencyKey.COLUMN_DEFINITION` did. Hibernate still appends `not null`. Hibernate 7.4.1 has `@Collate`, but it is `@Incubating`.
- **`PgOrderIdGenerator` is a `fun interface`, so tests pass a lambda.** `createPayment` passes `{ fixedPgOrderId }`, a UUID string unless the test gives one. No mocks are involved.
- **The key rule.**
  - It is one check with one message, "멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다.", covering whitespace, control characters and anything outside ASCII.
  - It runs after the blank and length checks, so a 65-character key with a space reports the length.
  - The checks stay in `init`, so `start` calls the generator before they run. An invalid key still consumes one UUID, which is harmless.
- **The unique-key test asserts the constraint name**, unlike its sibling tests, because the acceptance criterion names the key.
- **The gateway tests' `LP-ORD-…` example literals are now UUID strings.** They treat the PG order ID as opaque, so only the values changed. The reserved-characters lookup keeps its `+` and `&` (`pg-order+42&orderId=43`).
- **Docs beyond the two required notes.**
  - The payments design doc also notes the `idempotencyKey` row, the creation bullet (`Payment.start` only), and the lookup-index bullet.
  - It closes two hand-offs in §8: the key comparison and the `pg_order_id` index.
  - `CODING_STANDARDS.md` now says that fixtures for a factory-only entity call the factory, and that `Payment.start` gets a fixed generator.

### For later tickets

- **The tracer ([issue 151](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/151)) must reject a bad `Idempotency-Key` header before any lookup.** Under PAD SPACE, `findByIdempotencyKey("Checkout-42 ")` finds `Checkout-42`.
  - The character range is `Payment.IDEMPOTENCY_KEY_CHARACTERS`, which is private for now. Share it with the header check when that check needs it.
  - `IDEMPOTENCY_KEY_MAX_LENGTH` is already public.
- **151 injects `PgOrderIdGenerator` (the `UuidPgOrderIdGenerator` bean) wherever it calls `Payment.start`.** Tests that stub the test PG before confirming need a fixed generator bean (ADR 0029 rule 4). No test covers the bean's wiring yet; its first consumer will.
- **Step 3's callback looks payments up through `UK_PAYMENT_ON_PG_ORDER_ID`.** `PaymentRepository` has no `findByPgOrderId` yet.
- **The migration file now holds both blocks.** The `payment` block follows 149's `orders` block.

## Issue 151: confirming a draft pays through the PG

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/151#issuecomment-6340388224) of [issue 151](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/151), the tracer. The decisions are [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) and [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md), with the header rule from [ADR 0025](../adr/0025-confirm-requires-an-idempotency-key-and-replays-stored-state.md) and the test generator from [ADR 0029](../adr/0029-pg-order-id-is-minted-per-payment.md). The code is:

- [`OrderPaymentService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentService.kt)
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt)
- [`PaymentModifyService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/PaymentModifyService.kt)
- [`PgTransactionService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/PgTransactionService.kt)
- [`IdempotencyKeyArgumentResolver`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/IdempotencyKeyArgumentResolver.kt)
- [`OrderApi`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/order/OrderApi.kt)
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)
- [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt)
- [`TestPgOrderIdGenerator`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPgOrderIdGenerator.kt)

### Landed

Two commits on `feat/order-payment` (pushed, `5ec65f5..94dd580`), on top of issue 150:

- [`c2eb03d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c2eb03d) is the code and tests:
  - the `NEVER` orchestrator `OrderPaymentService`, with TX A and TX B on `OrderPaymentSagaSteps`
  - the payment slice's four provided ports
  - `OrderInfo` and `OrderFinder.findInfo`
  - the `Idempotency-Key` resolver and the 200/202 confirm handler
  - the tests moved onto the test PG
- [`94dd580`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/94dd580) is the docs: ADR 0003 marked superseded by ADR 0024, dated notes on ADRs 0013, 0019, 0021 and 0024, and the test PG convention in `CODING_STANDARDS.md`.

### Acceptance criteria

- [x] **[`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)** is new, with 6 tests. It extends `BaseCommittingApplicationServiceTest` and carries `@EnableTestPg`, so TX A and TX B really commit and the real adapter reaches the test PG over HTTP.
  - **Scene 1:**
    - One create.
    - The order is CONFIRMED, with payment method PG and a `confirmedAt`.
    - Stock goes 5 → 3. It would be 1 if TX B deducted again.
    - The payment is SUCCESS with the stubbed transaction key and the request's idempotency key.
    - The 10,000-point balance is unchanged.
  - **Accepted PENDING:** the order is payment pending, the payment is PENDING with its transaction key, and the stock is 3.
  - **Lost response** (`Fault.EMPTY_RESPONSE`): the payment is UNKNOWN with no transaction key, the order is payment pending, the stock is 3, and there was exactly one create.
  - **Shortage and deleted product:** `InsufficientStockException` and `ORDER_PRODUCT_NOT_AVAILABLE`. The order stays a draft with an unchanged `updatedAt`, there are zero creates, and the stock is unchanged.
  - **Inside a transaction:** `IllegalTransactionStateException`, zero creates, and the order is still a draft with stock 5.
  - **Mutation checks:**
    - I applied two mutations together: dropping the orchestrator's `NEVER`, and completing the order on any `Changed`. Four of the six scenes turned red.
    - The lost-response scene was red before the `PgOutcomeUnknownException` catch was added.
- [x] **[`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt)** is rewritten for the PG. It uses MockMvc, class-level `NOT_SUPPORTED` and `@EnableTestPg`, and runs 27 tests (was 14).
  - **Missing key:** 400 "필수 요청 헤더 'Idempotency-Key'가 누락되었습니다.".
  - **Malformed keys:**
    - Five keys (empty, 65 characters, an inner space, a tab, Korean) each get a 400 naming the rule they break.
    - They get the same 400 against a nonexistent order, which shows the web boundary rejects them before the order is read.
    - Removing the resolver's check turned all five red.
  - **Invalid cards:** four cards (blank type, blank number, a number that fails Luhn, a missing field) each get a 400. The draft, the stock and the zero create count are unchanged.
  - **200:**
    - The body has status CONFIRMED, `paymentMethod: "PG"`, `paymentStatus: "SUCCESS"` and `paidAmount: 7000`.
    - Items and `createdAt` equal the draft's, and GET detail returns the same JSON.
    - A second confirm is 409, and there is still only one create.
  - **202:**
    - Both PENDING and a lost response answer 202 with `Location: /api/v1/orders/{orderId}`, with `paymentStatus` PENDING or UNKNOWN respectively and no `paidAmount`.
    - The detail equals the 202 body.
  - **No secrets:** the 200 body, the 202 body and the detail never contain the transaction key, the PG order ID, or the card number with or without hyphens.
  - **Removed:** the point-confirm and balance scenes.
  - **Moved onto the PG:**
    - Shortage, then replenishment: the same key confirms after the rejected attempt.
    - Saved price: the create stub matches 1,000 won exactly, so a request at the new catalog price would not get its 200.
    - Unavailable product or brand, and unavailable outranking shortage.
    - Requester and ownership checks.
    - Re-confirming after the product and its brand are deleted.
    - Rollback on the last write at commit, which is now TX A's. A temporary CHECK fails the second product's stock UPDATE. The stocks, the payment row and the order are rolled back and no create is sent. The retry confirms with one create.
  - **New:** an old point-paid order, built by SQL, reads back with `paymentMethod: "POINT"` and no `paymentStatus`. Re-confirming it is 409 with no create.
- [x] **`OrderConfirmerConcurrencyTest`** keeps 7 scenes (was 11). The four point scenes are gone.
  - **8 confirmations for 5 units:** 5 confirmed, 3 `InsufficientStockException`, 0 technical errors, `createCount` = 5, and the stock formula holds.
  - **5 confirmations of one draft:** 1 confirmed, 4 `OrderAlreadyConfirmedException`, and one create.
  - **Held order:** the second confirmation waits on the order lock, then is rejected.
  - **Four deletion scenes:** one create when the confirmation held the product, zero when the deletion did.
  - **Pause point:** the paused confirmation now holds the order and product locks just before the payment is created, through a spy on `PaymentModifyService.start`.
- [x] **The API docs** (`OrderApiSpec`):
  - The `Idempotency-Key` header is a documented parameter.
  - The description covers the `{cardType, cardNo}` body, the 400s, the 409s and the 404s.
  - The 200 and 202 responses are listed, the 202 with its `Location` header.
  - The detail operation describes `paymentStatus`.
- [x] **Dated notes, with the original text kept:**
  - ADR 0003: `status: superseded by ADR-0024` and a top note.
  - ADR 0021: rule 4's open choice is settled by ADR 0028, and rule 3's last line becomes "옮기되 읽지 않는다" (carries but never reads).
  - ADR 0019: rule 3 gets the TX A row (order → products by id → revision when sold out flips) and the TX B row (order only, for now). The revisit condition "when payment becomes an external integration" is marked as arrived.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree: 70 classes, **802 tests, 0 failures, 0 skipped**.
  - That is 150's 785, plus 6 for the new integration test, plus 2 fixture contract tests, minus 4 point concurrency scenes, plus 13 in the API test.
  - Executed counts for the changed classes:

  | Class | Tests |
  | --- | --- |
  | `OrderPaymentIntegrationTest` | 6 (new) |
  | `OrderConfirmationApiTest` | 27 (was 14) |
  | `OrderConfirmerConcurrencyTest` | 7 (was 11) |
  | `OrderConfirmerRollbackTest` | 1 |
  | `OrderFixturesTest` | 12 (was 10) |
  | `ProductCacheTest` | 15 |
  | `ProductCacheRollbackTest` | 2 |
  | `BrandRegisterConcurrencyTest` | 11 |
  | `BrandRegisterRollbackTest` | 2 |
  | `LockFailureApiTest` | 3 |
  | `PaymentTest` | 51 |
  | `PaymentRepositoryTest` | 14 |

### Decisions made while implementing

- **Names for the spec's placeholders.**
  - Payment slice ports:
    - `PaymentStarter.start(orderId, idempotencyKey, amount)` joins TX A.
    - `PaymentFinder.findByOrder(orderId)` finds a payment.
    - `PgTransactionCreator.create(payment, card)` runs as `NEVER`.
    - `PaymentReconciler.reconcile(orderId, transaction)` joins TX B.
  - Payment slice services: `PaymentModifyService`, `PaymentQueryService` and `PgTransactionService`.
  - Order slice:
    - `OrderPaymentService` implements `OrderConfirmer` as the `NEVER` orchestrator.
    - `OrderPaymentSagaSteps` holds TX A (`startPayment`) and TX B (`applyTransaction`) and implements no port.
    - `OrderInfo(order, paymentStatus)` is the cross-aggregate result.
    - `OrderFinder.findInfo` sits beside `find`, as `ProductFinder` has both `find` and `findInfo`.
    - `OrderInfoAssembler` reads the payment only when the payment method is PG.
- **`PaymentReconciler` is keyed by order ID**, like `findByOrder`. It re-reads the payment inside TX B after the order lock, so a stale payment from TX A can never be merged back. TX B then reads the payment status for the response through `PaymentFinder`.
- **A PG amount cap, which the ticket did not ask for.**
  - The PG takes 64-bit amounts, and an order can total more. The old point test confirmed 10,737,418,235,000,000,000 won.
  - Without a cap, TX A would commit and `longValueExact()` would throw while building the PG request. That leaves an UNKNOWN payment that was never sent, holding its stock (ADR 0024 rule 1).
  - `PaymentStarter` now refuses amounts above `Long.MAX_VALUE` with a 400 inside TX A, so the reservation rolls back.
  - My first version put the rule on `Payment`. `PaymentRepositoryTest` pins a 65-digit amount round trip, though, and the limit belongs to the PG, so it moved to the slice that builds the PG request.
  - It is recorded as a dated note on ADR 0024 rule 1. Removing the cap turns the API test red.
- **Non-draft orders.**
  - TX A checks `status != DRAFT` and throws `OrderAlreadyConfirmedException` (409 `ORDER_ALREADY_CONFIRMED`).
  - It does not call `ensureDraft()`, which answers a payment-pending order with the unmapped `OrderStatusConflictException` (the generic 400).
  - This is the ticket's "for now".
- **Unknown outcome.**
  - `PgOutcomeUnknownException` returns the state TX A committed (payment pending, payment UNKNOWN) without reading it again.
  - It logs one WARN with the order ID and the failure kind. The ticket did not ask for this log, but nothing else would show such a payment.
  - `PgRejectedException` is not caught.
- **The `Idempotency-Key` header.**
  - An `@IdempotencyKey` argument resolver reads it, as `@RequesterId` works.
  - The resolver calls `Payment.checkIdempotencyKey`, the same check `Payment.start` runs, now extracted from `init`. The rule has one declaration and two enforcement points.
  - A missing header throws `MissingRequestHeaderException`, which `ApiControllerAdvice` now maps to 400. It used to fall through to the generic 500.
  - The resolver runs before the body, so a bad key is rejected before the card is read.
- **`PAYMENT_MISSING`** (500, with the generic code, like `POINT_ACCOUNT_MISSING`) was added because `findByOrder` needs it. Its test still belongs to issue 153.
- **Test PG order IDs.**
  - `@EnableTestPg` imports a `@TestConfiguration` `TestPgOrderIdGenerator`, `@Primary` over `UuidPgOrderIdGenerator`.
  - Tests reserve IDs with the base's `nextPgOrderId()`. The generator hands them out in order from a thread-safe queue.
  - It throws when no ID is reserved. A UUID fallback would silently miss the stub and look like an unknown outcome.
  - The base clears leftover IDs after each test.
  - A payment created in a TX A that then fails still takes an ID, so the rollback scenes reserve two.
- **Other tests that confirmed orders.**
  - `ProductCacheTest`, `BrandRegisterConcurrencyTest` and `BrandRegisterRollbackTest` now use `@EnableTestPg` and the committing base's `confirmOrder(testPg)`, which stubs a successful PG create.
  - The cache test's "upsert is the last statement" scene loses the create response, so only TX A's SQL is captured. The upsert is still last, after `update orders`, `update product` and `insert into payment`.
  - `ProductCacheRollbackTest` and `LockFailureApiTest` fail inside TX A and don't start the test PG.
  - The lock-failure scene now waits on the product row that another transaction holds, instead of the point account.
- **Review.** A two-axis review (standards and spec) ran before the commit.
  - The spec axis found every criterion met and flagged the extras above.
  - The standards axis found six rule breaches, all fixed:
    - two test variable names and one status assertion in the API test
    - the handler locals, now `order` as catalog design 5.33 asks
    - "결제를 요청하다" in the API doc, which `CONTEXT.md` avoids
    - missing dated notes on ADR 0013, and the `@ApplicationService` KDoc, because `OrderPaymentSagaSteps` implements no port
  - Three copies of the accepted-create stub became one base helper, `acceptNextCreate`.

### For later tickets

- **[Issue 152](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/152):**
  - TX B completes the order only on `Changed(to = SUCCESS)`. A FAILED transaction leaves the payment FAILED and the order payment pending.
  - `PgRejectedException` propagates as a 500 and leaves the payment UNKNOWN.
  - `PaymentReconciler` is the place for a `reject` beside `reconcile`.
- **[Issue 153](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/153):** the customer list still maps `OrderResponse.from(order)`, so a PG order in the list has no `paymentStatus` yet. `PAYMENT_MISSING` exists, but no test covers it.
- **[Issue 155](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/155):** replays and key conflicts go where TX A checks "non-draft → `ORDER_ALREADY_CONFIRMED`". The web boundary already rejects malformed keys before any lookup.
- **[Issue 156](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156):**
  - The reconcile entry can reuse `OrderPaymentSagaSteps.applyTransaction`.
  - The unknown-outcome path answers with TX A's snapshot. Once reconcile can change the order between TX A and the response, decide whether that path should read the order again.
- **[Issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157):** carry the PG amount cap into the payments design doc as a deviation.

## Issue 154: the point payment path is deleted

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/154#issuecomment-6340905631) of [issue 154](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/154). The decision is [ADR 0027](../adr/0027-point-payment-path-is-deleted.md), and the lock it changes is in [ADR 0019](../adr/0019-order-and-point-account-rows-locked-on-first-read.md). The code is:

- [`PointModifyService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/point/PointModifyService.kt)
- [`PointAccount`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/point/PointAccount.kt)
- [`Order`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/Order.kt)
- [`ErrorType`](../../apps/commerce-api/src/main/kotlin/com/loopers/support/error/ErrorType.kt)
- [`PointChargerConcurrencyTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/point/provided/PointChargerConcurrencyTest.kt)
- [`OrderTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/order/OrderTest.kt)

### Landed

Two commits on `feat/order-payment` (pushed, `f2723fa..a6f0198`), on top of issue 151:

- [`ff40ee0`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/ff40ee0) is the code and tests:
  - the point payment path deleted
  - the new charge-vs-charge concurrency test
  - `OrderTest` and `PointAccountTest` without the deleted methods
  - KDoc that named point payment, corrected
- [`a6f0198`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/a6f0198) is the docs:
  - dated notes on ADRs 0013 and 0019 and catalog design 5.37
  - the points-orders and catalog domain docs updated
  - the balance identity in `CODING_STANDARDS.md` without confirmed payments

### Acceptance criteria

- [x] **Nothing in main or test refers to the deleted symbols.**
  - Deleted: `PointDeductor`, `PointModifyService.deduct`, `PointAccount.pay`, `InsufficientPointsException`, `ErrorType.INSUFFICIENT_POINTS` with its `ApiControllerAdvice` handler, and `Order.confirm()`.
  - Also deleted: their five tests (three `pay` tests in `PointAccountTest`, two `confirm()` tests in `OrderTest`). Nothing is `@Disabled`.
  - A grep of `apps/` for every deleted name finds nothing. The only `deduct` left is `StockDeductor`, and every `.confirm(` is `OrderConfirmer.confirm`.
  - `INSUFFICIENT_POINTS` is gone from the error contract (`ErrorType`). The API docs (`OrderApiSpec`, `PointApiSpec`) did not list it after issue 151, and nothing generates the OpenAPI error list from `ErrorType`.
  - Charging and the balance lookup are unchanged. `PointModifyService` implements `PointCharger` only.
- [x] **[`PointChargerConcurrencyTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/point/provided/PointChargerConcurrencyTest.kt)** is new, on `BaseCommittingApplicationServiceTest`.
  - **The race:** charges of 2,000 and 3,000 on a 10,000 balance are released together through `runConcurrently`'s start gate.
  - **Outcomes:** `tally` counts 2 successes, 0 rejections and 0 technical errors.
  - **Ordering:** one charge returns 15,000, so the later charge added to the balance the earlier one had committed.
  - **Balance:** re-read in a new transaction, it equals the initial balance plus the successful charges, and the fixed 15,000.
  - **Mutation check:** I made `charge` read the account with `findByUser` instead of `findForUpdate` and ran the test as `@RepeatedTest(20)`. It failed 20 of 20, with the charges returning `[12000, 13000]`: both read 10,000, and the later commit overwrote the earlier charge. With the lock back, 20 of 20 passed.
- [x] **Old point-confirmed orders still read back.**
  - No domain transition reaches a POINT order any more, so these orders exist only as stored rows, and the tests seed them with SQL.
  - [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt), from issue 151, still passes:
    - status CONFIRMED, `paymentMethod: "POINT"`, `paidAmount: 2000` and no `paymentStatus`
    - re-confirming is 409 `ORDER_ALREADY_CONFIRMED`, with no create and unchanged stock
  - `OrderApiTest` and `OrderAdminApiTest` still read POINT rows in the lists. `OrderRepositoryTest` still maps them.
- [x] **ADR 0019's dated notes, with the original text kept:**
  - **Rule 1:** the point account left confirm's lock order, and only charging locks it now. The lock is tested by two racing charges, with the 20-of-20 mutation result.
  - **Rule 4:** the double payment it guarded against is gone. The order is still the first row TX A and TX B lock.
  - **Cost section:** account waits are charge against charge only, and the point race test is deleted.
  - **Revisit condition:** a parenthetical, since only charging is left to pile up on the account.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree: 71 classes, **798 tests, 0 failures, 0 skipped**.
  - That is 151's 802, plus 1 new test, minus 3 `pay` tests, minus 2 `confirm()` tests.
  - Executed counts for the changed or affected classes:

  | Class | Tests |
  | --- | --- |
  | `PointChargerConcurrencyTest` | 1 (new) |
  | `PointAccountTest` | 5 (was 8) |
  | `OrderTest` | 21 (was 23) |
  | `OrderConfirmationApiTest` | 27 |
  | `PointChargerTest` | 6 |
  | `PointAccountFinderTest` | 2 |
  | `PointApiTest` | 12 |

### Decisions made while implementing

- **`InvalidPaymentAmountException` is deleted too.** The ticket did not list it, but `PointAccount.pay` was its only thrower. Keeping it would leave an exception nothing can throw.
- **`OrderTest` builds confirmed orders only through `startPayment` and `completePayment`.**
  - The two tests that compared PG-confirmed and point-confirmed orders now use PG-confirmed orders only.
  - `ensureDraft()` and `paidAmount` look only at the status, so the payment method never changed their answer.
  - A reflection-built POINT order would break the helper's own rule, "상태를 직접 쓰지 않고 그 상태에 이르는 전이를 차례로 부른다". Its KDoc now points at the HTTP test that covers stored POINT rows.
- **The balance is checked two ways.** `CODING_STANDARDS.md` requires the balance identity computed from re-read values, "고정된 숫자와만 견주지 않는다". The test checks initial + successful charges and the fixed 15,000. The standard's formula itself no longer subtracts confirmed payments.
- **Docs beyond ADR 0019, each naming a deleted symbol or a point-paying confirm:**
  - ADR 0013's port list and catalog design 5.37 get dated notes.
  - The points-orders domain doc is a current-state reference, which issue 149 rewrote in place:
    - Its `pay(amount)` and `confirm()` rows are removed, each with a line saying so.
    - The account lock is described as charge-only.
    - The confirm flow keeps its point-era text under a dated note on TX A, the PG call and TX B. Rewriting that flow is the payments write-up's job.
  - The catalog domain doc's stock deduction now joins TX A, not points.
- **KDoc corrected:**
  - `PointAccountFinder` and `PointModifyService`: charging is the only lock taker.
  - `RuleViolationException`: the 409s are stock shortage and already confirmed.
  - the `tally` KDoc
  - `ProductModifyService.deduct`: the revision bump is confirm's last lock.
- **Review.** A two-axis review (standards and spec) ran before the commit.
  - The spec axis found every criterion met. It flagged one wrong citation, fixed: my domain-doc note said TX B locks only the order "per ADR 0028 rule 4", but that rule's table includes the failure case. It now cites ADR 0019's note that TX B locks only the order *today*.
  - It also flagged stale lines in `ProductModifyService` and the two domain docs, all fixed.
  - The standards axis found one breach, fixed: the new test compared only against the fixed balance.

### For later tickets

- **[Issue 155](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/155):**
  - Re-confirming an old POINT order is 409 `ORDER_ALREADY_CONFIRMED` today because of TX A's placeholder ("non-draft → `ORDER_ALREADY_CONFIRMED`"), not `ensureDraft()`. Keep that answer when the placeholder is replaced (spec 147: "포인트로 확정된 주문이면 409 `ORDER_ALREADY_CONFIRMED`다").
  - The points-orders domain doc's `ensureDraft()` row still says the application calls it before touching stock.
- **[Issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157):**
  - `docs/design/points-orders.md` still lists `INSUFFICIENT_POINTS` in its §6 error table and its insufficiency rule, with no dated note yet.
  - The domain doc's confirm-flow note can point at the payments design doc once its step-2 sections exist.

## Issue 153: the order lists and admin views show the payment status

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/153#issuecomment-6341445265) of [issue 153](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/153). The rules are rules 1 and 5 of [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md). The code is:

- [`OrderInfoAssembler`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderInfoAssembler.kt)
- [`OrderQueryService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderQueryService.kt)
- [`PaymentQueryService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/PaymentQueryService.kt)
- [`OrderAdminResponse`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/order/OrderAdminResponse.kt)
- [`OrderApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderApiTest.kt)
- [`OrderAdminApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderAdminApiTest.kt)
- [`OrderFinderTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderFinderTest.kt)

### Landed

Three commits on `feat/order-payment` (pushed, `15fda3b..2cafc0b`), on top of issue 154:

- [`bab9990`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/bab9990) is the code and tests:
  - the customer list, the admin list and the admin detail return `OrderInfo`
  - `PaymentFinder.findAllByOrders` reads a slice's payments in one statement
  - `OrderAdminResponse` gains `paymentStatus`, and the API docs describe both payment fields
  - the HTTP and port tests, and the test support they need
- [`16a9a6a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/16a9a6a) is the ADR note and the coding standards:
  - a dated note on ADR 0028 rule 1: a list fails on a missing payment too
  - `CODING_STANDARDS.md`: where `confirmOrder` lives, and how to count one table's statements
- [`2cafc0b`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/2cafc0b) is a dated note on points-orders design 16.2: both order responses read `OrderInfo` again

### Acceptance criteria

- [x] **A customer list that mixes a draft, a point order, a payment pending PG order and a confirmed PG order shows each order's `paymentMethod` and `paymentStatus`.**
  - [`OrderApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderApiTest.kt) now carries `@EnableTestPg`. Its draft/point list scene became the four-way mix:

    | Order | `status` | `paymentMethod` | `paymentStatus` | `paidAmount` |
    | --- | --- | --- | --- | --- |
    | PG-paid (test PG answers SUCCESS) | CONFIRMED | PG | SUCCESS | 4000 |
    | payment pending (test PG answers PENDING) | PAYMENT_PENDING | PG | PENDING | absent |
    | point-paid (SQL-seeded, no transition reaches POINT since issue 154) | CONFIRMED | POINT | absent | 2000 |
    | draft | DRAFT | absent | absent | absent |

  - Each list entry is also compared, as JSON, with that order's `GET /api/v1/orders/{orderId}`. The list path (`toInfos`) and the detail path (`toInfo`) cannot drift apart.
- [x] **The admin list and detail show the same fields.** [`OrderAdminApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderAdminApiTest.kt) runs the same four orders through `GET /api-admin/v1/orders`. It compares every entry with `GET /api-admin/v1/orders/{orderId}` and checks that `confirmedAt` stays out.
- [x] **A page of several PG orders reads their payments in one query (`captureSql`).**
  - `OrderApiTest` confirms three orders through the test PG, behind a point-paid order and a draft.
  - Page 0 (size 3) holds the three PG-paid orders. It sends exactly one statement that selects from `payment`: `… from payment p1_0 where (p1_0.deleted_at is null) and p1_0.order_id in (?,?,?)`.
  - Page 1 holds the point-paid order and the draft. It sends none.
  - **Mutation check:** I made `toInfos` map each order through `toInfo`. The page sent four payment statements, and the test failed.
  - An older test caught the same rule first. `OrderFinderTest` pins two statements for a slice of drafts. Before an empty-input guard was added, the customer-list version went red at three, because Hibernate still sends a query for an empty `IN ()`. It and its admin-list twin now pin "drafts and point orders read no payment".
- [x] **A PG order whose payment row is missing answers 500 `PAYMENT_MISSING`. The test helper that creates this state names the bypass.**
  - [`BaseApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt)`.startPaymentWithoutPayment()` calls `Order.startPayment()` and saves the order through the repository. It skips the payment TX A would create, and its KDoc says no port can build this state.
  - **HTTP:** 500 with the generic code `Internal Server Error` and the message `ErrorType.PAYMENT_MISSING.message`, as `POINT_ACCOUNT_MISSING` answers. Checked on the customer detail and list, and on the admin list and detail.
  - **Port:** [`OrderFinderTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderFinderTest.kt) asserts `ErrorType.PAYMENT_MISSING` from `findInfo`, both `findAll`s and `findForAdmin`.
- [x] **The API docs list the new fields on the list and admin responses.**
  - `OrderApiSpec.findAll`, `OrderAdminApiSpec.getOrders` and `OrderAdminApiSpec.getOrder` describe `paymentMethod` and `paymentStatus`, and when each is absent. The customer detail already did after issue 151.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree: 71 classes, **802 tests, 0 failures, 0 skipped**.
  - That is issue 154's 798 plus 4 new tests. Two old list scenes were rewritten in place.
  - Executed counts for the changed or affected classes:

  | Class | Tests |
  | --- | --- |
  | `OrderApiTest` | 55 (was 53) |
  | `OrderAdminApiTest` | 12 (was 11) |
  | `OrderFinderTest` | 10 (was 9) |
  | `OrderCreatorTest` | 2 |
  | `OrderConfirmationApiTest` | 27 |
  | `OrderPaymentIntegrationTest` | 6 |

### Decisions made while implementing

- **A list page fails on a missing payment too.** This was decided during the implementation. `findAllByOrders` throws `PAYMENT_MISSING` when any requested order has no payment, as `findByOrder` does. Dropping that order would list it with the PG payment method and no payment status, a shape the API docs rule out, and so hide the inconsistency. The [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md) rule 1 note records it.
- **The empty-input guard sits in `PaymentQueryService`.** It is part of the port's contract ("[orderIds]가 비면 읽지 않는다"), so every caller gets it. The assembler stays branch-free.
- **`findAllByOrders` returns `List<Payment>`.** Finders return whole aggregates (ADR 0014). The assembler reads only `orderId` and `status` from the payments. `orderId` is the order's own key, so ADR 0028 rule 5 ("reads only the payment status") still holds.
- **`findForAdmin` keeps its name** while it now returns `OrderInfo`. The points-orders design table names it, and its KDoc now contrasts it with the customer's `find` and `findInfo`.
- **Test support:**
  - `confirmOrder(testPg)` moved from the committing base up to `BaseApplicationServiceTest`, so `NOT_SUPPORTED` MockMvc classes can call it. It takes `pgStatus` to build a payment pending order.
  - `OrderApiTest` and `OrderAdminApiTest` now carry `@EnableTestPg`. Their annotation set matches `OrderConfirmationApiTest`'s, so the context cache can reuse that context instead of starting another.
- **The per-table count uses `captureSql`, not `withStatistics`.** Hibernate statistics don't split statements by table, so the test filters `captureSql`'s SQL. `CODING_STANDARDS.md` now records this exception to its query-count rule.
- **Review.** A two-axis review (standards and spec) ran before the commit.
  - **Spec:** every criterion met.
    - It noted that the admin list has no `captureSql` check of its own. Kept as is: it runs through the same `toInfos`.
    - It noted that the empty-input guard sits in the payment slice, not in the order slice. Kept, for the reason above.
  - **Standards:**
    - "PG order" collides with the glossary's PG 주문 번호 (`pgOrderId`). The local `pgOrderIds` became `orderIdsWithPayment`, and the test names now say "paying through the PG".
    - PENDING is on the glossary's avoid list for 결제 중인 주문, so the test names say "payment pending order".
    - Two quantities that no test asserts were dropped.
    - The `captureSql` exception was recorded.
    - The PG check became one named rule, `hasPayment`.
- **A dated design note.** Points-orders design 16.2 said ADR 0014 had removed `OrderInfo` and that both responses read `Order`. A 2026-10-22 note now says both read `OrderInfo` again.

### For later tickets

- **[Issue 152](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/152):** a PAYMENT_FAILED order goes through the same assembler, so the lists and admin views show `paymentStatus: FAILED` without new code. No list test covers a failed order yet. The four-way scenes in `OrderApiTest` and `OrderAdminApiTest` are the natural place for a fifth row.
- **[Issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157):** the payments design doc should carry three facts:
  - the list's one-statement payment read
  - the 500 on a missing payment for lists too
  - `confirmOrder`'s new home
- **Flaky timeout.** The test profile's `pg.response-timeout` is 200 ms. At a load average around 14, with other worktrees running Gradle, the first create in a cold JVM timed out once. That left the payment `UNKNOWN` instead of `PENDING`, and it passed on rerun. Every PG scene test is exposed the same way. A warm-up or a longer limit for non-timeout scenes would fix it. No issue is filed.

## Issue 152: a failed payment returns the reservation once

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/152#issuecomment-6341594995) of [issue 152](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/152). The rules are [ADR 0026](../adr/0026-stock-reservation-is-the-pending-orders-line-items.md), rules 3 and 4 of [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md), and rules 1 and 3 of [ADR 0020](../adr/0020-product-list-cache-keyed-by-a-db-revision-locked-last.md). The code is:

- [`OrderPaymentService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentService.kt)
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt)
- [`PaymentModifyService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/PaymentModifyService.kt)
- [`StockRestorer`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/product/provided/StockRestorer.kt) and its implementation in [`ProductModifyService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/product/ProductModifyService.kt)
- [`Product.restoreStock`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/product/Product.kt)
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)

### Landed

Three commits on `feat/order-payment` (pushed, `595dfc5..4ce2f98`), on top of issues 154 and 153:

- [`1b67ce0`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/1b67ce0) is the code and tests:
  - The orchestrator catches `PgRejectedException` outside any transaction and hands it to TX B's new rejection entry, `OrderPaymentSagaSteps.applyRejection`. An accepted create whose transaction says FAILED takes the same path through `applyTransaction`.
  - Both entries share one rule. The order changes only on `Changed`: `completePayment()` on SUCCESS, and `failPayment()` plus the return of stock on FAILED.
  - `PaymentReconciler.reject(orderId, rejection)` applies `Payment.reject` inside TX B and logs a WARN, without card data, on `Conflicted`.
  - The new product port `StockRestorer.restore(quantities)` locks products by id, skips missing or deleted ones, and adds the units back. It bumps the list revision only when a sold-out product becomes buyable.
- [`db5f70a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/db5f70a) holds the dated notes:
  - ADR 0020 rule 1 and the rule 3 table
  - ADR 0019 rule 3
  - catalog design 8.7
  - `CODING_STANDARDS.md` for the new test spy
- [`4ce2f98`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/4ce2f98) holds the review fixes: a test for a conflicting rejection, and the test hook's name.

### Acceptance criteria

- [x] **Scene 3 (rejected before create).** [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): one create; the payment is FAILED with a null transaction key; the order is `PAYMENT_FAILED` with no `confirmedAt` and no `paidAmount`.
  - Stock goes 5 → 3 → 5. The 3 is read during the PG call, between TX A's commit and TX B, so the test proves the reservation happened rather than inferring it.
- [x] **An accepted create whose transaction is FAILED** ends the same way, and the payment keeps its transaction key.
- [x] **A product deleted while the payment was pending stays deleted.**
  - The delete commits during the PG call.
  - After the failure, the kept product is back at 5.
  - The deleted product still has its deletion time and its reserved stock of 3, both read by SQL since no port can see a deleted product.
- [x] **Admin stock: 5 → reserve 2 → 3 → the admin sets 10 → 10 → the payment fails → 12.** The two middle values are read during the PG call.
- [x] **A product sold out by its reservation is buyable in the customer list after the failure.**
  - The test reads the list during the PG call. That caches the sold-out page under the revision TX A raised.
  - After the failure, the list shows the product buyable and the revision is `before + 2`.
  - **Mutation check:** I commented out `markChanged()` in `restore`. The final list read served the cached `soldOut = true`, and the test failed.
  - [`ProductCacheTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/product/provided/ProductCacheTest.kt) pins TX B's upsert as its last statement, after the `orders`, `payment` and `product` UPDATEs.
- [x] **The confirmation API returns the 200 payment-failed body.** [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt):
  - status 200, no `Location`, `status: PAYMENT_FAILED`, `paymentStatus: FAILED`, no `paidAmount`
  - the detail returns the same body, and the stock is back
  - the no-leak test gained a FAILED case
- [x] **ADR 0020 rule 1 ("여덟 쓰기") and its rule 3 table have dated notes**, with the original text kept. The rule 3 note also replaces the stale "주문 확정" row, which still listed the point account, with ADR 0028 rule 4's TX A and TX B rows.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree: 71 classes, **817 tests, 0 failures, 0 skipped**. That is issue 153's 802 plus 15 new tests.

  | Class | Tests |
  | --- | --- |
  | `ProductTest` | 34 (was 28) |
  | `OrderPaymentIntegrationTest` | 12 (was 6) |
  | `ProductCacheTest` | 16 (was 15) |
  | `OrderConfirmationApiTest` | 29 (was 27) |
  | `OrderConfirmerConcurrencyTest` | 7 |

### Decisions made while implementing

- **The window between TX A and TX B is the PG call.** `OrderPaymentIntegrationTest` spies `PgTransactionService`. `whilePaymentPending { … }` runs a write or read just before the create goes out. It runs on the same thread with no `Pause`: the PG call holds no transaction, so the action's ports commit on their own. `CODING_STANDARDS.md` records the spy.
- **The return of stock reuses the deduction's `lockAll`.** Products lock in id order, and the deleted-product filter turns a deleted product into a skip rather than a rejection. `deduct` still rejects.
- **`Product.restoreStock` uses `Math.addExact`.** A return past `Int.MAX_VALUE` fails, and TX B rolls back with the order still pending, instead of wrapping to negative stock.
- **Once only needs no new guard.** Stock comes back only on `Changed(to = FAILED)`, under the order lock. The end-to-end repeat needs the reconcile API.
- **Three tests passed against existing code**: the FAILED transaction, the deleted product and the sold-out flip. Each was mutation-checked on the line it guards.
- **Review.** A two-axis review ran before the push.
  - **Spec:** every criterion met. It noted that a conflicting rejection had no end-to-end test. One now exists: a 422 rejection echoing another amount leaves the payment UNKNOWN, the order payment pending and the stock reserved. A mutation that fails the order on `Conflicted` makes it fail.
  - **Standards:** no violations. `whilePaymentPending`'s parameter became `action`, since two callers only read. The `restore`/`deduct` and `reject`/`reconcile` shapes stay duplicated: two call sites each, with real differences.
- **Rebased twice** while issues 154 and 153 landed.
  - `ProductModifyService.deduct`'s KDoc line conflicted, because both tickets fixed the same stale "포인트 계정 잠금" wording. 154's version was kept.
  - `CODING_STANDARDS.md` conflicted with 153's `confirmOrder` sentence. Both edits were kept.

### For later tickets

- **[Issue 156](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156):**
  - The repeated-failure test needs reconcile ("a repeated failure answer returns nothing" is pinned only at the domain level for now).
  - Scene 8 can reuse the `PgTransactionService` spy field in `OrderPaymentIntegrationTest`.
- **[Issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157):** the payments design doc should carry:
  - TX B's failure lock order: order → products by id → revision when sold out flips
  - the ninth list-changing write
  - the `addExact` overflow behaviour
- **The lists don't show a failed order yet.** Issue 153 noted that a `PAYMENT_FAILED` order already shows `paymentStatus: FAILED` in the lists through the shared assembler, but no list test has that row. That was not in this ticket's criteria and was left out.
- **Flaky create timeout.** `OrderConfirmationApiTest` "[1] newPrice = 500" got 202 once in a full run.
  - It was the first test in a fresh context. The stub matched and WireMock answered 202, but the test profile's 200 ms `pg.response-timeout` had already fired (`SocketTimeoutException`), so the create was outcome unknown.
  - It passed alone and on rerun. Issue 153 saw the same thing.
  - Diagnosed and fixed after the close; see the follow-up below.

### Follow-up: the flaky create timeout

Fixed in [`fda7f0b`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/fda7f0b) (pushed, `d5cd282..fda7f0b`).

- **Cause.** The test JVM's first HTTP exchange runs Jetty's and WireMock's server code cold. `-Xshare:off` and the JaCoCo agent make it slower still. Under CPU load, that server-side work passes the test profile's 200 ms `pg.response-timeout`. So a create that WireMock answered 202 surfaces as `SocketTimeoutException`, the payment stays UNKNOWN, and the confirmation answers 202 instead of 200.
- **Only the JVM's first exchange is exposed.**
  - Later fresh contexts, with fresh WireMock servers, answered their first create in 87–196 ms even under load. Warm exchanges took milliseconds.
  - Gradle runs test classes in package order, so the exposed test is whichever `@EnableTestPg` class comes first. Before issue 153 that was `OrderConfirmationApiTest`, and "[1] newPrice = 500" was its first method. Since issue 153 it is `OrderAdminApiTest`.
- **Evidence.** A throwaway harness ran the first create of a fresh JVM against the gateway's minimal context, with 20 busy threads to stand in for a load average of ~14. Each run took about 25 s.

  | | Runs | Timed out |
  | --- | --- | --- |
  | No warm-up | 20 | 9 |
  | With the fix | 14 | 0 |
  | Fix removed again, same load | 8 | 4 |

- **Fix.** `@EnableTestPg` now also imports `TestPgWarmUpConfig`. When each context refreshes, it sends one GET to the test PG's `/__admin/health` with the JDK `HttpClient`, reading the address from `PgProperties`.
  - WireMock does not journal admin requests, so `createCount()` and `unmatchedRequests()` are unchanged.
  - The adapter's HTTP client, its connection pool and its observations are untouched. `ExhaustedPoolPaymentGatewayTest` and `ObservedPaymentGatewayTest` still pass.
  - A failing warm-up fails the context.
  - The 200 ms limit stays, so the timeout scenes stay fast.
- **No committed regression test.** The bug needs the JVM's first PG exchange under CPU load, and no test can pin that it runs first. `CODING_STANDARDS.md` records the warm-up next to `@EnableTestPg`. The harness was deleted.
- **Full `ktlintCheck check`** with the fix: 71 classes, 817 tests, 0 failures, 0 skipped.

## Issue 155: a same-key confirmation replays and a wrong key is rejected

Recorded 2026-10-22 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/155#issuecomment-6343745417) of [issue 155](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/155). The contract is [ADR 0025](../adr/0025-confirm-requires-an-idempotency-key-and-replays-stored-state.md), the check order is in [spec 147](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147)'s "주문 슬라이스의 조율", and rule 5 of [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md) decides who compares the key. The code is:

- [`OrderPaymentService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentService.kt)
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt)
- [`PaymentValidator`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/provided/PaymentValidator.kt) and its implementation [`PaymentValidationService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/PaymentValidationService.kt)
- [`Payment.isStartedWith`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/payment/Payment.kt)
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt) and [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt)

### Landed

Two commits on `feat/order-payment` (pushed, `7071838..773f9eb`):

- [`8d02f60`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/8d02f60) is the code and tests:
  - TX A (`OrderPaymentSagaSteps.startPayment`) checks in ADR 0025's order:
    1. It locks the order. A missing order, or someone else's, is 404, even with the owner's key.
    2. A point-confirmed order is 409 `ORDER_ALREADY_CONFIRMED`.
    3. An order that has a payment replays on the key that started it. Any other key is 409 `IDEMPOTENCY_KEY_MISMATCH`.
    4. A draft whose key another payment uses is 409 `IDEMPOTENCY_KEY_IN_USE`.
    5. It reserves stock.
    6. It starts the payment.

    This replaces the "non-draft is already confirmed" placeholder.
  - A replay changes nothing and hands the orchestrator no payment to send. The orchestrator returns the stored order and payment status with neither a create nor a lookup at the PG. The controller already answers 202 with `Location` for a payment pending order and 200 otherwise.
  - The new payment port `PaymentValidator.validateForStart(idempotencyKey)`, implemented by the read-only `PaymentValidationService`, runs at step 4.
  - `Payment.isStartedWith(key)` decides replay against mismatch, with an exact comparison. The order slice still reads nothing of a payment but its status (ADR 0028 rule 5).
  - When two drafts race with one key and both pass the pre-check, the loser's insert hits `UK_PAYMENT_ON_IDEMPOTENCY_KEY`, and its TX A rolls back with the reservation. The orchestrator catches `DataIntegrityViolationException` outside the transaction and asks the validator again. The validator now sees the winner's payment and answers `IDEMPOTENCY_KEY_IN_USE`. Any other violation is rethrown.
  - The new error types `IDEMPOTENCY_KEY_MISMATCH` and `IDEMPOTENCY_KEY_IN_USE` are both 409. The confirm API description lists the replay and the three 409s in check order.
- [`773f9eb`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/773f9eb) holds the notes:
  - ADR 0005's status and a dated note
  - ADR 0019's dated note
  - the order domain doc's dated note on the confirm flow
  - `CODING_STANDARDS.md`: `tally` without a rejection type, and the race spy

### Acceptance criteria

- [x] **Same key after a 202 is 202 while pending and 200 once finished, with zero extra creates and lookups. The same key after a failure returns the 200 payment-failed body.**
  - [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt) replays during the first confirmation's PG call and gets `PAYMENT_PENDING` / `UNKNOWN`. A replay after it gets `CONFIRMED` / `SUCCESS`. There is one create, zero lookups, and stock goes 5 → 3 once.
  - [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt):
    - A pending replay answers 202 with `Location` and the first body, even though it carries another card.
    - Finished replays answer 200 without `Location` and with the first body, for both SUCCESS and FAILED.
    - Every case has one create, zero lookups, and no second reservation or second return.
  - Over HTTP, a 202 followed by a 200 after the payment finishes needs the reconcile API. That belongs to issue 156.
- [x] **A different key on an order that has a payment is 409 `IDEMPOTENCY_KEY_MISMATCH`, and nothing changes.**
  - The API test runs over SUCCESS, PENDING and FAILED. The detail, the create count, the lookup count and the stock are all unchanged.
  - The port test checks the same with a pending payment.
- [x] **Another order's key, even another user's, is 409 `IDEMPOTENCY_KEY_IN_USE`, and nothing is reserved.**
  - The API test runs with draft stock 10 and with draft stock 1. The short case proves the check comes before the reservation.
  - **Mutation check:** with the pre-check moved after the reservation, the short case answered `INSUFFICIENT_STOCK`.
  - The port test checks that the draft and its stock are unchanged.
- [x] **Scene 5: concurrent confirms of one order with one key make one payment, one reservation and one create, and the others replay.**
  - Five confirms run with `runConcurrently`, giving 5 successes, 0 rejections and 0 technical errors.
  - There is one create and zero lookups. The final stock is 10 − 2, computed from the re-read line items.
  - Each answer is either pending/unknown or confirmed/success.
- [x] **Two drafts racing with one key make one payment. The loser gets 409 `IDEMPOTENCY_KEY_IN_USE`, and its stock is untouched.**
  - The loser is paused between the pre-check and its insert. Meanwhile, the winner confirms another product.
  - The loser's draft keeps its `updatedAt` and its stock of 5, and the winner's payment holds the key.
  - **Before the mapping existed,** the loser got a raw `DataIntegrityViolationException` naming `payment.UK_PAYMENT_ON_IDEMPOTENCY_KEY`.
- [x] **After a shortage rejection, the same key confirms the draft once stock is replenished.** The existing test `a later item shortage rolls back every reservation and sends nothing, and the same key confirms after replenishment` covers this.
- [x] **Keys that differ only in case are different keys.**
  - In the port test, `Checkout-42` and `checkout-42` each confirm an order, with two creates.
  - **Mutation check:** the key column under `utf8mb4_0900_ai_ci` fails that test.
  - `PaymentTest` pins the exact comparison of `isStartedWith`.
- [x] **Notes, keeping the original text.**
  - ADR 0005's status reads `accepted; superseded by ADR-0025 for confirm`. Its dated note also says the unique-key recovery its "대가" called gone is back for confirm.
  - ADR 0019 has a dated note after "새 요청 키를 들이지 않는다". It also covers rule 2's `ORDER_ALREADY_CONFIRMED` bullet, which now holds only for point-confirmed orders.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes** on the pushed tree: 71 classes, **835 tests, 0 failures, 0 skipped**. That is issue 152's 817 plus 18 new tests.

  | Class | Tests |
  | --- | --- |
  | `OrderPaymentIntegrationTest` | 18 (was 12) |
  | `OrderConfirmationApiTest` | 37 (was 29) |
  | `PaymentTest` | 55 (was 51) |
  | `OrderConfirmerConcurrencyTest` | 7 |

### Decisions made while implementing

- **The key-in-use check is its own port, not part of making the payment.** The spec's port list put "미리 찾아보고" inside the port that makes the payment. But step 4 has to come before the reservation (step 5), and the payment is made at step 6. `PaymentValidator` follows the `BrandValidator` precedent: a precondition that only a repository can answer. `PaymentStarter`'s KDoc says callers validate first and that a lost race surfaces as the unique-key violation. Spec 147 has a dated note.
- **Comparing the key is the payment's question.** The first cut compared `payment.idempotencyKey` in the order slice. Both review axes flagged that against ADR 0028 rule 5, so `Payment.isStartedWith(key)` answers it.
- **The race is mapped by asking again, not by parsing the constraint name.** Hibernate does expose `payment.UK_PAYMENT_ON_IDEMPOTENCY_KEY`. Using it would tie the order slice to Hibernate's exception type and MySQL's message format.
  - MySQL reports the duplicate only after the winner commits. So a fresh read outside the rolled-back TX A sees the winner's payment.
  - If another constraint fails while the key happens to be taken, the answer is also `IDEMPOTENCY_KEY_IN_USE`. That answer is true: the key is taken. The KDoc says so.
  - The temporary-CHECK test in `OrderConfirmationApiTest` still gets 500.
- **A replay is a null payment in `StartedPayment`.** The orchestrator reads `started.payment ?: return started.info`. There is no sealed result type, in line with the earlier choice against `PgAnswer`.
- **The point check reads the payment method, not `ensureDraft()`.** `ensureDraft()` runs inside `Order.startPayment()`, which comes after the reservation. A point order with a deleted product would then answer `ORDER_PRODUCT_NOT_AVAILABLE`.
- **Existing tests changed meaning.**
  - Re-confirming an order paid through the PG with a fresh key is now a key mismatch, so `OrderConfirmationApiTest`'s re-confirm assertions use `assertKeyMismatch`. The product-and-brand-deleted test now shows a replay with the original key and a mismatch with another key, both before any product read.
  - The ownership test sends the owner's key, so 404 is shown to come before a replay.
  - `OrderConfirmerConcurrencyTest`'s two same-order scenes use different keys and now expect `IDEMPOTENCY_KEY_MISMATCH`, asserting the `errorType` of each counted rejection.
- **The confirm API description also gained the first-request failure path** (200 with a payment-failed order and the stock returned). Issue 152 had left it out, and the 200 response now covers it.
- **Review.** A two-axis review ran before the commit.
  - **Spec:** every criterion met. It flagged the rule 5 read and asked for scene 5's mismatch and in-use cases in `OrderPaymentIntegrationTest`, so that `--tests '*PaymentIntegrationTest'` runs them. Both are fixed.
  - **Standards:** three findings, all fixed: an `MvcTestResult` named `detail`, a second `assertThrows` result not named `exception`, and a `Location` read that bypassed MockMvcTester. As part of that, the replay API test split into a pending test and a finished test. The orchestrator's private wrapper became `startPaymentMappingKeyRace`.
  - **Kept:** the null-as-replay flag, and `findByIdempotencyKey(...) != null` over a new `existsBy` method.

### For later tickets

- **[Issue 156](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156):**
  - Add the real replay transition over HTTP: a 202, the payment finished by reconcile, then a 200 replay with the same key.
  - Scene 8's restart replays with the same key. The replay path neither creates nor looks up.
  - `OrderPaymentIntegrationTest` now has two spies (`PgTransactionService`, `PaymentModifyService`) and `pauseBeforeStartingPaymentOf(order)`.
- **[Issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157):** the payments design doc should carry:
  - TX A's six checks
  - the `PaymentValidator` port and `Payment.isStartedWith`
  - the race mapping by re-check, with its one accepted misattribution
  - the replay contract table, with "the card is not compared"

## Issue 156: an unfinished payment is reconciled with the PG

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156#issuecomment-6344945061) of [issue 156](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156). The rules are rules 4 and 5 of [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) and rule 3 of [ADR 0028](../adr/0028-order-slice-orchestrates-the-payment.md), and the scenes are the reconcile halves of [spec 147](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147)'s integration-test list. The code is:

- [`OrderPaymentReconciler`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/provided/OrderPaymentReconciler.kt) and its implementation [`OrderPaymentService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentService.kt)
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt), whose TX B the reconcile reuses
- [`PgTransactionRetriever`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/provided/PgTransactionRetriever.kt) and its implementation [`PgTransactionService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/PgTransactionService.kt)
- [`OrderApi`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/order/OrderApi.kt) and [`OrderApiSpec`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/webapi/v1/order/OrderApiSpec.kt)
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt), [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt) and [`ProductCacheTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/product/provided/ProductCacheTest.kt)

### Landed

Two commits on `feat/order-payment` (pushed, `a96f2d9..59a6551`):

- [`6aab164`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/6aab164) is the code and tests:
  - The new order port [`OrderPaymentReconciler`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/provided/OrderPaymentReconciler.kt)`.reconcile(userId, orderId)` serves `POST /api/v1/orders/{orderId}/payment/reconcile`, with no body. [`OrderPaymentService`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentService.kt) implements it next to `OrderConfirmer`, so it also runs `NEVER`, outside any transaction.
  - It reads the order without a lock and checks in this order:
    1. A missing order, or someone else's, is 404.
    2. A draft is 409 `PAYMENT_NOT_STARTED`.
    3. A point-confirmed order and a finished PG order answer their stored state without calling the PG.
    4. A payment pending order is looked up.
  - The new payment port [`PgTransactionRetriever`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/provided/PgTransactionRetriever.kt)`.retrieve(payment)`, implemented by `PgTransactionService` (`NEVER`), looks the transaction up by the payment's PG order ID. It rethrows `PgUnavailableException` and `MultiplePgTransactionsException`.
  - A found transaction goes through `OrderPaymentSagaSteps.applyTransaction`, the confirmation's TX B, unchanged. Success confirms the order. Failure fails it and returns the stock once.
  - `null` and `PgUnavailableException` answer the stored state. `MultiplePgTransactionsException` logs a WARN and answers the stored state. A mismatched transaction is logged by TX B's `Conflicted`. None of them changes the payment or the reservation, and no path sends a create.
  - The new error type `PAYMENT_NOT_STARTED` is 409. `OrderApi` shares the 202-with-`Location` / 200 mapping between confirm and reconcile, and `OrderApiSpec` documents the endpoint.
  - The confirmation's unknown create now re-reads the stored state when it answers (see the decisions below).
- [`59a6551`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/59a6551) holds the notes:
  - ADR 0028 rule 3's dated note on what "stored state" means
  - `CODING_STANDARDS.md`: the three new spy uses and the WARN capture

### Acceptance criteria

- [x] **Scene 2: an accepted PENDING, then a reconcile that sees SUCCESS, confirms the order. Stock stays 3, with one create and one lookup.**
  - [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt)'s scene 2 test now continues into a reconcile.
  - After the confirm it reads back PAYMENT_PENDING / PENDING and stock 3.
  - After the reconcile it reads back CONFIRMED / SUCCESS, `confirmedAt`, the transaction key and stock 3. There is one create, one lookup and no unmatched request.
- [x] **Scene 4: after a lost response, a reconcile finds the transaction and finishes the payment. Creates stay at 1.**
  - The lost-response test continues into a reconcile: UNKNOWN becomes CONFIRMED / SUCCESS with the transaction key recorded. Stock stays 3, with one create and one lookup.
- [x] **Scene 6: two reconciles, or a reconcile racing confirm's TX B, applying the same SUCCESS confirm the order once. For FAILED, the stock comes back exactly once.**
  - Two tests, each run for SUCCESS and FAILED. The first TX B is paused inside the payment's apply while it holds the order lock. The other TX B is shown waiting (`finishesWithin(LOCK_WAIT_PROBE)` is false), then the first is released.
  - Both answers carry the same status and the same `confirmedAt`. Stock ends at 3 for SUCCESS and 5 for FAILED; a double return would be 7. There is one create, and two lookups (two reconciles) or one (reconcile against confirm).
  - **Mutation check:** TX B reading the order without `FOR UPDATE` fails all four rows.
- [x] **Scene 7: the TX B bean is spied to throw once after the PG succeeded. The order stays payment pending and the payment UNKNOWN. A later reconcile confirms it once, and creates stay at 1.**
  - A `@MockkSpyBean OrderPaymentSagaSteps` makes `applyTransaction` run the original, flush, then throw `DataAccessResourceFailureException` once.
  - The confirm throws. A re-read shows PAYMENT_PENDING, UNKNOWN, no transaction key and stock 3.
  - The reconcile then confirms it: CONFIRMED / SUCCESS with the transaction key recorded, one create, one lookup.
- [x] **Scene 8: the payment slice's create is spied to throw before sending. The payment is UNKNOWN, the stock held, and there are zero creates. After a same-key confirm replay and a reconcile that sees zero transactions, nothing has changed and creates are still zero.**
  - The `PgTransactionService` spy throws a test `ProcessStopped` once before sending. Later calls are original, so any create would show in the count.
  - Read back after the stop: PAYMENT_PENDING, UNKNOWN, stock 3, zero creates.
  - A same-key replay and a reconcile that sees `[]` both answer PAYMENT_PENDING / UNKNOWN. The final read is unchanged, with zero creates, one lookup and no WARN.
- [x] **Scene 9: a lookup that returns nothing, several transactions, another PG order ID or another amount leaves the payment and reservation unchanged, with zero creates. The several-transactions and mismatch cases log a WARN.**
  - One test runs three cases on a payment left UNKNOWN with zero creates: several transactions, another PG order ID, and another amount.
  - Each answers PAYMENT_PENDING / UNKNOWN and leaves the payment and the stock unchanged, with zero creates and one lookup.
  - Each logs exactly one WARN: `PG 거래가 2개다`, `reason=PG_ORDER_ID_MISMATCH` or `reason=AMOUNT_MISMATCH`.
  - The lookup that returns nothing is scene 8's reconcile, which logs no WARN.
- [x] **An unavailable lookup (test PG 503 or stall) answers 202 and changes nothing.**
  - At the port, both a 503 and a stall past the test profile's 200 ms response limit answer the stored PAYMENT_PENDING / PENDING. The state is unchanged, with one create and one lookup.
  - Over HTTP, a 503 answers 202 with `Location`, and the body equals the first confirmation's and the detail's.
- [x] **The HTTP test covers 202, 200, 409 `PAYMENT_NOT_STARTED` and 404 for reconcile, and the API docs describe the endpoint.**
  - [`OrderConfirmationApiTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/OrderConfirmationApiTest.kt):
    - 202, then a reconcile that finds SUCCESS or FAILED answers 200 without `Location`, and a same-key confirm replays the same body. This is the transition issue 155 handed off. Stock ends at 8 or 10, with one create and one lookup.
    - An unavailable lookup answers 202 with `Location`.
    - A draft answers 409 `PAYMENT_NOT_STARTED` and sends nothing to the PG.
    - A missing requester answers 401. A missing order and another user's order both answer 404 with the same body. None of them looks anything up.
    - SUCCESS, FAILED and point-paid orders answer 200 twice, without `Location` and with the detail's body, and look nothing up.
  - `OrderApiSpec.reconcile` documents the endpoint. The confirm description now says a reconcile that finished the payment during an unanswered create makes it 200.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes, with executed test counts checked.** 71 classes, **856 tests, 0 failures, 0 skipped**. That is issue 155's 835 plus 21 new tests.

  | Class | Tests |
  | --- | --- |
  | `OrderPaymentIntegrationTest` | 31 (was 18) |
  | `OrderConfirmationApiTest` | 45 (was 37) |
  | `ProductCacheTest` | 16 (one test rewritten) |

### Decisions made while implementing

- **The stored state is read when answering.** Issue 151 left this open: "decide whether that path should read the order again."
  - Confirm's unknown create and reconcile's null, unavailable and several-transactions answers now re-read the order and payment status. They no longer return the state read before the PG call.
  - So a reconcile that finishes the payment during an unanswered create now shows as confirmed (200), not pending (202). Step 3's callback will open the same window.
  - ADR 0028 rule 3 has a dated note. A test runs a reconcile during the confirmation's PG call, and the lost-response confirmation then answers CONFIRMED / SUCCESS.
  - `ProductCacheTest` relied on no SQL after TX A ("답을 모르는 확정은 TX A가 저장한 상태로 답하므로 그 뒤에 읽는 SQL도 없다"), and the full check caught it. The test now asserts that the revision upsert is TX A's last *locking* statement, with only plain `SELECT`s after it. The lock order it guards (order → product → revision) is unchanged.
- **Names for the spec's placeholders.** `OrderPaymentReconciler` reconciles the order's payment and matches the `/payment/reconcile` path. `PgTransactionRetriever` is not a Finder, because a PG lookup's `null` does not mean "missing".
- **The first read is unlocked** (`OrderFinder.find`), as the spec says, and only TX B locks. Scene 6's lookup counts pin this: a locking first read would see the finished order and skip the second lookup.
- **The several-transactions WARN logs only the order ID and the exception message**, not the PG order ID. The order slice still reads nothing of a payment but its status (ADR 0028 rule 5).
- **WARNs are read on the `com.loopers.application` logger**, so the tests don't care which class logged.
- **Review.** A two-axis review ran before the commit.
  - **Spec:** no gaps. It noted that scene 8 read state only at the end, so a read right after the stop now pins "nothing changed".
  - **Standards:** two fixes.
    - The API doc said "PG에 결제를 다시 요청하지 않습니다", a phrase `CONTEXT.md` avoids. It now says "PG 거래의 생성을 다시 보내지 않습니다".
    - A 401-then-404 test named its bodies `error` / `secondError` instead of `body` / `error`.

    A `pgTransaction` helper duplicated in both test classes is now inlined at the HTTP test's one call site.
  - **Kept:** the string-flag parameterizations (precedent: `deleted: String`), and the point-paid SQL bypass duplicated across four order HTTP test classes.

### For later tickets

- **[Issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157):** the payments design doc should carry:
  - the reconcile contract: the status checks and the lookup's exception table
  - the scene and test pairs for scenes 2, 4, 6, 7, 8 and 9
  - the stored-state re-read and the `ProductCacheTest` relaxation
  - the ports `OrderPaymentReconciler` and `PgTransactionRetriever`
- **Not in any ticket:** a base-class helper for the point-paid SQL bypass, shared by four order HTTP test classes.

## Issue 157: the payments design doc covers step 2

Recorded 2026-10-23 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157#issuecomment-6353617364) of [issue 157](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/157). The rules the doc traces are [ADR 0024](../adr/0024-confirm-calls-the-pg-between-two-transactions.md) through [ADR 0029](../adr/0029-pg-order-id-is-minted-per-payment.md) and the tests of tickets 148 to 156. The docs are:

- [`docs/design/payments.md`](../design/payments.md), sections 9 to 17
- [`docs/design/points-orders.md`](../design/points-orders.md) and [`docs/domain/points-orders.md`](../domain/points-orders.md), dated notes
- [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt), the one test change

### Landed

Two commits on `feat/order-payment` (pushed, `98d88bc..bbb6136`):

- [`005ebc9`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/005ebc9) is the one test change. The lost-response test (scene 4) in [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt) now asserts exactly one WARN after the confirm, carrying `결과 미확인` and the order ID (see the decisions below).
- [`bbb6136`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/bbb6136) is the docs:
  - [`docs/design/payments.md`](../design/payments.md) gets sections 9 to 17:
    - §9: sources, scope, and what tickets 148 to 156 built
    - §10: the order × payment state combinations, the transition diagram and table, and the guards
    - §11: TX A, the PG call and TX B, each with its ports and propagation; the lock table; the orchestrator's `NEVER`; the create's outcomes and their WARNs
    - §12: confirm's ten-step check order, the replay contract and the responses
    - §13: the reconcile's order, the lookup's outcomes, and the stored state read when answering
    - §14: the scene table. All 31 `OrderPaymentIntegrationTest` cases are mapped, with create and lookup counts and end states.
    - §15: the accepted limits
    - §16: the names table and the PR command block
    - §17: the step-3 handover

    The title now covers step 2, and §1.1's status cell for step 2 reads "끝남(2026-10-23, …)". Step 1's sections that step 2 made stale (the intro, the status line, §1.1, §2.5, §5.3 and §8) have dated notes.
  - [`docs/design/points-orders.md`](../design/points-orders.md): 2026-10-23 dated notes on every section that describes paying for confirm with points, pointing at ADR 0024 and ADR 0027.
  - [`docs/domain/points-orders.md`](../domain/points-orders.md): one dated note. It corrects "TX B는 지금 주문만 잠근다", because a failure also locks the products and the revision, and it describes reconcile. Issue 154 handed this off.

[Spec 147](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147)'s body has nine new dated "2026-10-23 고침" notes, one wherever the implementation changed a decision (listed below). Nothing in its text was removed.

### Acceptance criteria

- [x] **The step-2 sections exist and match the code. Every rule in them can be traced to a test or an ADR.**
  - Each rule cites the ADR rule or the test that pins it. §14 names each scene's test.
  - The unknown create's WARN (§11.4) had neither a test nor an ADR. `005ebc9` adds a test for it rather than dropping the rule. **Mutation check:** deleting the `log.warn` in `OrderPaymentService.confirm` turns the test red.
- [x] **The command block was run. The recorded counts are the ones Gradle reported, and no filter matches nothing.**
  - Run 2026-10-23. The counts are read from the JUnit XML reports. Every filter found its classes, with 0 failed and 0 skipped.

    | Filter | Classes (tests) |
    | --- | --- |
    | `*PaymentIntegrationTest` | `OrderPaymentIntegrationTest` (31) |
    | `*OrderConfirmationApiTest` | `OrderConfirmationApiTest` (45) |
    | `*OrderConfirmer*Test` | `OrderConfirmerConcurrencyTest` (7), `OrderConfirmerRollbackTest` (1) |
    | `*OrderApiTest`, `*OrderAdminApiTest`, `*OrderFinderTest` | 55, 12, 10 |
    | `*.OrderTest`, `*.PaymentTest`, `*OrderRepositoryTest`, `*PaymentRepositoryTest` | 21, 55, 24, 14 |
    | `*PaymentGatewayTest` | `PaymentGatewayTest` (96), `ExhaustedPoolPaymentGatewayTest` (1), `ObservedPaymentGatewayTest` (2), `ConnectTimeoutPaymentGatewayTest` (2, ran rather than skipped) |
    | `*.ProductCacheTest`, `*PointChargerConcurrencyTest` | 16, 1 |

  - Every filtered run also runs `HexagonalArchitectureTest`'s 7 rules. The counts leave them out.
  - The full `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes: 71 classes, **856 tests, 0 failures, 0 skipped**. That equals issue 156's count, because `005ebc9` adds an assertion, not a test.
- [x] **The points-orders design doc has dated notes where it describes point payment for confirm, keeping the original text.**
  - The notes are on the status line and on §1, §2, §4, §5.1, §5.3, §5.5, §5.6, §5.9, §6, §7, §9, §10, §11, §15, §17 (with ADR 0025 and Q25, Q32 and Q33), §17.2, §17.3, §18.1 (Q39, Q42), §18.2, §18.3, §19 (Q44, Q45, Q46, Q49, 19.3), §19.2 and §20.
  - A script listed every mention of point payment for confirm and found none without a note.
  - A word diff of both points-orders docs deletes nothing.
- [x] **All relative links in the changed docs resolve.** A script resolved every relative link in the three docs.

### Decisions made while implementing

- **The WARN got a test.** The line was added in issue 151 as the only trace of a payment left UNKNOWN, since no metric finds one. No test read it, so the doc could not trace it. Pinning it seemed better than leaving the rule untraced, and it is the one code-side change in this docs ticket.
- **`'*.ProductCacheTest'` with a dot.** Without the dot the filter also matches `BrandProductCacheTest` and `LikeProductCacheTest`. The dots in `'*.OrderTest'` and `'*.PaymentTest'` do the same job.
- **The scene numbers follow spec 147's scene list.** I did not re-read the assignment's Notion page, which would have needed Chrome in front.
- **The key race happens at the payment's save** (§12.1 row 10). The payment's ID is `IDENTITY`, so the insert hits `UK_PAYMENT_ON_IDEMPOTENCY_KEY` at `PaymentStarter.start`'s save, not at commit. The first draft said commit, and the review caught it.
- **Wording.** "슬라이스" is the vertical slice. "조각" is only a Spring `Slice` page (§15.4).
- **Spec 147's nine notes:**
  - The names chosen for the spec's placeholders (issues 151, 152, 156)
  - `OrderStatusConflictException` (issue 149)
  - The list's `PAYMENT_MISSING` (issue 153)
  - The PG amount cap in TX A (issue 151)
  - The unknown create's WARN (issue 151)
  - The stored state read when answering (issue 156)
  - `InvalidPaymentAmountException` deleted (issue 154)
  - Scene 9's zero-transaction case lives in scene 8's test (issue 156)
  - The stock race staying in `OrderConfirmerConcurrencyTest`, run by the PR's third command (issue 151)
- **Review.** A two-axis review ran on the docs before the commit.
  - **Standards:**
    - Dated notes were missing in points-orders §5.1, §5.6's 10–12 note, §17.1 (Q25, Q32, Q33), §18.1 (Q39) and §19.1 (Q49), and in payments §5.3.
    - "조각" was used with two meanings.
    - Some wording was not glossary-safe: "결제가 없는 PG 주문", "포인트 주문", and an ambiguous "결제 실패".

    All of these are fixed.
  - **Spec:** six fixes.
    - The untraced WARN (`005ebc9`).
    - Spec notes were missing for the WARN and for scene 9's zero-transaction case.
    - §12.1 placed the key race at commit.
    - The "트랜잭션 안" row claimed zero creates for both tests, but the reconcile test's setup sends one.
    - §16.2's comment said the filter covers all of scene 11. Its point-paid half is in `OrderConfirmationApiTest`.
    - The validator row missed the call made outside any transaction.
  - **Kept as judgement calls:**
    - §10 to §13 restate rules their ADRs already hold.
    - One decision fans out into many dated notes across points-orders.md.
    - The title is rewritten.

### For later tickets

- **Step 3:** §17 of the payments design doc is the handover:
  - The callback. `PaymentRepository` has no find by PG order ID yet, and the reconcile port takes a requester that the callback does not have.
  - The `transaction_key` index.
  - The breaker. It goes on lookups only, and a refused lookup still surfaces as `PgUnavailableException`. It must never wrap the create inside the adapter.
  - Retries resend lookups, never creates.
  - The restart scene.
  - Metrics for payments left UNKNOWN.
- **[Spec 147](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147):** with this ticket, tickets 148 to 157 are all closed. The spec can be reviewed against its checklist and closed.

## Spec 147: the step 2 spec review

Recorded 2026-10-23 from the [spec review comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147#issuecomment-6354544931) of [spec 147](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/147). The review ran both axes of a code review over the whole branch, after all ten tickets had closed. The merged `PgTransactionClient` port came from a question asked during the review, not from a finding.

Spec review on 2026-10-23: `feat/order-payment` (HEAD `faec086` at review time) against `week5` (`54651ce`, the branch's merge-base), on two axes: this spec and the coding standards. Tickets 148–157 are all closed.

### Result: 56 of 56 stories met

The review found 55 met, 1 partial and 0 missing. The partial story was fixed in [`da34380`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/da34380). Stories 47, 48, 53 and 56 are about docs or structure, so they were checked by reading, not by a test.

- **Story 25** ("viewing my orders never to contact the PG or create a payment"). The detail view's tests asserted the PG counts, but the customer and admin list tests did not. Both list tests now assert that the test PG received only the two creates from their setup and no lookup.
  - Mutation check: an `OrderInfoAssembler.toInfos` that looks up each PG-paid order's transaction turns both tests red at the `lookupCount()` line. The lookup's exception was swallowed so the list still answered 200.
- **A nit under story 55.** The scene 6 race between a reconcile and the confirmation's TX B re-read only the order and the stock. It now re-reads the payment status too, as the two-reconcile variant already did.

The decisions without a story of their own also match the amended text: TX A's check order, the lock order, the `propagation` alias, `NEVER` on the orchestrator and on `PgTransactionService`, the migration, the ADR notes, and seams 1–5.

### Decided in this review

- **The vertical slice is 슬라이스, and 조각 means only a `Slice` page** ([`c440a7d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c440a7d)). ADR 0013 had used 조각 for both. The rule is in `CODING_STANDARDS.md`'s new `## 용어` section, and ADR 0013 carries a dated note. 191 uses that meant the slice now say 슬라이스, across KDoc, the ADRs, the design and domain docs and `CODING_STANDARDS.md`. About 200 page meanings stay 조각. Eight uses meant neither (a fragment of a response body, or Spring Data's repository fragment), so they were reworded and the rule has no exceptions. `docs/research/` stays as posted.
- **The two PG ports are now one, `PgTransactionClient`** ([`dd2e074`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/dd2e074), [`0de231a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/0de231a)). It has `create(payment, card)` and `retrieve(payment)`, and their contracts are unchanged.
  - `PgTransactionCreator` and `PgTransactionRetriever` had the same implementation (`PgTransactionService`, `NEVER`), the same caller (`OrderPaymentService`) and the same outside-any-transaction condition. Only ADR 0013's one-port-per-use-case rule split them. ADR 0013 records the exception, and ADR 0028 rule 1 has a dated note.
  - The port's KDoc and payments design 11.1 now say why the order slice calls this port instead of `PaymentGateway`:
    - another slice's `required` port is off limits (ADR 0013);
    - the implementation refuses a call inside a transaction before anything reaches the PG (ADR 0028 rule 2);
    - turning the payment's PG order ID and amount into a PG request stays in the payment slice (ADR 0028 rule 5).
- **"PG 주문" is on the Avoid list of `CONTEXT.md`'s 결제수단 entry**, because it collides with PG 주문 번호. Write "결제수단이 PG인 주문" instead.
- **Four judgement-call smells were fixed rather than declined.** They are listed below under `da34380`.

### Fixed in this review

[`da34380`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/da34380) (code and tests) and [`fbe5aaf`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/fbe5aaf) (docs):

- **The key's format is checked at the port** (catalog design 5.25). `OrderPaymentService.confirm` now checks the key with `Payment.checkIdempotencyKey` before TX A. Before, only the web boundary checked it ahead of the lookup. Under `utf8mb4_bin`'s PAD SPACE, a caller that skipped the web and sent `"key "` found the payment of `"key"`, so it got `IDEMPOTENCY_KEY_IN_USE` instead of a format error.
  - The new integration test ran red first with exactly that `CoreException`.
  - Payments design 12.1 has the bullet.
- **One rule for the payment status.** `OrderInfo` now rejects a payment status on an order that doesn't pay through the PG, and a missing status on an order that does. The three places that build it (the assembler, TX A, and the create response) now share that one rule. `OrderInfoTest` covers both cases.
- **The payment method is compared once.** `Order` gains `paysThroughPg` and `productQuantities`. TX A sends an order that pays through the PG to the replay branch and lets `ensureDraft()` reject a point-paid one.
  - The two kinds of order never overlap, so every answer stays the same. Payments design 12.1, rows 5 and 6, has a dated note.
  - `OrderTest` covers both properties, and the domain doc's property table lists them.
- **`OrderConfirmRequest.toCreditCard()`** replaces 26 copies of `CreditCard(type = request.cardType, number = request.cardNo)`. `startPaymentMappingKeyRace` is now `startPaymentOrRejectUsedKey`.
- **Standards:**
  - `hasStatus2xxSuccessful()` became the exact first status for each row (`CODING_STANDARDS.md`, HTTP tests);
  - stale KDoc in three places: `StockDeductor` (it described ADR 0003's confirm), `ProductFinder.findForUpdateOrNull` (it named only the deductor), and `PaymentGateway` (it described step 2's ports as future);
  - `Order`'s class KDoc is restored; it had been dropped with the point path;
  - a blank line after `reserveStock`'s guard (catalog design 5.33);
  - wording: test names say "PG order ID" to match `pgOrderId` (they said "PG order number"), and "PG orders", "실패한 시도" and "포인트 주문" are reworded.

### Spec text amended

Dated "2026-10-23 고침(검토)" notes in this issue's body:
- **브랜치와 단계:** the merged `PgTransactionClient` port, and the vocabulary change. The body's seven uses of 조각 now say 슬라이스.
- **결제, 멱등 키:** the orchestrator checks the format too.
- **TX A의 차례:** checks 2 and 3 swapped (`Order.paysThroughPg`, then `ensureDraft()`), with the same answers.

### Recorded deviations and open points

- **`ApiControllerAdvice` turns every `MissingRequestHeaderException` into 400**, not only a missing `Idempotency-Key`. Ticket 151 accepted it.
- **Two accepted extras:** `TestPgWarmUpConfig`, and the guard plus `addExact` in `Product.restoreStock`. Both were accepted in ticket 152.
- **Six commits predate the design interview and were not judged against this spec:** `8ffd51c`, `1faf289`, `a9dea84`, `bdd4b39`, `5324951` and `d7b7cfd`. They carry the BigInteger money change, `confirmedAt` leaving the responses, the `paid_amount` question, the duplicate-product guard and the stock reservation research. They go into `week5` with this branch.

### Declined review suggestions

- **`LIST_PATH` keeps its name.** It names the PG's wire list endpoint (ticket 148).
- **`PaymentGateway.retrieveTransaction(transactionKey)` stays without a caller.** This spec leaves it to step 3's callback.
- **The per-class wrappers around `acceptNextCreate`** (`acceptCreate`, `acceptSuccessfulCreate`) stay. Each fixes its own class's defaults.
- **Still open from ticket 156's review:** the point-paid SQL shortcut copied across four order HTTP test classes, and string-flag parameterizations.

### Verification

- `./gradlew cleanTest check` from the root at `0de231a`, which includes every module's `ktlintCheck`, ended `BUILD SUCCESSFUL`.
  - commerce-api: 864 tests in 72 classes, with 0 failures, 0 errors and 0 skips. That is the 856 from issue 157 plus 8 new ones: 1 for the key format, 5 in `OrderTest` and 2 in the new `OrderInfoTest` class.
  - commerce-batch: 3 tests. supports/jackson: 2 tests.
- `OrderPaymentIntegrationTest` now has 32 cases, up from 31.
- Mutation checks: story 25's list assertions (above); the key-format test ran red before the fix.
