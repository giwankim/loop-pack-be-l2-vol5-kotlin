# Week 10 in-memory unresolved-payment gauge: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 281](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/281), then the spec's own.

## Issue 282: raise and lower commerce.payment.unresolved in memory through a payment status change port

Recorded 2026-11-27 from [issue 282's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/282#issuecomment-7207132340). The text below is the comment's.

Implemented as `919e93bf` on top of `ddbe2bf1`. `scripts/final-check --app commerce-api` on `919e93bf`, a clean tree, started 2026-11-27T01:04:55Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 125 classes, 1253 tests (6 min 9 s), 0 failures, 0 errors, 0 skipped**.

### Acceptance criteria

Every scene of `UnresolvedPaymentGaugeE2ETest` reads the Prometheus text over real HTTP and asserts the change from its own first reading (`changeSince`).

- [x] **The text holds both series while health is UP.** The first scene. The `# HELP` line says what it counts, "since this process started" and "can be negative", and "aggregate with sum".
- [x] **A lost create response adds 1 to `UNKNOWN` while health is UP; the reconcile that finds the PG 거래 takes it back.** The PG's create count stays 1 and no request missed a stub.
- [x] **An accepted create still 처리 중 leaves `UNKNOWN` where it was and adds 1 to `PENDING`.** The scene also reconciles while the PG still says `PENDING` (an `Unchanged` TX B, which moves nothing), then with `SUCCESS` (back to the start).
- [x] **A create rejected before creation leaves both series where they started.** TX A's +1 and the rejection's −1.
- [x] **A replayed confirm with the same 멱등 키 adds nothing.** Read after the first confirm, unchanged after the replay; the reconcile then takes the first confirm's +1 back.
- [x] **A TX B that rolls back leaves the number as TX A left it.** `failTxBOnceAfterItsWrites`, the precedent of `OrderPaymentLogEventTest`: 500, `UNKNOWN` +1. The reconcile then applies the same transaction and takes it back.
- [x] **A 결과 미확인 payment this process never saw start, reconciled, takes `UNKNOWN` down by 1, below 0 when it started at 0.** `startPaymentWithoutConfirm`, a new base helper, saves the order as payment pending and starts the payment through `PaymentRecorder`, the payment's provided port. In this class's context the scene starts at 0, because every other scene asserts that it ends where it began; so the text carries −1.
- [x] **The `NaN` scene is gone. No scene reads the gauge from a bean, counts calls to the port with a mock, or waits for a refresh.**
- [x] **No test-only reset method in production code and no `@DirtiesContext`.**
- [x] **`PaymentIncidentE2ETest` still passes with its scraped `UNKNOWN` 0, 3, 0.** Its context (the real-port base with `@EnableTestPg`, no spy) is shared with no other class, so no residue reaches it and it keeps the absolute values. Its `observe` no longer polls: one scrape must read the value.
- [x] **Red checks.** See below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Red checks

Not committed; each source was restored before the commit, and every run is of the whole `UnresolvedPaymentGaugeE2ETest`.

| Change | Scenes that failed |
| --- | --- |
| The TX A notification moved inside the transaction (into `OrderPaymentSagaSteps.startPayment`, after `paymentRecorder.start`) | `a TX A that rolls back after starting the payment leaves both series where they started`: `UNKNOWN` +1 instead of 0. No scene caught it before, so this scene was added. |
| The TX B notification also sent for a result that is not `Changed` (`statusChanged(null, payment.status)`) | `an accepted create still pending at the PG …`: `PENDING` +2 instead of +1 after the still-pending reconcile. That reconcile step was added for this check: no scene had an `Unchanged` TX B. |
| The −1 for `from` dropped | Six scenes: the lost create, the pending create, the rejection, the replay, the rolled-back TX B, the never-seen payment. |
| The number clamped at 0 (`updateAndGet { maxOf(0, it - 1) }`) | `reconciling an unknown payment this process never saw start takes unknown down by one`: change 0 instead of −1. |

The first run of the TX A check passed, which exposed a scene green for the wrong reason: without a reserved PG order ID, `PaymentRecorder.start` threw before the payment existed, so the 500 came from the test PG's generator. The scene now names a PG order ID and ends with a retry that only matches the stubbed second ID if the failed TX A took the first.

### Decisions made while implementing

- **The adapter alone decides what is counted.** `PaymentStatusChangeListener.statusChanged(from, to)` says nothing of gauges. `UnresolvedPaymentGauge` keeps one `AtomicLong` per unresolved status and ignores the others. It registers each with Micrometer's `obj, fn` form, which holds the object weakly; the singleton bean's map holds it strongly.
- **`adapter.monitoring` is a driven adapter now.** `HexagonalArchitectureTest` lets it reach the domain (for `PaymentStatus`) and `application.payment.required`, and nothing else of the application.
- **The two call sites are where the saga already stands outside the transactions:** right after TX A returns a new payment, and in `applyAndLog` after `payment.apply.committed` when the result is `Changed`.
- **Taken from issue 283 because this ticket forces them:** the gauge's `@Scheduled` refresh, its `@EnableScheduling`, `@ConditionalOnProperty`, `NaN` and the failed-count WARN ("registered in every profile", "no longer reads the scheduled count", "the `NaN` scene is gone"); the relay KDoc's sentence about the gauge's scheduling, which became false; and `UnresolvedPaymentGaugeDisabledTest`, which asserted the gauge absent under `test`. Issue 283 is left with the count, its Finder method and value type, the index, the two properties (in `application.yml` and the real-port base), `UnresolvedPaymentGaugeEnabledPropertyTest` and ADR 0014's line. The KDocs of the base and the property test say the properties no longer do anything.
- **Review fixes before the commit.** The scene first injected `PaymentRecorder` to prepare the payment, which `CODING_STANDARDS.md` forbids (§Fixture, §도우미), so the base got `startPaymentWithoutConfirm`, named for its bypass. Three passages of `CODING_STANDARDS.md` still described the scheduled gauge and got dated notes. `PaymentIncidentE2ETest`'s comments spoke of a refresh.
- **The gauge's context is one more cached context,** as before: the real-port base, `@EnableTestPg` and the `OrderPaymentSagaSteps` spy in place of the `PaymentQueryService` spy.

## Issue 283: remove the scheduled count of unresolved payments, its index and its properties

Recorded 2026-11-27 from [issue 283's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/283#issuecomment-7207486136). The text below is the comment's.

Implemented as `a4c0f519` on top of `8ae899fd`. `scripts/final-check --app commerce-api` on `a4c0f519`, a clean tree, started 2026-11-27T01:29:52Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 123 classes, 1248 tests (6 min 22 s), 0 failures, 0 errors, 0 skipped**. Against issue 282's run (125 classes, 1253 tests) the difference is the deleted classes: `PaymentFinderTest` (3 tests) and `UnresolvedPaymentGaugeEnabledPropertyTest` (2).

### Acceptance criteria

- [x] **No production code or configuration mentions the count, its value type, the two properties or the index.** `PaymentFinder.countUnresolved`, its implementation in `PaymentQueryService`, `UnresolvedPaymentCount`, `PaymentRepository.countByStatusIn` and its row type `PaymentStatusCount` are gone. The `payment.unresolved-gauge.*` documents are gone from `application.yml`'s default and `test` sections. `git grep` finds none of them under `apps/`.
- [x] **The count scenes and the EXPLAIN scene, `UnresolvedPaymentGaugeDisabledTest` and `UnresolvedPaymentGaugeEnabledPropertyTest` are deleted.** `PaymentFinderTest` held only those three scenes, so the class went. `UnresolvedPaymentGaugeDisabledTest` already went in issue 282.
- [x] **A context without the relay registers no scheduled task.** `@EnableScheduling` is on `OrderEventRelayScheduler` alone (the gauge's went in issue 282), and its KDoc no longer mentions the gauge. The four `OrderEventRelay*Test` classes are unchanged and pass.
- [x] **The schema Hibernate creates under test has no index on the payment status.** `PaymentRepositoryTest`'s index scene now reads `the payment table has no non-unique index`. It was flipped first and failed with `Expecting empty but was: ["IDX_PAYMENT_ON_STATUS:1:status"]`, then passed once the entity's `@Table` lost the index.
- [x] **The drop-index SQL file exists beside the file that added it.** `docs/migrations/2026-11-27-drop-payment-status-index.sql` (`ALTER TABLE payment DROP INDEX IDX_PAYMENT_ON_STATUS, ALGORITHM = INPLACE, LOCK = NONE`); `2026-11-26-payment-status-index.sql` stays.
- [x] **ADR 0014 carries the dated withdrawal line.** A 2026-11-27 line under the 2026-11-26 memo, which stays.
- [x] **The gauge scenes and `PaymentIncidentE2ETest` pass unchanged.** Neither file is in the diff.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This comment.

### Decisions made while implementing

- **Two test-base helpers went with `PaymentFinderTest`.** `confirmOrderRejectedBeforeCreate` and `confirmOrderLosingCreateResponse` came in with the count (PR 273) for its one-payment-per-status preparation, and nothing else called them. `confirmOrder` is back to its body before PR 273. `TestPg.rejectsBeforeCreate` and `losesCreateResponse` stay: many scenes call them.
- **The real-port base sets no property now.** Its KDoc says instead that the gauge moves only after a commit, which is why its scenes leave the test transaction.
- **`CODING_STANDARDS.md`:** the bullet on the gauge's property test went, with a dated note on the relay's trigger-test bullet, whose shape it copied. Dated notes record `PaymentFinderTest`'s removal where it was named as a `JdbcTemplate` user and as the helpers' first caller, and the properties' removal in the real-port base's bullet.
- **Left for issue 284:** `docs/design/payment-incident.md` still describes the scheduled count, the index, the switch and `PaymentFinderTest` as current; the rewrite of its section 2 and the evidence rerun are that ticket's.
- **Review.** Standards and spec reviews found no hard violation or missing requirement. Fixed before the commit: the index scene's KDoc said the foreign key had an index of its own (it reuses `UK_PAYMENT_ON_ORDER_ID`), and the real-port base's KDoc still spoke of "writes counted".

## Issue 284: describe the in-memory gauge in the design doc and re-record the incident evidence

Recorded 2026-11-27 from [issue 284's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/284#issuecomment-7207757222). The text below is the comment's.

Implemented as `d275387e` on top of `4c65ad3f`. Documents and evidence only; no file under `apps/` changed. `scripts/final-check --app commerce-api` on `4c65ad3f`, a clean tree, started 2026-11-27T01:42:39Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 123 classes, 1248 tests (6 min 57 s), 0 failures, 0 errors, 0 skipped**. `4c65ad3f`'s code is `a4c0f519`'s (issue 283); the docs were written in the working tree while it ran, and no file Gradle reads changed.

### Acceptance criteria

- [x] **Section 2 nowhere says the value comes from the payment rows, a refresh interval, `NaN` or `max`, except inside the dated paragraph.** [`payment-incident.md` 2.1](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/d275387e/docs/design/payment-incident.md#21-지표) opens with the one dated paragraph: what the scheduled count was, what hung on it, why it went, and a link to the old section 2 at `ddbe2bf1` for the removed scenes, red checks and spec differences. The table now gives the source (this process's memory), the observation scope (what this process has seen since it started), the refresh period (none: the number moves before the response returns) and how to read it (`sum`). 2.2 is now the two call sites in the saga.
- [x] **The three new limits and the step 3 instruction are in the design doc.** 2.6: a restarted instance starts at 0; an instance can be negative; so the `sum` under-reports after a restart by the payments unresolved at that moment, and the gap stays after they resolve. The scheduler-thread, `deleted_at` and ADR 0014 limits are gone. 2.11 ends with the instruction for step 3: the runbook takes the database query on the payment rows (`payment.status in ('UNKNOWN', 'PENDING')`, the query step 2's recovery reads its targets with) as the authority for "how many and which", and `sum(commerce_payment_unresolved)` as the alarm.
- [x] **`docs/evidence/week10/` holds exactly one run directory, produced on this branch's code, and section 3 cites its name, times and values.** [`20261127T014458Z-e8a33044`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/tree/d275387e/docs/evidence/week10/20261127T014458Z-e8a33044), the final check's incident run: twelve files, no `failure.json`. `20261126T135338Z-47f59811` is deleted. Section 3.5 cites the run, its times (baseline 01:44:58.933Z, fault .672Z, hypotheses .686Z, lookups .698Z/.703Z/.706Z, recovery .804Z) and its values; `fault.json`'s SHA-256 starts `4ba0993e`.
- [x] **The recorded gauge values are `UNKNOWN` 0, 3, 0 and `PENDING` 0 throughout, with health UP.** In the three records and the three Prometheus texts as fetched; the texts carry the new `# HELP`. The lost orders are 13, 16, 19 as before; only times, PG order IDs, `# HELP` and hashes differ from the old run.
- [x] **The design doc records the final check.** 2.9, a dated bullet: commit, start, timings, counts, and the arithmetic from step 2's 126 classes / 1250 tests (282: one gauge scene and `UnresolvedPaymentGaugeDisabledTest` out, five scenes in, 125/1253; 283: `PaymentFinderTest` and `UnresolvedPaymentGaugeEnabledPropertyTest` out, 123/1248).
- [x] **The doc checks of the pre-commit hook pass.** `check-doc-notes` reports only CHANGED lines (the rewrite of section 2 and the run record of 3.5, deliberate) and every section reference resolves; `check-doc-links` passes.
- [x] **The closing comment is appended to the writing material.** This comment. Spec 281 stays open for `docs/agents/spec-closing.md`.

### Decisions made while implementing

- **The final check came first.** Its incident run is the committed evidence, so one run answers "produced on this branch's code", the section 3 record and the 2.9 counts.
- **Sections 1 and 3 got dated notes, section 2 a rewrite.** A new 1.3 lists tickets 282–284, and 1.1 has the branch as stage 1′. Section 3 keeps its text, with notes where the gauge changed it: 3.2 (no polling; absolute 0/3/0 because no other class shares that context), 3.6 and 3.8 (the count after a restart), 3.11 and 3.13 (pointers to 2.9 and 2.11).
- **The old section 2's history moved behind a pinned link.** The AC leaves no room for the removed scenes, red checks 268/269 or the three spec-difference rows, so the dated paragraph links `blob/ddbe2bf1`. 2.8 now has issue 282's four red checks and 283's index flip beside step 1's two that still hold. 2.10 adds five rows for spec 281.
- **Review.** Neither axis found a missing requirement. Fixed before the commit: 알림/알리다 named the port call, which `CONTEXT.md` avoids because 알림 is the PG callback; 조각 named the spec's slices, which `CODING_STANDARDS.md` keeps for `Slice` pages; a leftover clause in 2.4 said the management server reads committed rows; the scene table claimed the raw text carries `-1.0`, while the scene asserts only the change of −1.
