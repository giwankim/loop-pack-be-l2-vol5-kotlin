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

## Issue 278: recovering the three unresolved payments through the reconcile API, proven on the same ten orders

Recorded 2026-11-26 from [issue 278's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/278#issuecomment-7191240350). The text below is the comment's.

Implemented as `933e30ff`, with review fixes in `68e86fac`, on top of `9c45c8af`. `scripts/final-check --app commerce-api` on `68e86fac`, a clean tree, started 2026-11-26T11:39:19Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 126 classes, 1249 tests (6 min 16 s), 0 failures, 0 errors, 0 skipped**. Class and test counts are the same as issue 277's run: recovery extends the same `@Test`. Its incident run is `20261126T114111Z-a052e207`. No file under `src/main` changed since `9c45c8af`.

### Acceptance criteria

- [x] **The fault set's same 10 orders, read fresh: 10 confirmed, 0 결과 미확인, 10 PG transactions all `SUCCESS`; 20 PG transactions in all.** [`PaymentIncidentE2ETest`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/68e86fac/apps/commerce-api/src/test/kotlin/com/loopers/adapter/webapi/v1/order/PaymentIncidentE2ETest.kt)'s new `runRecovery` reads the targets from the database's unresolved payments, asserts them equal to the three lost orders, and freezes them. It asserts response loss off, and reconciles each target once through `POST /api/v1/orders/{orderId}/payment/reconcile` with the owner's `X-USER-ID` (200, `CONFIRMED`/`SUCCESS`). `recovery.json` holds the fault's 10 order IDs, all `CONFIRMED`/`SUCCESS` with no reservation. Each payment's transaction key equals the one the PG holds, and no unresolved payment is left. The PG transaction count from the request log is 20.
- [x] **Create count is 1 for each of the 20 PG 주문 번호, the increase during recovery is 0, and no PG request missed its stub.** The total create count is the same before and after the reconciles. Each of the 20 orders, read fresh at the end, has one create. `pgOrderIdsCreatedMoreThanOnce` is empty and `unmatchedPgRequests` is 0. Each target has two list lookups: the diagnosis's and the reconcile's.
- [x] **Stock is down by exactly 20 at the end, with nothing more deducted on success.** Product stock is 0 (20 − 20) after recovery and is unchanged by the repeats.
- [x] **The owner's point balance and coupon are unchanged.** The owner's `point_account` and `coupon` rows, read whole at the start of the run, equal those read at the end.
- [x] **One outbox row per order, still one after the repeats.** After the repeats, each of the 20 orders has exactly one live `order_event_outbox` row.
- [x] **A second reconcile and a repeated PG 알림 on a recovered order change nothing.** See the deviation below for `result=unchanged`. After a second reconcile (200, `CONFIRMED`) and a repeated 알림 (200) for each target, the orders, payments, product and outbox rows of all 20 orders are unchanged. The captured event list is unchanged, and the PG received no request.
- [x] **Another user's reconcile of a fault order is refused.** 404 with `ORDER_NOT_FOUND`, inside the same unchanged-rows window.
- [x] **Scraped gauge `UNKNOWN` returns to `0.0`; health is UP.** `recovery.json`, `recovery-health.json` and `recovery-prometheus.txt` come from one scrape: `UNKNOWN 0.0`, `PENDING 0.0`, `UP`.
- [x] **The fault files' hashes at the end equal those in `recovery.json`.** `fault.json`, `fault-health.json`, `fault-prometheus.txt` and `fault-log-events.json` are hashed before the first reconcile. `recovery.json` carries the hashes as `faultFileSha256`. At the end the test reads `recovery.json` back from disk and compares it with the files hashed again.
- [x] **Red checks, not committed, recorded in the writing material.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Deviation: no `result=unchanged`

A recovered order is `CONFIRMED`. Both reconcile paths, the customer's and the PG 알림's, return at `OrderPaymentSaga.checkLookupNeeded` before any lookup or TX B, so they leave no event at all. `payment.apply.committed` with `result=unchanged` comes only from a TX B that re-reads a payment another path already applied: a race, which a sequential test cannot reach without a production change, and the spec forbids one. "Changes nothing" is asserted as no new event, no PG request and unchanged rows. That is stronger than one log line. The `unchanged` event itself stays pinned by `OrderPaymentLogEventTest`. This is a wording error in the ticket, not a gap in commerce, so no new ticket.

### Red checks

The changes below were not committed.
- **An early 알림 for a fault order.** The fault phase also plays the first lost order's PG 알림. The gauge wait fails with `expected: {"PENDING"="0.0", "UNKNOWN"="3.0"} but was: {"PENDING"="0.0", "UNKNOWN"="2.0"}`, and `failure.json` has `"phase" : "fault"`.
- **Recovery by a new confirm.** The first target is confirmed again with a new idempotency key instead of reconciled. Commerce refuses it with `IDEMPOTENCY_KEY_MISMATCH` before any create leaves, so the duplicate-create check does not go red. Recovery fails on the gauge instead (`UNKNOWN 1.0` where `0.0` is expected, `"phase" : "recovery"`). The guard against a second charge is ADR 0024/0025's idempotency key, not the test.
- **A second create re-sent straight to the PG.** To show that the duplicate-create check can go red, the first target's create is re-sent to the test PG over `HttpClient` (202). Recovery fails on that order's `createCount=2`, and the `finally`'s check is added as suppressed: `PG order IDs that received more than one create: [<its PG order ID>]`.

### Decisions made while implementing

- **The row snapshot gains the outbox.** The diagnosis's `diagnosedRows` is now `orderRows` and includes `order_event_outbox`. The diagnosis also asserts zero outbox rows for the three lost orders before recovery.
- **`IncidentPg` gains `requestCount()`**, delegating to `TestPg`, for the repeats' "no PG request".
- **The fault files are named from the phase files `observe` writes** (`phaseFiles("fault")` plus `fault-log-events.json`), so a renamed suffix cannot leave the hash list stale.
- **`faultFileSha256` is a nullable field of the phase record.** Only recovery sets it, and the shared mapper leaves it out of the other phases' files.
- **The PG transaction's `SUCCESS` is read from the fixture**, which is the PG here. A real lookup would add to the lookup counts the test asserts. The proof that commerce applied the PG's transaction is the matching transaction key.
- **The test is renamed** `lost responses leave three payments unknown under health UP, a PG lookup tells why, reconcile recovers them once`.

## Issue 279: the incident section of the design doc, and the final check's run committed as evidence

Recorded 2026-11-26 from [issue 279's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/279#issuecomment-7192347452). The text below is the comment's.

Implemented as `e875b133`, on top of `a0429979`. Docs and evidence only. `scripts/final-check --app commerce-api` on `a0429979`, a clean tree, started 2026-11-26T12:03:50Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 126 classes, 1249 tests (7 min 29 s), 0 failures, 0 errors, 0 skipped**. That is 2 classes and 5 tests more than step 1's final check (124, 1244). Its incident run is `20261126T120602Z-0c742bb1`. No file under `src/main` changed in spec 274. Only documents and evidence changed after `a0429979`.

### Acceptance criteria

- [x] **Section 3 is written in the design doc's Korean and uses the glossary's terms.** [Section 3 of `docs/design/payment-incident.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/e875b133/docs/design/payment-incident.md#3-2단계-사고의-재현과-복구) covers, in order: what was built, the lifecycle and its fixture, the three hypotheses and the verdict, the proof of recovery, the evidence layout, the failed-recovery cases, what was not chosen, the limits, the scenes, the red checks, the final check, where the build differs from the spec, and what step 3 inherits. 운영자 is on 관리자's Avoid list, so the section defines it once as the person handling the incident, not the admin-API role.
- [x] **The committed evidence is the run of this ticket's final check, and the design doc records its commit and run ID.** [`docs/evidence/week10/20261126T120602Z-0c742bb1/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/e875b133/docs/evidence/week10/20261126T120602Z-0c742bb1) holds the run's 12 files, byte-identical to the build directory (`diff -r` is empty). It has no `failure.json`, and the Prometheus texts are untrimmed. The four fault files still match the SHA-256s in `recovery.json`. Section 3.5 records the commit `a0429979` and the run ID, with the run's values per phase.
- [x] **The three red checks are in the design doc with the scene that failed.** Section 3.10 lists them: the early 알림 (fault phase, `UNKNOWN 2.0`), the recovery by a new-key confirm (refused with `IDEMPOTENCY_KEY_MISMATCH`, failing on the gauge), and the lost order's PG transaction held `PENDING` (A `ACCEPTED`). It adds the direct re-sent create from 278 and the two from 275.
- [x] **The design doc's step table marks step 2 as built.** So does the status line.
- [x] **`scripts/final-check --app commerce-api` passes on a clean tree, with no skipped tests, and its commit, time and counts are recorded.** Section 3.11. See the run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Also in section 3

- **Week 5 scenes for failed recoveries (3.6).** Lookup unavailable, no transaction (scene 8), several transactions and another amount (scene 9) are cited by test name. For "still `PENDING`" no week 5 scene reconciles while the PG still says pending. The scene cited is step 1's `unchanged` log event scene, and it asserts the events only. No scene re-reads the payment row after a reconcile finds `PENDING`. This is recorded as a limit.
- **Two limits for step 3's runbook (3.8).** An operator reconciles as the customer, carrying the owner's `X-USER-ID`, and must reach the PG directly to diagnose.

### Decisions made while writing

- **The evidence is a separate directory per run**, `docs/evidence/week10/<run id>/`, so a later step's run can sit beside it.
- **The review corrected 278's wording.** `result=unchanged` does not come only from a race. A sequential reconcile that finds `PENDING` again leaves it too. Section 3.4 says that a confirmed order's reconcile and 알림 never reach TX B. 278's note above is left as it was recorded.
- **The committed Prometheus texts carry the local absolute path** in the `disk_free_bytes` and `disk_total_bytes` labels. They are kept untrimmed, as the spec asks.

## Spec 274: the closing review

Recorded 2026-11-26 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274#issuecomment-7194436290) of [spec 274](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274). The text is the comment's, with its blob URLs.

Step 2 of week 10 is built, and its closing review is done. `scripts/final-check --app commerce-api` on `7902fb9f`, a clean tree, started 2026-11-26T13:51:41Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 126 classes, 1249 tests (6 min 25 s), 0 failures, 0 errors, 0 skipped**. Step 1's final check ended at 124 classes and 1244 tests, so this step adds 2 classes and 5 tests. The review's fixes add and remove no test, so the counts match the run on `a0429979` that issue 279 recorded. Only documents and evidence change after `7902fb9f`.

Tickets 275–279 are closed, each with its own comment. [Design 3](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident/docs/design/payment-incident.md#3-2단계-사고의-재현과-복구) holds the step. No file under `src/main` changed in this spec, and there is no ADR.

### The incident, as the committed run shows it

The committed evidence is now this final check's incident run, [`docs/evidence/week10/20261126T135338Z-47f59811/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/feat/payment-incident/docs/evidence/week10/20261126T135338Z-47f59811): 12 files, no `failure.json`, the Prometheus texts untrimmed. It replaces issue 279's run `20261126T120602Z-0c742bb1`, because the review changed the incident test. The values are the same; only the timestamps, the PG order IDs and the hashes differ.

| Phase | What the files hold |
| --- | --- |
| Baseline | Orders 1–10 all `CONFIRMED`/`SUCCESS`, one create and one list lookup each. Stock 10, 10 PG transactions, gauge `UNKNOWN 0.0`, `PENDING 0.0`, health `UP` |
| Fault | Orders 11–20 under response loss. 13, 16 and 19 are `PAYMENT_PENDING`/`UNKNOWN` with one reservation, one create, no list lookup and a `null` transaction key, and they are the database's unresolved payments. Stock 0, 20 PG transactions, gauge `UNKNOWN 3.0`, health still `UP`. Each lost order's only event is `pg.create.outcome-unknown` (WARN, `cause=NoHttpResponseException`) |
| Diagnosis | Hypotheses written at 13:53:39.578Z, before the first PG lookup at .590Z. Every lookup finds `["SUCCESS"]`: A (still processing) rejected, B (response lost) accepted, C (failed apply) rejected. No order, payment, product or outbox row changed |
| Recovery | The customer's reconcile on the three. Orders 11–20 all `CONFIRMED`/`SUCCESS`, no reservation, one create each, so no customer is charged twice. 20 PG transactions and 20 creates, no unresolved payment, gauge `UNKNOWN 0.0`, health `UP`. The four fault files still hash as `recovery.json` holds them (`fault.json` starts `bb4fd0c2…`) |

### Where the implementation differs

[Design 3.12](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident/docs/design/payment-incident.md#312-명세와-다른-것) has nine rows, all from the tickets; this review added none. The ones that change what a reader would assume:

| The spec said | What is there | How it was found |
| --- | --- | --- |
| A repeated reconcile and 알림 leave `result=unchanged` | No event at all. A confirmed order returns before the lookup and TX B, so the test asserts no new event, no PG request and equal rows | Issue 278 |
| Recovering by a new-key confirm turns the duplicate-create check red | Commerce refuses it with `IDEMPOTENCY_KEY_MISMATCH` before any create, so it fails on the gauge. A create re-sent straight to the test PG shows the check can go red | Issue 278 |
| All 10 PG transactions `SUCCESS` | Read from the fixture, which is the PG here. The proof that commerce applied each is the matching transaction key | Issue 278 |

**Not proven here:** how an operator reconciles without carrying the customer's `X-USER-ID`, diagnosis without direct access to the PG, a PG that sends its 알림, response loss other than a dropped connection, hypothesis C after a lookup, the payment row after a reconcile finds `PENDING` again, and anything about throughput or concurrency ([design 3.8](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident/docs/design/payment-incident.md#38-한계)). The first two are where step 3's runbook starts.

### The closing review

A two-axis review of `week10...feat/payment-incident` at `c2bd90a8` (17 commits, 21 files), with one reviewer on the standards and one on this spec. Standards found 1 hard breach of a documented rule, 3 possible breaches and 6 kinds of possible smell. Spec found 2 partial requirements, 3 weaker implementations and no scope creep. Each was checked against the code and decided.

#### What changed

- **`3136770a`: the incident test takes its order IDs from the orders `prepareOrder` returns.** It created the orders, dropped what the port returned, and read the IDs back by SQL, against the fixture rule that generated values are read from what `prepare` built. The port returns the saved order, so the IDs are still the database's own. This supersedes issue 276's "named by the order IDs read back from the database".
- **`3136770a`: the callback's request JSON is `json`**, not `body`, as the HTTP test rule names it, and `charge` takes the balance and the owner by name.
- **`3136770a`: the PG contract's user ID lives in one place.** `TestPg.PG_USER_ID` is public, and the incident test and `IncidentPgTest` use it instead of their own `"135135"`.
- **`7902fb9f`: the design no longer says baseline to recovery took under a second.** It was measured with a 100 ms gauge refresh where production uses 10 s, so under the evidence rule it was at best a reference, and nothing argued from it.

#### Left as they were

- **The `finally` releases response loss and checks for a second create before writing `failure.json`.** Both are in-memory WireMock calls, and the class promises `failure.json` for a failure in the phases, which holds.
- **The spec's three weaker items** (`SUCCESS` read from the fixture, the second red check, no `result=unchanged`) are stated in design 3.4, 3.10 and 3.12.
- **Each phase states its own assertions**, so the per-order block and the health and duplicate-create checks repeat across phases. A phase reads top to bottom as its acceptance criteria, and a red check fails on the line that names it.
- **`IncidentPg` delegates five reads to its private `TestPg`.** It is the one test PG the incident sees, and keeping `TestPg` private keeps stubs from going around its state.
- **Lost orders filtered outside `PhaseRecord`**, at two call sites in one file.
- **Strings for statuses and gauge values, `(pgOrderId, amount, card)` travelling together, and `Hypothesis.A/B/C`.** The records mirror the JSON files, and A/B/C are the spec's labels, already in the committed `hypotheses.json`.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident/docs/research/week10-payment-incident-writing-material.md#spec-274-the-closing-review).

## Draft PR body

The body of the PR from `feat/payment-incident` into `week10` (PR 280), written at the spec's closing review (2026-11-26) and updated after the PR's review in `/mattpocock-skills:pr`'s shape, with the final check on `7902fb9f` and the comment's link. It makes no timing, throughput or concurrency claim; none was measured under production settings.

### Summary

Week 10 step 2. The PG succeeds but commerce never hears it, so three payments stay 결과 미확인 while health answers UP. This branch reproduces that incident in one test on real MySQL and the test PG, tells its cause apart from two others by a read-only PG lookup and each order's log events, recovers the payments through the customer's 대사, and proves the recovery on the same ten orders. Every phase writes its evidence to files and asserts on what it wrote.

- **Spec:** issue 274, with tickets 275–279, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/274#issuecomment-7194436290)
- **Design:** [`docs/design/payment-incident.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident/docs/design/payment-incident.md) section 3: 3.2 the lifecycle, 3.3 the hypotheses and the verdict, 3.4 the recovery and its proof, 3.5 the evidence, 3.8 the limits, 3.10 the red checks, 3.12 what differs from the spec, 3.13 what step 3 inherits
- **Evidence:** [`docs/evidence/week10/20261126T135338Z-47f59811/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/feat/payment-incident/docs/evidence/week10/20261126T135338Z-47f59811), the final check's incident run, 12 files as written
- **Writing material:** [`docs/research/week10-payment-incident-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident/docs/research/week10-payment-incident-writing-material.md), which holds every ticket's closing comment
- **No production code changed, and no ADR.** Test code and documents only.

One `@Test`, four phases, each ending in one observation that is written and then asserted:

```text
PaymentIncidentE2ETest
  baseline   orders 1–10, each confirmed and finished by its PG 알림         → baseline.json, -health.json, -prometheus.txt
  fault      orders 11–20; the 3rd, 6th, 9th lose the create's response     → fault.json, …, fault-log-events.json
             (IncidentPg: the PG makes a SUCCESS transaction and drops the connection)
  diagnosis  hypotheses first, then a PG list lookup sent straight to the test PG → hypotheses.json, verdict.json
             A still processing | B response lost | C failed apply, judged by the lookup's status and the order's events
  recovery   the customer's 대사 on the unresolved payments in the DB           → recovery.json (with the fault files' SHA-256), …
  finally    release the loss, fail on any PG order ID created twice          → failure.json on a failure
```

### Evidence

**Before:** step 1 showed one lost order moving the gauge, but nothing reproduced an incident among normal orders, separated its cause from the others, or showed the recovery on the same orders without a second create.

**After:** from the committed run, values as the files hold them.

| Phase | Gauge `UNKNOWN` | Health | Unresolved in the DB | PG transactions / creates | Stock |
| --- | --- | --- | --- | --- | --- |
| Baseline (orders 1–10) | 0.0 | UP | none | 10 / 10 | 10 |
| Fault (orders 11–20) | 3.0 | UP | 13, 16, 19 | 20 / 20 | 0 |
| Recovery (the same 11–20) | 0.0 | UP | none | 20 / 20 | 0 |

- **Diagnosis:** every lookup finds `["SUCCESS"]` and each lost order's only event is `pg.create.outcome-unknown`, so A is rejected, B accepted, C rejected. No order, payment, product or outbox row changed.
- **Normal confirms:** every normal order's confirm answers 202 with `PENDING` before its 알림, and each lost order's answers 202 with `UNKNOWN`.
- **Recovery:** all ten `CONFIRMED`/`SUCCESS` with one create each, the lost three with two list lookups (the diagnosis's and the 대사's). A second 대사 and a repeated 알림 change no row and send no PG request; another user's 대사 is refused.

| Red check (local, never committed) | Result |
| --- | --- |
| One lost order's 알림 sent before the fault's observation | The fault phase: `UNKNOWN 2.0` where `3.0` is expected; `failure.json` with `"phase" : "fault"` |
| The first target confirmed with a new 멱등 키 instead of reconciled | Refused with `IDEMPOTENCY_KEY_MISMATCH` before any create; recovery fails on the gauge (`UNKNOWN 1.0`) |
| The first target's create re-sent straight to the test PG | Recovery fails on `createCount=2`, and the `finally` adds the duplicate-create failure |
| One lost order's PG transaction held `PENDING` | The diagnosis: A `ACCEPTED`, `expected: REJECTED but was: ACCEPTED` |
| `stopResponseLoss` leaves the loss stub | `IncidentPgTest`'s switch scene: `expected: 200 but was: 202` |
| `logEventsOf`'s `orderId` filter inverted | 21 of `OrderPaymentLogEventTest`'s 22 scenes |
| The first baseline order's create response lost (PR review) | The baseline's confirm: `expected: "PENDING" but was: "UNKNOWN"`; `failure.json` with `"phase" : "baseline"` |
| `createdPgOrderIds` throwing on a create without `orderId` again (PR review) | `IncidentPgTest`'s new scene: `PathNotFoundException: No results for path: $['orderId']` |

`scripts/final-check --app commerce-api` on `7ff6d2e4`, a clean tree, after the closing review's and this PR's review fixes, with ktlint passing and only documents changed since (6 min 46 s). Its incident run `20261126T144218Z-9cea88b6` asserts the same values as the committed one; only timestamps, PG order IDs and hashes differ:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-api | 126 (124 before) | 1250 (1244 before) | 0 |

### Merge Danger

**Door:** two-way

Test code, documents and evidence files only. A revert removes them.

**Blast Radius:** commerce-api's test suite

- `TestPg` gained four reads and a public `PG_USER_ID`; its stubs are unchanged, and every earlier scene passes.
- `OrderPaymentLogEventTest` reads events through the new `LogEvents.kt`, with its 22 scenes as strict as before.
- The committed Prometheus texts carry the local absolute path in the `disk_free_bytes` and `disk_total_bytes` labels, kept untrimmed as the spec asks.
