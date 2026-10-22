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
