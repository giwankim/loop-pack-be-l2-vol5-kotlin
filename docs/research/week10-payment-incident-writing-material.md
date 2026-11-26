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
