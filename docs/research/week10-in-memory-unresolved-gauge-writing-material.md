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
