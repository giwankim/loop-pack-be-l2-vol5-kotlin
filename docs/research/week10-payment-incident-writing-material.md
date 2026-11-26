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

Recorded 2026-11-26 from [issue 276's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/276#issuecomment-7190418946). The text below is the comment's.

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

## Issue 277: judging three hypotheses for the unresolved payments by a read-only PG lookup and log events

Recorded 2026-11-26 from [issue 277's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/277#issuecomment-7190724667). The text below is the comment's.

Implemented as `f72939f6`, with review fixes in `f7d6a2df`, on top of `f6ed99bb`. `scripts/final-check --app commerce-api` on `f7d6a2df`, a clean tree, started 2026-11-26T11:19:29Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 126 classes, 1249 tests (6 min 14 s), 0 failures, 0 errors, 0 skipped**. Class and test counts are the same as issue 276's run: the diagnosis extends the same `@Test`. Its incident run is `20261126T112123Z-4599ad08`. No file under `src/main` changed since `f6ed99bb`.

### Acceptance criteria

- [x] **The payment, order and stock rows of the three orders are identical before and after the lookup.** [`PaymentIncidentE2ETest`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/f7d6a2df/apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/PaymentIncidentE2ETest.kt)'s new `runDiagnosis` reads whole rows (`select *`) of the three `orders`, their three `payment` rows and the one `product` row before and after the lookups. It asserts that the sets are equal, and that they hold 3, 3 and 1 rows, so the equality is not over empty lists.
- [x] **Per order: A is rejected by the lookup's status, C by the event sequence, B is accepted.** Each hypothesis is an enum entry with its statement, its expected observation, and a check that judges the observation. The test asserts per order that A is `REJECTED`, B is `ACCEPTED` and C is `REJECTED`. It also asserts that every lookup found exactly `SUCCESS`, so C, which expects `SUCCESS` too, can fall only to the event order.
- [x] **The event sequence is read from captured events by `orderId`, through the reader of the first ticket.** `orderApplicationLogs.logEventsOf(orderId).map { it.name }`. Each lost order's sequence is `[pg.create.outcome-unknown]`.
- [x] **Red check, not committed, recorded in the writing material.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above.
- [x] **The closing comment is appended to the writing material.** It goes into `docs/research/week10-payment-incident-writing-material.md`.

### Red checks

The change below was not committed.
- The fixture holds the first lost order's PG transaction as `PENDING` (`IncidentPg.hold`'s 13th transaction). The fault phase still passes, since its create response is lost and commerce sends no lookup. The diagnosis then reads lookup `["PENDING"]` for order 13 and judges A `ACCEPTED` and B `REJECTED`. The assertion fails with `expected: REJECTED but was: ACCEPTED` at the A verdict. The run directory holds the baseline and fault files, `verdict.json` with that order's judgements, and `failure.json` with `"phase" : "diagnosis"`.

### Decisions made while implementing

- **The test sends the lookup itself.** It sends `GET /api/v1/payments?orderId=<PG order ID>` with the PG contract's `X-USER-ID` over `HttpClient`, straight to the test PG, as `IncidentPgTest` sends its second create. No commerce endpoint is called, and the fixture gains nothing.
- **The operator's lookup is told apart by count.** It lands in the same request log as commerce's own lookups. The fault phase asserted zero list lookups for the three PG order IDs, and the diagnosis asserts exactly one afterwards, so it is the operator's and commerce sent none in between. Create counts (one per PG order ID, 20 in all) and unmatched requests (none) are asserted unchanged.
- **The hypotheses get their own file, `hypotheses.json`.** It is written before the first lookup. The evidence writer creates files only, so `verdict.json` could not be written early and filled in afterwards. `verdict.json` repeats the hypotheses and their `writtenAt`, so it reads on its own. Per order it holds the PG order ID, the lookup's time and raw body, one observation (lookup statuses and event names), and each hypothesis's expected observation and verdict. This file is one more than the spec's list.
- **The lookup's HTTP status is checked before its body is parsed**, so a 4xx or 5xx reaches `failure.json` as itself and not as a JSON parse error.
- **C follows the ticket's definition**, `pg.create.accepted` then `payment.apply.failed`. The design doc (2.11) also names `pg.lookup.found` → `payment.apply.failed` and `pg.create.rejected` → `payment.apply.failed` as failed applies. Neither path can reach a lost create, so the verdict is the same, but a later incident with a lookup in its sequence would need C widened.
- **The hypotheses keep the spec's letters A, B and C.** The review suggested names, and the letters were kept because spec 274 labels them that way. The enum's KDoc and the files carry each statement.
- The test is renamed `lost responses leave three payments unknown under health UP, a PG lookup and log events tell why, evidence is kept`. The longer name did not fit ktlint's 130 columns.
