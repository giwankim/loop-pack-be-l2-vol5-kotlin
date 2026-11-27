# Week 10 payment incident follow-on: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286), then the spec's own.

## Issue 287: relaying the incident's twenty outbox rows under a pinned clock, and one purchase activity per order

Recorded 2026-11-27 from [issue 287's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/287#issuecomment-7209476967). The text below is the comment's.

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

## Issue 288: publishing the incident's week, and the follow-on evidence

Recorded 2026-11-27 from [issue 288's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/288#issuecomment-7209765569). The text below is the comment's.

Implemented as `90131aa2` on top of `43a1a253`, on `feat/payment-incident-follow-on`. `scripts/final-check --app commerce-api` on `90131aa2`, started 2026-11-27T04:15:49Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 123 classes, 1248 tests (6 min 35 s), 0 failures, 0 errors, 0 skipped**. The tree's one uncommitted change was the 운영자 entry in `CONTEXT.md`, which issue 289 commits and no test reads. No file under `src/main` changed. The check's incident run is `20261127T041746Z-8cff87b2`, and the `@Test` took 14.4 s in it.

### Acceptance criteria

- [x] **Before the run the ranking API answers `NOT_PUBLISHED` with no items.** The body is written as received to `follow-on-ranking-before-run.json`, and the assertions read that file: status `NOT_PUBLISHED`, `snapshotId` null, no items.
- [x] **After the run it answers `PUBLISHED`, snapshot `2026-09-07-r1`, one item: rank 1, the product, score 20.** commerce-batch runs once from its bootJar (`CommerceBatchProcess`): `periodStart=2026-09-07`, snapshot `2026-09-07-r1`, revision 1. The body is written to `follow-on-ranking.json` and asserted from it.
- [x] **The snapshot row's `input_count` is 20.** `follow-on.json` holds every `weekly_ranking_snapshot` row. There is exactly one: revision 1, `FROZEN`, inputs 20, with its freeze time and checksum. No revision 2.
- [x] **`follow-on.json` carries the per-order rows, the snapshot row and the four counts.** It is the phase's `observe` record with a `followOn` part. Per order, that part holds the event ID, the outbox status and attempts, and the purchase activity rows (a list, so a duplicate would show). It also holds the snapshot rows, the owner's rows, and the counts: topic records 21, outbox rows 20, purchase activities 20, processed confirmations 20. The assertions read the record that was written.
- [x] **The closing observation reads health `UP` and gauge `UNKNOWN 0.0`, `PENDING 0.0` from one scrape.** It is the same `observe` the other phases use. It writes `follow-on-health.json` and `follow-on-prometheus.txt`.
- [x] **Read again at the end: recovery's invariants hold.** The record shows stock 0, create count 1 for each of the 20 PG 주문 번호, and 20 PG transactions (20 creates, none twice, no unmatched request). It shows every order `CONFIRMED`/`SUCCESS` with the PG's transaction key. The owner's point and coupon rows equal the run's first reading. The fault files' SHA-256 equal the hashes in `recovery.json`.
- [x] **A failure in this phase writes `failure.json` with `"phase" : "follow-on"`.** Both red checks below wrote it.
- [x] **Red checks, recorded below.**
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Red checks

Neither change was committed. Each ran the class alone and was then reverted.
- **Recovery pinned in the next week** (`RECOVERED_AT` = 2026-09-15T10:00Z). The phase fails at `Expecting actual: ["rank 1 product 1 score 17"] to contain exactly: ["rank 1 product 1 score 20"]`. The written body shows score 17. The ranking assertions come right after the purchase-activity count, ahead of the per-row checks. Otherwise the week bound on `confirmedAt` would have failed first, and the score would never be seen.
- **commerce-streamer's already-recorded check bypassed** (`findByEventId` made to find nothing, and the streamer's bootJar rebuilt by the test task). The re-delivered event of order 19 reaches the "same order under another event ID" branch. The conflict stops the consumer (ADR 0033), so no second purchase activity is written. The phase fails at `Waited 30 s for 21 recorded order confirmed events. State: 20 recorded`, with partition 1 at 9 of 10 records read.

### Decisions made while implementing

- **The follow-on's record is `observe`'s record with a `followOn` part.** `observe` already writes `<phase>.json`. A second file under that name would fail create-new, so the follow-on's fields ride on `PhaseRecord`. `faultFileSha256` works the same way, and the shared mapper leaves out the `null`s, so the earlier phases' files are unchanged.
- **The ranking bodies are asserted from the written files.** Only the status is asserted on the result. The issue and spec 274's evidence rule ask for assertions on what was written. `CODING_STANDARDS.md` records this as a dated exception to the `bodyJson()` rule. The bodies are decoded as UTF-8, the API's encoding, whatever charset the response names.
- **The `@Test` keeps the spec's name, and ktlint's line length is suppressed on that function alone.** The name makes a 164-character line. The user chose the suppression over a shorter name. It is the only ktlint suppression in the repo, noted in `CODING_STANDARDS.md`.
- **`DataSource` is a new constructor dependency, for `WeeklyRankingBatchTables.reset` in `@BeforeEach`.** The ranking's request helper and the snapshot SQL repeat `WeeklyRankingFlowTest`'s and are not moved, for issue 262's reason (a different base class). Both are noted in `CODING_STANDARDS.md`.
- **Left as found in review:** the closing assertions repeat recovery's block in shape. Each phase asserts its own record, as the earlier phases do.

## Issue 289: the week 10 runbook and 운영자 in the glossary

Recorded 2026-11-27 from [issue 289's closing comment](). The text below is the comment's.

Implemented as `ff1003e1` on top of `c2ff3269`, on `feat/payment-incident-follow-on`. Docs only: no file under `src/main` or `src/test` changed, so no test ran. The pre-commit doc checks (`check-doc-links`, `check-doc-notes`) and ktlint passed.

### Acceptance criteria

- [x] **증상 names the alarm and says health stays `UP`.** §1: `sum(commerce_payment_unresolved)` staying above 0, health `UP` by design, with the per-instance `curl` for health and the Prometheus lines (`runFault`'s `UNKNOWN 3.0` under `UP`).
- [x] **진단 질의.** §2.1 is the `select` of unresolved payments joined to `orders` for `user_id`, with `pg_order_id`, amount, status, transaction key and `updated_at`. §2.2 is the PG list lookup `curl` with the PG contract's `X-USER-ID` as a placeholder. §2.3 says the log events are read from the logstash JSON by `orderId`, with a table of what each event order means. The section opens with the DB as the authority and the gauge as the alarm, and says what a restart does to `sum`.
- [x] **조치 전제.** §3 lists the three: the cause has stopped, the target list was read from the DB and frozen, and the PG shows one matching `SUCCESS`.
- [x] **중단 조건.** §5 lists the spec's six conditions. The operator changes nothing and asks the PG, including when the PG reports a failure: no SQL ends the payment or releases the stock reservation.
- [x] **Recovery is the customer's reconcile `curl` with the owner's ID, once per target.** §4, with what 200/`CONFIRMED`, 202, 200/`PAYMENT_FAILED` and 404 mean.
- [x] **복구 확인.** §6 covers the order and payment rows, no added create (the PG lookup still shows one transaction, and no `pg.create.*` after the reconcile), the outbox row, and the purchase activity. It names `order-events.recovery.order-ids` for a missing outbox row.
- [x] **재진입.** §7 restarts from §2.1, refreezes the list, repeats only for targets still unresolved, and cites `runRecovery`'s repeat window (recovery + 1 s: second reconcile and 알림, rows, events and PG requests unchanged, another user 404).
- [x] **No SQL that writes and no command that creates a PG transaction.** Every SQL is a `select`. The only command that changes a payment is the reconcile.
- [x] **The stated limits.** §8: the operator reconciles as the customer and nothing records it was them; diagnosis needs direct PG access; the text is not machine-checked. A fourth limit says the incident test ran one kind of incident only.
- [x] **Every domain word follows `CONTEXT.md`, its Avoid lists included.** 중계 (not 릴레이), 발행하다, 주문 확정 이벤트, 삭제되지 않은, 기록하다.
- [x] **The pre-commit doc checks pass.**
- [x] **The closing comment is appended to the writing material.** This section.

### Decisions made while implementing

- **Each command says how far the test ran it.** The test does not shell out. It sends the same queries and requests through JDBC and HTTP clients, and some of its queries are narrower or wider than the runbook's: `unresolvedOrderIds` selects only `order_id`, `outboxRowCounts` counts all 20 orders without an `order_id` filter, and `purchaseActivityRows` reads the whole table. The runbook says which part the test exercised rather than "the same query".
- **Precondition 3 is half asserted by the test.** `runDiagnosis` asserts the lookup's status only. The amount and PG 주문 번호 matching is read from `runRecovery`'s reconcile, which applied the transaction without a conflict. The runbook says so.
- **누락 복구 is cited from week 7.** The incident test never misses an outbox row, so the start-up property is pinned by `OrderEventRecoveryRunnerTest`, and the runbook gives the `java -jar … --order-events.recovery.order-ids=` form.
- **The runbook links 운영자 to the glossary instead of defining it again.** Design §3 gets two dated lines: its local definition now points at the glossary, and the runbook uses 사고 and 복구, the assignment's section words, without adding them to the glossary.
- **Found in review:** an Avoid word (살아 있는 for a not-deleted row), a false "every phase ends with `observe`" (diagnosis doesn't), two "same query" claims, and a bare 이벤트 for 주문 확정 이벤트. All fixed before the commit.
