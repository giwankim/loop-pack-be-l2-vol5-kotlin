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
- [`OrderPaymentSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSteps.kt)
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
  - the `NEVER` orchestrator `OrderPaymentService`, with TX A and TX B on `OrderPaymentSteps`
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
    - `OrderPaymentSteps` holds TX A (`startPayment`) and TX B (`applyTransaction`) and implements no port.
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
    - missing dated notes on ADR 0013, and the `@ApplicationService` KDoc, because `OrderPaymentSteps` implements no port
  - Three copies of the accepted-create stub became one base helper, `acceptNextCreate`.

### For later tickets

- **[Issue 152](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/152):**
  - TX B completes the order only on `Changed(to = SUCCESS)`. A FAILED transaction leaves the payment FAILED and the order payment pending.
  - `PgRejectedException` propagates as a 500 and leaves the payment UNKNOWN.
  - `PaymentReconciler` is the place for a `reject` beside `reconcile`.
- **[Issue 153](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/153):** the customer list still maps `OrderResponse.from(order)`, so a PG order in the list has no `paymentStatus` yet. `PAYMENT_MISSING` exists, but no test covers it.
- **[Issue 155](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/155):** replays and key conflicts go where TX A checks "non-draft → `ORDER_ALREADY_CONFIRMED`". The web boundary already rejects malformed keys before any lookup.
- **[Issue 156](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/156):**
  - The reconcile entry can reuse `OrderPaymentSteps.applyTransaction`.
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
