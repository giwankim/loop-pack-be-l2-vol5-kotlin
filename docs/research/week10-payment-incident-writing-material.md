# Week 10 payment incident: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274), then the spec's own.

## Issue 275: an incident fixture over the test PG, and one order's log events

Recorded 2026-11-26 from [issue 275's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/275#issuecomment-7189568038). The text below is the comment's.

Implemented as `de6dbfb6`, with review fixes in `bd3c2704`, on top of `c2f6abd4`. `scripts/final-check --app commerce-api` on `bd3c2704`, a clean tree, started 2026-11-26T09:42:12Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 125 classes, 1248 tests (5 min 57 s), 0 failures, 0 errors, 0 skipped**. No file under `src/main` changed since `c2f6abd4`. Only the writing material changed after `bd3c2704`.

### Acceptance criteria

- [x] **The fixture has its own scenes.** [`IncidentPgTest`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/bd3c2704/apps/commerce-api/src/test/kotlin/com/loopers/support/IncidentPgTest.kt), four scenes through `mvc`. A registered normal order confirms with 202 as `PAYMENT_PENDING`/`PENDING`, and the PG's callback finishes it as `CONFIRMED`/`SUCCESS`. A registered lost order confirmed while loss is on answers 202 as `PAYMENT_PENDING`/`UNKNOWN`, with one create and zero list lookups for its PG order ID. A create sent a second time for one PG order ID is reported, and the order created once is not.
- [x] **Response loss can be asserted off and on, and released from a `finally`.** `isResponseLossOn()` is false before `startResponseLoss()`, true after it, and false after `stopResponseLoss()` in a `finally`. A second release is harmless. Once released, the lost order's create is answered with its `SUCCESS` transaction and the order is confirmed with one create, so the switch really removes the loss.
- [x] **`OrderPaymentLogEventTest` reads events through the new reader and its scene count is unchanged.** It reads through [`logEventsOf(orderId)` and `logEvents()`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/bd3c2704/apps/commerce-api/src/test/kotlin/com/loopers/support/LogEvents.kt). It runs 22 scenes, as before. No `@Test` or data row changed.
- [x] **No production file changes.** `git diff --name-only c2f6abd4 bd3c2704 -- '*/src/main/*'` is empty. `OrderPaymentSaga` is untouched.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above.
- [x] **The closing comment is appended to the writing material.** It goes into `docs/research/week10-payment-incident-writing-material.md`, which this ticket creates.

### Red checks

Neither change below was committed.
- `stopResponseLoss` that keeps the loss stubs: the switch scene fails with `expected: 200 but was: 202`, because the create is still lost.
- `logEventsOf` with its `orderId` filter inverted: 21 of the 22 log event scenes fail.

### Decisions made while implementing

- **The loss is a stub over a stub.** A lost order has an accepting stub (202, `SUCCESS`) and, while the loss is on, an `EMPTY_RESPONSE` stub over it. When two stubs share a priority, WireMock matches the newer one. Stopping the loss removes the loss stubs, and the accepting stub answers again. A lost order registered while the loss is on gets its loss stub at once. Normal orders are never lost.
- **Every held transaction is `SUCCESS`.** That is what a list lookup by the PG order ID finds, for normal and lost orders alike. A normal order's create is answered `PENDING`. The fixture mints keys as `20261126:TR:incident-<n>`, unique within one fixture.
- **`TestPg` stays stateless and gains reads.** `losesCreateResponse` now returns its `StubMapping`, and the class gains `remove(stub)`, `createdPgOrderIds()` (the body's `orderId` of every create in the request log) and `listLookupCount(pgOrderId)`. That last one is named apart from `lookupCount()`, which also counts lookups by transaction key.
- **A second create is reported, not thrown.** `pgOrderIdsCreatedMoreThanOnce()` reads the request log, registered order or not. The incident test (issue 276) asserts it empty at every phase and in `finally`.
- **The reader keeps level and exception.** `LogEvent` holds the level, the key-value pairs as text and the exception logged with it. Without the level and the exception, `OrderPaymentLogEventTest` could not keep its assertions.
- **The log event scenes are as strict as before.** They now read one order's sequence. The review saw that a stray event of another order, or one without `orderId`, would no longer fail a scene. A helper now checks that the order's sequence equals every captured event.
- `CODING_STANDARDS.md` gains a dated note under the test PG's paragraph.

## Issue 276: the baseline and fault phases of the payment incident, and their evidence

Recorded 2026-11-26 from issue 276's closing comment. The text below is the comment's.

Implemented as `47b7c59f`, with review fixes in `49d9c328`, on top of `b58090a8`. `scripts/final-check --app commerce-api` on `49d9c328`, a clean tree, started 2026-11-26T10:51:32Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 126 classes, 1249 tests (6 min 21 s), 0 failures, 0 errors, 0 skipped**. That is one class and one test more than issue 275's run. No file under `src/main` changed since `b58090a8`. Only the writing material changed after `49d9c328`.

### Acceptance criteria

- [x] **Baseline.** [`PaymentIncidentE2ETest`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/49d9c328/apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/PaymentIncidentE2ETest.kt), one `@Test` on `BaseRealPortWebApiAdapterTest`, outside the test transaction. Response loss is asserted off. 10 orders are confirmed through `mvc` (202 each, the create accepted as `PENDING`), and each one's PG 알림 is played right after its confirmation (200). `baseline.json` holds 10 orders `CONFIRMED`/`SUCCESS`, one create each, the scraped gauge `UNKNOWN 0.0` and `PENDING 0.0`, health `UP`, and 10 PG transactions.
- [x] **Fault.** 10 other orders. All 10 PG transactions are registered before the loss starts: the 3rd, 6th and 9th, named by the order IDs read back from the database, as lost (`SUCCESS`), the rest as normal. `fault.json` holds 7 orders `CONFIRMED`/`SUCCESS`, and 3 `PAYMENT_PENDING`/`UNKNOWN` whose IDs equal the database's unresolved payments. It also holds the gauge `UNKNOWN 3.0` and `PENDING 0.0`, health `UP`, 20 PG transactions in all, product stock 0 (20 − 20, so the three reservations are held), and, for each of the three, one create and zero list lookups.
- [x] **No status is written directly into the commerce database.** Orders are created through the order port and move only through `mvc` (confirm, PG 알림). The test's SQL is all `select`.
- [x] **Every identifier asserted or written is read back.** Order IDs, idempotency keys, PG order IDs, transaction keys and statuses come from fresh reads of `orders`, `order_line_item` and `payment`. Create and lookup counts and the PG transaction count come from the test PG's request log. The PG 알림 reads its PG order ID from the payment row.
- [x] **The assertions run on the same values that the files hold.** Each phase builds one record from those reads and one scrape of the management port, writes it, and asserts on that record. Health and Prometheus are written from the same scrape, as fetched.
- [x] **Two runs write two directories.** The run directory is `build/payment-incident/<UTC start>-<random suffix>/`, made with `createDirectory`. Every file is written `CREATE_NEW`. The runs of this ticket left one directory each, for example `20261126T104612Z-cf2f4d4e` and `20261126T104650Z-d15d4b19`.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above. Its incident run is `20261126T105330Z-fe20fbb4`.
- [x] **The closing comment is appended to the writing material.** This section.

### Red checks

The change below was not committed.
- The fault phase plays the PG 알림 of the first lost order as well. The gauge wait fails with `expected: {"PENDING"="0.0", "UNKNOWN"="3.0"} but was: {"PENDING"="0.0", "UNKNOWN"="2.0"}`. The run directory holds the baseline's three files and `failure.json` with `"phase" : "fault"` and the `ConditionTimeoutException`, and no fault file.

### Decisions made while implementing

- **PG order IDs are drawn in confirmation order.** `TestPgOrderIdGenerator` is a FIFO queue, and the fault's 10 IDs are all drawn and registered before the loss starts, as the spec asks for the lost orders' `SUCCESS` transactions. The confirmations then run one by one in the same order, so the n-th registration is the n-th payment.
- **The scrape is retried, and the files hold the last one.** The gauge reaches the text one refresh later, so each phase scrapes health and Prometheus together until the gauge reads what the phase expects (10 s at most). The record, the assertions and both raw files come from that one scrape.
- **A PG transaction is counted from the request log.** The review saw that counting the fixture's registered transactions would read 10 and 20 whatever commerce did. The count is the number of PG order IDs that received a create. Every create matched a stub, and the PG makes the transaction whether its answer is sent or lost.
- **A lost order's transaction key is written as `null`.** The project's Jackson leaves `null` fields out, so the order record is `@JsonInclude(ALWAYS)`. The file shows that commerce never learned the key, not that the test forgot to write it.
- **The reservation follows the order's status.** `reservedQuantity` is the line's quantity while the order is `PAYMENT_PENDING` (CONTEXT.md, 재고 예약). Product stock 0 is what shows the reservations were held and not released.
- **`failure.json` carries the phase, the exception's class and message, and its stack trace.** The `finally` releases response loss and checks for a second create on any PG order ID. A second create fails the test on its own, or is added as suppressed to an earlier failure. A failure in `@BeforeEach`, before the run directory exists, has nowhere to write.
- **The evidence writer stays in the test.** It has one user, so by the 도우미 rule it is a private class of `PaymentIncidentE2ETest`, not `com.loopers.support`. `IncidentPg` gains `createCount()` and `createdPgOrderIds()`. `CODING_STANDARDS.md` notes the class's constructor dependencies.
- **Not yet asserted here.** `fault-log-events.json` holds the three orders' captured events (each a single `pg.create.outcome-unknown` with `cause` `NoHttpResponseException`). Judging them is issue 277's verdict.
