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

Recorded 2026-11-27 from [issue 289's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/289#issuecomment-7210042973). The text below is the comment's.

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

## Issue 290: design section 4, the verification scope, and the three-app final check

Recorded 2026-11-27 from [issue 290's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/290#issuecomment-7210373376). The text below is the comment's.

Implemented as `1bce6675` on top of `d0dba7cc`, on `feat/payment-incident-follow-on`. Docs and evidence only: no file under `src/main` or `src/test` changed. `scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer` on `d0dba7cc`, started 2026-11-27T04:51:01Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 9 classes, 65 tests (29 s); commerce-api 123 classes, 1248 tests (6 min 34 s); commerce-streamer 18 classes, 68 tests (50 s); 0 failures, 0 errors, 0 skipped in all three**. The code at `d0dba7cc` is `90131aa2`'s (issue 288); only docs changed since. The check's incident run is `20261127T045326Z-5f278390`, and the `@Test` took 14.6 s in it.

### Acceptance criteria

- [x] **The three-app final check passes on a clean tree, with no skipped tests; §4 records the commit, the start time, and each app's class and test counts and duration.** [Design §4.10](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/1bce6675/docs/design/payment-incident.md#410-끝의-확인). The counts equal the last checks of week 9 (batch, streamer) and of spec 281 (api): this branch extended one `@Test` and added no class or test. No batch or streamer process was left after the run.
- [x] **The verification-scope table names, per feature, the classes that ran and separates confirmed from unconfirmed.** [§4.5](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/1bce6675/docs/design/payment-incident.md#45-기능별-검증-범위와-남은-위험): six rows (카탈로그와 관리자, 포인트·주문·PG, 이벤트와 아웃박스, 쿠폰, 주간 랭킹, 이번 사고). The six named classes are there: `OrderPaymentIntegrationTest` (63 tests in this run), `CouponIssueIntegrationTest` (12), `WeeklyRankingJobRestartTest` (12), `WeeklyRankingFlowTest` (1), `OutboxDeliveryE2ETest` (2), `OrderEventRecoveryE2ETest` (1).
- [x] **§4 says what the result does not claim.** The paragraph under the table: no production deployment, real accounts, load, or every failure mode. Every scene ran on the laptop's Testcontainers (one Kafka broker) and a WireMock PG.
- [x] **The three red checks are in §4 with the message each failed on.** §4.9: `Expected size: 20 but was: 19` (relay skips a recovered order's row), `… ["rank 1 product 1 score 17"] to contain exactly: ["rank 1 product 1 score 20"]` (recovery pinned in the next week), `Waited 30 s for 21 recorded order confirmed events. State: 20 recorded` (streamer's already-recorded check bypassed; the consumer stops, ADR 0033).
- [x] **The committed evidence directory is the final check's run, whole, with the follow-on files; the previous run's directory is removed; §3.5 gets a dated line.** [`docs/evidence/week10/20261127T045326Z-5f278390/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/1bce6675/docs/evidence/week10/20261127T045326Z-5f278390): 17 files (the 12 of §3.5 and 5 follow-on files), no `failure.json`, identical to the build output. `20261127T014458Z-e8a33044` is removed; §3.5's dated line says so, and its link now points at that run in `d0dba7cc`.
- [x] **§4 lists the limits, including the two the runbook carries and anything the pinned clock changed.** §4.7: the operator reconciles as the customer, diagnosis needs direct PG access, the runbook is not machine-checked, one kind of incident. The pinned clock reaches only commerce-api's injected `Clock`: `BaseEntity`'s `created_at`/`updated_at`, the streamer and batch child processes (`frozen_at`), the test PG and the evidence times stay real, and recovery's repeats moved to recovery + 1 s.
- [x] **Deviations from the spec are listed in §4.** §4.11: the third recovered order is the one published twice (not the first, with a class-local stub); `follow-on.json` is `observe`'s record with a `followOn` part; the runbook has four limits, not three; precondition 3 is half asserted by the test.
- [x] **The closing comment is appended to the writing material.** This section.

### Decisions made while implementing

- **The scope table lists representative classes, not all of them.** Each row has the classes that carry the feature plus the assignment's named ones. "Confirmed" is what those scenes asserted in this run; "unconfirmed" is what no scene ran.
- **The old run's link became a GitHub tree link at `d0dba7cc`.** The relative link would have pointed at the deleted directory. `check-doc-notes` reports that §3.5 line as changed (a warning), with the status cell and the §4 placeholder.
- **Found in review:** the follow-on file count (four → five), the test name's line length (164 → 168, ktlint counts the indent), and 보내다/나가다/재발행/중복 for relaying, which the glossary avoids (now 발행하다, 다시 발행하다). Left as found: 회차 for a relay round (as `order-outbox.md` uses it), and the timings as `scripts/final-check` reports them.

## Spec 286: the closing review

Recorded 2026-11-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286#issuecomment-7210970334) of [spec 286](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286). The text is the comment's, with its blob URLs.

Step 3 of week 10 is built, and its closing review is done. `scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer` on `f854f588`, a clean tree, started 2026-11-27T05:35:50Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 9 classes, 65 tests (27 s); commerce-api 123 classes, 1248 tests (6 min 24 s); commerce-streamer 18 classes, 68 tests (51 s); 0 failures, 0 errors, 0 skipped in all three**. The counts match the gauge branch's final check (commerce-api) and week 9's (commerce-batch, commerce-streamer): this step extends one `@Test` and adds no class or test. No streamer or batch process was left running. Only documents and evidence change after `f854f588`.

Tickets 287–290 are closed, each with its own comment. [Design 4](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/design/payment-incident.md#4-3단계-후속-확인과-runbook) holds the step, and the [runbook](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/runbook/week10.md) is new. No file under `src/main` changed in this spec, and there is no ADR.

### The follow-on, as the committed run shows it

The committed evidence is now this final check's incident run, [`docs/evidence/week10/20261127T053810Z-324a6dcf/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/feat/payment-incident-follow-on/docs/evidence/week10/20261127T053810Z-324a6dcf): 17 files, no `failure.json`, the four Prometheus texts untrimmed. It replaces issue 290's run `20261127T045326Z-5f278390`, because the review added an assertion to the incident test. The values are the same; only the timestamps, the PG order IDs and the hashes differ.

| What | The committed run |
| --- | --- |
| Before the follow-on | Baseline orders 1–10 and fault orders 11–20 under a clock pinned to the week of 2026-09-07. 13, 16 and 19 lost the create's response and were recovered by the customer's 대사, as in step 2 |
| Relay | One round claims all 20 outbox rows and stops after the broker's ack of the last, order 19's, before saving it. The next round sends it again. All 20 rows `PUBLISHED`, order 19's with 2 attempts |
| Counts kept apart | 21 records on the topic, 20 outbox rows, 20 purchase activities, 20 processed order confirmations. The streamer logged `RECORDED` once per order and `ALREADY_RECORDED` for order 19 |
| Purchase activities | One per order, quantity 1. `confirmed_at` is 2026-09-10T10:00Z for 13, 16 and 19 (the recovery) and 2026-09-08T10:00Z for the other 17 |
| Ranking | `NOT_PUBLISHED` before the run. After it, snapshot `2026-09-07-r1` (revision 1, `FROZEN`, 20 inputs) is `PUBLISHED`, rank 1 product 1 score 20 |
| Closing observation | Stock 0, 20 PG transactions and 20 creates, no unresolved payment, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, the owner's rows unchanged. The fault files still hash as `recovery.json` holds them (`fault.json` starts `ecb72ee0…`) |

### Where the implementation differs

[Design 4.11](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/design/payment-incident.md#411-명세와-다른-것) has four rows, all from the tickets; this review added none. The one that changes what a reader would assume: **the order sent twice is the third recovered one, not the first.** One round claims all 20 rows and saves them in claim order, so the shared spy helper, which throws on the first save, would end the round at order 1 and re-send all 20 (40 records). Only the last row saved can be left as the single repeat, so a stub inside the class throws on that row alone.

**Not proven here:** an operator reconciling without carrying the customer's `X-USER-ID`, diagnosis without reaching the PG, any check of the runbook's text, the runbook's stop conditions, a payment left processing, 누락 복구 in the incident, re-sending the first or second recovered order, and anything about deployment, real accounts, load or throughput ([design 4.7](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/design/payment-incident.md#47-한계), [4.5](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/design/payment-incident.md#45-기능별-검증-범위와-남은-위험)).

### The closing review

A two-axis review of `week10...feat/payment-incident-follow-on` at `64e4977f` (12 commits, 23 files), with one reviewer on the standards and one on this spec. Standards found no breach of a documented rule, 4 wording and doc slips and 5 possible smells. Spec found 1 requirement implemented wrongly, 1 partial and no scope creep. Each was checked against the code and decided.

#### What changed

- **`d1561f37`: the follow-on hashes the fault files again after its last write.** It took the hash as an argument to `observe`, so before `observe` wrote `follow-on.json` and its scrape, and compared only that to `recovery.json`. Recovery hashes again after its assertions; the follow-on now does the same, so story 25's "at the very end" holds.
- **`f854f588`: the runbook's stop conditions point at re-entry.** Two sentences in 5절 sent the operator to "6절", which is 복구 확인; re-entry is 7절.
- **`f854f588`: the runbook says 다시 발행되거나 다시 전달되어도.** It said only 다시 전달되다, the broker's redelivery of the same record, but the gap between the topic's records and the purchase activities comes from republishing.
- **`f854f588`: design 4 freezes a ranking period in the glossary's word**, 고정하다, where it said 얼리다, and 3.5 lists `follow-on.json` beside `recovery.json` as carrying the fault files' hashes.

#### Left as they were

- **"Re-delivered" in the writing material's copies of the 287 and 288 comments.** They are copies of posted comments; the code (`republishedOrderId`), the design and the runbook use the right word.
- **`runFollowOn`'s length, and its closing block's likeness to recovery's.** Each phase asserts its own record and reads top to bottom as its acceptance criteria.
- **`date_format(…, STORED_UTC)` in four queries.** The format is already one constant; the queries differ.
- **`(evidence, baseline, fault, ownerRowsAtStart)` passed to two phases, and `observe`'s optional parts.** Two callers in one class, and each optional belongs to one phase.
- **Strings for outbox statuses and streamer results.** They are the raw values the test reads from rows and log lines, as an outside observer would.
- **`readFollowOn` and `written`.** The first is what the test read; the second is that record read back from `follow-on.json`, which the assertions use (story 24).

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/research/week10-payment-incident-follow-on-writing-material.md#spec-286-the-closing-review).

## Draft PR body

The body of the PR from `feat/payment-incident-follow-on` into `week10`, written at the spec's closing review (2026-11-27) in `/mattpocock-skills:pr`'s shape, with the final check on `f854f588` and the comment's link, and updated after the PR's review with the final check on `a55e8448`. It makes no timing, throughput or concurrency claim; none was measured under production settings.

### Summary

Week 10 step 3. The incident test no longer stops at the order row. After recovery, the same `@Test` relays the 20 orders' outbox rows to a real broker, the real commerce-streamer records their purchase activities, commerce-batch publishes the week, and the ranking API shows the product's score 20. One order is sent twice, so the topic's records and the purchase activities are counted apart. A runbook and a per-feature table of what was and was not verified take the result.

- **Spec:** issue 286, with tickets 287–290, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/286#issuecomment-7210970334)
- **Design:** [`docs/design/payment-incident.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/design/payment-incident.md) section 4: 4.2 the follow-on, 4.3 the evidence, 4.4 the runbook, 4.5 the verification scope, 4.7 the limits, 4.9 the red checks, 4.11 what differs from the spec
- **Runbook:** [`docs/runbook/week10.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/runbook/week10.md): unresolved payments while health is UP
- **Evidence:** [`docs/evidence/week10/20261127T055722Z-c9dcca96/`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/feat/payment-incident-follow-on/docs/evidence/week10/20261127T055722Z-c9dcca96), the final check's incident run, 17 files as written
- **Writing material:** [`docs/research/week10-payment-incident-follow-on-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/payment-incident-follow-on/docs/research/week10-payment-incident-follow-on-writing-material.md), which holds every ticket's closing comment
- **No production code changed, and no ADR.** Test code and documents only; `CONTEXT.md` gains 운영자.

```text
PaymentIncidentE2ETest  (clock pinned: confirms 2026-09-08T10:00Z, recovery 2026-09-10T10:00Z)
  baseline · fault · diagnosis · recovery      as in step 2                         → baseline.json … recovery.json
+ follow-on
+   relay round 1   claims 20 rows, stops after the broker's ack of order 19, before its PUBLISHED
+   relay round 2   past the lease, sends order 19 again          → topic order-events-payment-incident: 21 records
+   commerce-streamer (bootJar)  RECORDED ×20, ALREADY_RECORDED ×1 → 20 purchase activities
+   ranking API     NOT_PUBLISHED                                 → follow-on-ranking-before-run.json
+   commerce-batch (bootJar)  weeklyRanking 2026-09-07 revision 1 → snapshot 2026-09-07-r1, 20 inputs
+   ranking API     PUBLISHED, rank 1 product 1 score 20          → follow-on-ranking.json
+   observe         stock, creates, PG transactions, owner's rows, gauge, health, fault-file hashes → follow-on.json, -health.json, -prometheus.txt
```

### Evidence

**Before:** step 2 recovered three payments and stopped at one outbox row per order. Nothing showed the recovered orders reaching the purchase activities or the weekly ranking, the clock was unpinned so a run across Monday 00:00 UTC could split the week, and the topic's records and the purchase activities were both 20.

**After:** from the committed run, values as the files hold them.

| Count | Value |
| --- | --- |
| Records on the topic | 21 (20 distinct event IDs; order 19's twice) |
| Outbox rows | 20, all `PUBLISHED`, order 19's with 2 attempts |
| Purchase activities | 20, one per order, quantity 1; 13, 16, 19 at the recovery's `confirmed_at` |
| Processed order confirmations | 20 |
| Ranking | `NOT_PUBLISHED` → `PUBLISHED` `2026-09-07-r1`, rank 1 score 20, input count 20 |

- **What recovery proved still holds:** stock 0, one create per PG order ID, 20 PG transactions, the owner's points and coupons unchanged, gauge `UNKNOWN 0.0`·`PENDING 0.0`, health `UP`, and the fault files hash as `recovery.json` holds them.
- **Red checks** (design 4.9): a relay that skips one recovered order's row fails on 19 purchase activities; a recovery pinned to the next week fails on score 17; bypassing the streamer's already-recorded check stops the consumer on the repeated event.

`scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer` on `a55e8448`, a clean tree, after the closing review's fixes and this PR's review fixes, with ktlint passing and only documents and evidence changed since:

| App | Classes | Tests | Failures, errors, skipped | Time |
| --- | --- | --- | --- | --- |
| commerce-batch | 9 | 65 | 0 | 28 s |
| commerce-api | 123 | 1248 | 0 | 6 min 28 s |
| commerce-streamer | 18 | 68 | 0 | 48 s |

The counts are unchanged from the steps before: this step extends one `@Test`. All three apps run so that week 9's restart and late-revision scenes are not skipped. The table in design 4.5 lists, per feature, the classes that ran, what this run confirmed and what is unverified. It claims a controlled local incident and passing regressions, not deployment, real accounts or load.

### Merge Danger

**Door:** two-way

Test code, evidence and documents only. A revert removes the follow-on phase, the runbook and design section 4.

**Blast Radius:** tests

- `PaymentIncidentE2ETest` now starts commerce-streamer and commerce-batch from their bootJars and needs Kafka; it takes about 13 s in the final check.
- The runbook is not checked by anything: a changed column or endpoint fails the test, not the runbook (design 4.7).

## PR 291: the review's replies

Recorded 2026-11-27. Five Devin Review threads on PR 291 (Codex found no issue); the replies below are as posted. Four were fixed in `3d02f9df` and `a55e8448`, one was left as it was.

### Re-entry drops pending follow-up orders

[Reply](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/291#discussion_r4716203746).

Right. 7절 sent every unfinished target back to 2.1절, but 2.1절 lists only unresolved payments, so an order whose payment finished and whose outbox row or purchase activity is missing never comes back. `a55e8448` limits re-entry to what 6.1절 and 6.2절 find. A gap that 6.3절 or 6.4절 finds follows that section's own next step: 누락 복구 for a missing outbox row, the purchase-activity design for a published event with no activity.

### Valid purchase rows look duplicated

[Reply](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/291#discussion_r4716203882).

Right. `purchase_activity` is one row per order and product, and the line above already says one per item. `a55e8448` makes it "같은 주문과 상품의 행이 둘이 아니다". The incident's orders have one item each, so the test's one row per order is unchanged.

### HTTP status hidden during recovery

[Reply](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/291#discussion_r4716204037).

Right. 4절 branches on 200, 202 and 404, and stop condition 1 on a lookup that is not 200, but `curl -s` printed only the body. `a55e8448` adds `-w '\nHTTP %{http_code}\n'` to the PG lookup (2.2절) and the reconcile (4절), and says the last line is the status. Both stay read-only.

### Snapshot state recorded without assertion

[Reply](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/291#discussion_r4716204198).

Right. Design 4.2 lists `FROZEN` among the assertions, but `SnapshotRow.describe()` left `state` out, so `follow-on.json` carried it unchecked. `3d02f9df` adds the state to the description and the expected line is now `2026-09-07-r1 period 2026-09-07 revision 1 state FROZEN inputs 20`. `FROZEN` is the only state commerce-batch writes (the publication row says what a period shows), so this pins the snapshot row's shape rather than a new behaviour. The final check on `a55e8448` passes with 123 classes and 1248 tests in commerce-api, and its incident run `20261127T055722Z-c9dcca96` is now the committed evidence.

### Relay exception binding rule missed

[Reply](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/291#discussion_r4716204362).

Leaving this one. The rule is about naming: a thrown exception the test keeps is held in `exception`. It does not ask a test to keep one it never reads. Here the throw only stops the relay round; the assertions that follow read the outbox rows and the topic. The repo has other unbound `assertThrows` calls, among them the same relay stop in `WeeklyRankingFlowTest`, and binding it would leave an unused variable.
