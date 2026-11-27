# Week 10 payment incident follow-on: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286), then the spec's own.

## Issue 287: relaying the incident's twenty outbox rows under a pinned clock, and one purchase activity per order

Recorded 2026-11-27 from issue 287's closing comment. The text below is the comment's.

Implemented as `fe1a525e` on top of `c4054768`, on `feat/payment-incident-follow-on`. `scripts/final-check --app commerce-api` on `fe1a525e`, started 2026-11-27T03:51:30Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 123 classes, 1248 tests (6 min 28 s), 0 failures, 0 errors, 0 skipped**. The tree's one uncommitted change was the 운영자 entry in `CONTEXT.md`, which issue 289 commits and no test reads. No file under `src/main` changed since `c4054768`. The check's incident run is `20261127T035326Z-59776a3c`.

### Acceptance criteria

- [x] **The four existing phases keep their assertions and pass under the pinned clock.** I pinned the clock first and ran the test as it was: it passed (1 test, 0 skipped, 22.9 s), so there was no fallback to the real clock. The baseline and the fault confirm at 2026-09-08T10:00Z, recovery reconciles at 2026-09-10T10:00Z, and the relay runs at recovery + 5 s. The ranking period starts on 2026-09-07 (Monday). Evidence times stay on the real clock.
- [x] **The three recovered orders' `confirmed_at` is the recovery instant, and the repeated reconcile and PG 알림 leave it unchanged.** Recovery's repeats now run with the clock at recovery + 1 s. A repeat that confirmed an order again would therefore write a different time, and two assertions would see it: recovery's whole-row comparison of `orders` and the follow-on's read of every order's `confirmed_at`. That read finds the recovery instant for the three recovered orders and 2026-09-08T10:00Z for the other 17.
- [x] **After the relay, each of the 20 orders has one outbox row and all 20 are published.** The stopped round leaves 19 rows `PUBLISHED` with one attempt each. The 20th, the re-delivered order's, is `PENDING` with one attempt. After the next round, all 20 rows are `PUBLISHED`, that one with two attempts.
- [x] **The topic holds 21 records with 20 distinct event IDs.** `TopicReader` reads the 20 event IDs from the stopped round, then one from the next round, which is the re-delivered order's. That event is the only one read twice. It belongs to the **third** recovered order, not the first (see the deviation below).
- [x] **`purchase_activity` has 20 rows, one per order, quantity 1; 10 for the baseline set and 10 for the fault set.** Every row is for the one product, and its `confirmed_at` is the order's own, inside the week from `PERIOD_START`. Each of the three recovered orders is asserted by its order ID, with the recovery instant.
- [x] **`processed_order_confirmation` has 20 rows.** There is one per order, each with the order's event ID and `confirmed_at`. The streamer's log holds 21 deliveries: `RECORDED` once for each order, plus `ALREADY_RECORDED` for the re-delivered order (order 19 in the check's run).
- [x] **The test's SQL is all `select`.** It writes nothing to the streamer's tables.
- [x] **The streamer is stopped whether the test passes or fails.** It starts only in the follow-on phase. It is ended in that phase's `finally`, and again in `@AfterEach` inside a `try` before the tables are truncated.
- [x] **Red check, recorded below.**
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above.
- [x] **The closing comment is appended to the writing material.** This file, which this ticket creates.

### Deviation: the third recovered order is the one sent twice

The spec planned to send the first recovered order's event twice, using the existing `stopRelayAfterBrokerAckBeforeMarkingPublished` stub. That plan cannot give 21 records:
- At recovery + 5 s, one claim takes all 20 rows (`CLAIM_BATCH_SIZE` is 20), ordered by `nextAttemptAt, id`. The 17 rows confirmed on 09-08 come first and the 3 recovered on 09-10 come last.
- The publisher sends the whole batch, and the relay then saves each result in that order.
- The shared stub throws on the first save, which here is baseline order 1. A throw ends the round, so every row after it has been sent but not marked. Past the lease all 20 would go out again: 40 records.

Only the row the round saves last can be left as the one row sent twice. That row is the third recovered order's. The user chose this over two alternatives: losing one save without throwing (the round would not stop), or keeping the shared stub (40 records). The stub is a class-local extension that throws only for that row's ID. `CODING_STANDARDS.md` keeps stubs that a single class uses inside that class.

### Red checks

The change below was not committed.
- **The relay skips the first recovered order's row** (a temporary stub drops it from every claim). The phase fails at `Expected size: 20 but was: 19` on `purchase_activity`. The rows printed with the failure show order 13 `PENDING` after two attempts and missing from the purchase activities. `failure.json` has `"phase" : "follow-on"`.

### Decisions made while implementing

- **The relay is the context's `OrderEventRelayer` bean.** It runs over the spied `OrderEventOutboxModifyService`, not over a new `OrderEventRelay`, because the ticket names the port. `order-events.relay.enabled` stays off.
- **The assertions on purchase activities come before the ones on the topic.** The streamer is awaited for as many deliveries as the topic records actually read. So a missing row fails on the business count (19), not on a wait that runs out.
- **The streamer's group is a constant passed to `CommerceStreamerProcess`, as in `OutboxDeliveryE2ETest`.** `@TestPropertySource` sets only the topic. The group is the streamer's own setting, not commerce-api's.
- **`PERIOD_START` is already in the class.** The purchase activities are asserted inside its week. Issue 288 uses it for the batch run.
- **Left for issue 288:** the batch run, the ranking API, `follow-on.json`, the closing observation, the `@Test` rename, and the `@BeforeEach` that resets the ranking tables.
- **`CODING_STANDARDS.md` gains two dated notes for issue 287.** One covers the spy and `@TestPropertySource` on a `BaseRealPortWebApiAdapterTest` subclass. The other covers the five new constructor dependencies. The streamer helpers stay copied from `WeeklyRankingFlowTest`, for the reason the issue 262 note gives.
