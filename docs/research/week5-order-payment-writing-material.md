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
