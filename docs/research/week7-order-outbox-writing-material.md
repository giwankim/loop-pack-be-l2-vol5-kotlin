# Week 7 order outbox: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one ticket of [spec 191](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/191)'s closing comments.

## Issue 192: a first confirmation leaves one outbox row

Recorded 2026-11-02 from the closing comment of [issue 192](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/192). The comment carries full blob URLs where this section links files relatively.

Implemented on `worktree-agent-a8eefc4d14c229e75`, cut from the integration tip `3c5c638`, as `b47c84b`; the commit after it starts the writing material with this comment. `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check`: **82 classes, 953 tests, 0 failures, 0 errors, 0 skipped**, up from the 79 classes and 943 tests week 6's writing material records. This commit adds 3 classes and 10 tests.

The decisions are [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md) rules 1 and 3–8 and sections 2–4 of the [order outbox design](../design/order-outbox.md). The code is:

- [`OrderEventOutbox`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/OrderEventOutbox.kt) and [`OrderEventOutboxStatus`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/OrderEventOutboxStatus.kt): the entity and its status, in the order domain
- [`OrderEventOutboxRepository`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/required/OrderEventOutboxRepository.kt): `save`, `findByEventId` (nullable) and `count`
- [`OrderEventSerializer`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/required/OrderEventSerializer.kt): the port and `SerializedOrderEvent`
- [`JacksonOrderEventSerializer`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/JacksonOrderEventSerializer.kt): the implementation, beside `OrderConfirmedMessage`
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt): the write, in `applyResult` through a private `leaveInOutbox`
- [`OrderEventOutboxTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventOutboxTest.kt), [`OrderEventOutboxRepositoryTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/required/OrderEventOutboxRepositoryTest.kt) and [`OrderEventOutboxImmutabilityTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/required/OrderEventOutboxImmutabilityTest.kt): the scenes
- [`OrderFixtures`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/order/OrderFixtures.kt): `createOrderEventOutbox`
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): a clause on the repository test that commits

### Landed

Two commits on top of `3c5c638`:

- [`b47c84b`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/b47c84b) is the entity, the ports, the adapter, the write, the tests and the standards clause.
- The commit after it creates the writing material with this comment as its first section.

### Acceptance criteria

- [x] **A confirmation whose create answers success leaves one row, read in a new transaction, with every event column right.** `OrderEventOutboxTest`, on `BaseCommittingApplicationServiceTest` with the test PG. `count()` is 1 in the new transaction. `event_id` is `order-confirmed:v1:{orderId}`, `order_id` the order's, `event_type` `OrderConfirmed`, `schema_version` 1, `message_key` the order ID's string.
- [x] **Its status is `PENDING`, `attempt_count` 0, `next_attempt_at` the pinned clock's instant, and `published_at` and `last_error` empty.** Compared with the literal `2026-10-27T01:02:03.123456Z`.
- [x] **The payload equals the expected string exactly, field order included.** The order lists the pricier product first, and the items come out in ascending product ID order:
  ```
  {"orderId":<id>,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[{"productId":<cheaper>,"quantity":2},{"productId":<pricier>,"quantity":1}]}
  ```
- [x] **The stored string reads back as `OrderConfirmedMessage.Payload` equal to the original, for `…123456Z`, `…100Z` and `…03Z`.** A parameterized scene with the `JsonMapper` bean. Each case also compares the whole stored string, so the instant's written form is pinned too.
- [x] **Repository tests on `BaseRepositoryTest`.**
  - A duplicate `event_id` is refused: `DataIntegrityViolationException`, root cause naming `UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID`.
  - Invalid JSON is refused: the same exception, root cause naming `CK_ORDER_EVENT_OUTBOX_PAYLOAD`.
  - Changing a managed entity's event columns by reflection and flushing leaves the values re-read in a new transaction unchanged. The scene changes all six event columns, and asserts that the managed field really did change before it flushes. That run logged no `update order_event_outbox` statement.
- [x] **`HexagonalArchitectureTest` passes with its rules unchanged.** 7 tests; the file is not in the diff.
- [x] **Red check, a local edit never committed: removing `updatable = false` from the payload turns the immutability test red.** Below.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** Run from `cleanTest`, with `BOOTSTRAP_SERVERS` unset, read by `scripts/test-counts`: 82 classes, 953 tests, 0 failures, 0 errors, 0 skipped. `OrderEventOutboxTest` 5, `OrderEventOutboxRepositoryTest` 4, `OrderEventOutboxImmutabilityTest` 1. `OrderEventPublishingTest` (15) and `OrderPaymentIntegrationTest` (63) still pass with week 6's publish beside the write. The pre-commit hook's tree-wide `ktlintCheck` passed.
- [x] **The closing comment starts `docs/research/week7-order-outbox-writing-material.md`, in the shape of week 6's writing material.** It is that file's first section.

### Red checks

The mutant was a local edit, run and reverted; it is not committed.

- **`updatable = false` removed from the payload: the ticket's red check.** `OrderEventOutboxImmutabilityTest` failed at its payload assertion, the only one of the six columns the edit released:
  ```
  org.opentest4j.AssertionFailedError:
  expected: "{"orderId":12345,"confirmedAt":"2026-10-27T01:02:03.123456Z","items":[]}"
   but was: "{"orderId":99999}"
  ```
  The flush sent `update order_event_outbox set attempt_count=?,deleted_at=?,last_error=?,next_attempt_at=?,payload=?,published_at=?,status=?,updated_at=? where id=?`, with `payload` as the only event column in it. The other payload is valid JSON on purpose, so the check constraint can't refuse the `UPDATE` and turn the test red for another reason.
- **The TDD reds.** `OrderEventOutboxTest` first failed to compile. With the entity and repository but no write, all five of its cases failed. They passed once the serializer and the write were in.

### Decisions made while implementing

- **`status` is a real `varchar(16)`.** The design's table says varchar, but `@Enumerated(STRING)` on MySQL gives the `enum ('PENDING','PUBLISHED')` that every other status column in the repository has. `@JdbcTypeCode(SqlTypes.VARCHAR)` gave a varchar with Hibernate's own unnamed `check (status in (...))`, a third constraint the design doesn't have. `columnDefinition = "varchar(16)"` gives what the table says, with exactly two constraints, so a value step 2 adds needs no schema change. This differs from the other entities on purpose, and the field's KDoc says so.
- **The port has `save`, `findByEventId` and `count`.** No production code reads yet. The two reads are what the scenes need: `count` sees every row, so "one row" and "no row" don't depend on knowing an event ID. Step 2 adds its own query with its index.
- **The write is a private function in `OrderPaymentSagaSteps`.** ADR 0034 rule 1 rules out a wrapper bean, and this is not one. `applyResult` stays readable, and the transaction is the step's.
- **The event is built once and goes to both the row and week 6's publisher.** The row is saved first. Either failure rolls TX B back, and the publisher's relay only runs after a commit.
- **The immutability scene has its own class.** "Re-read in a new transaction" needs a commit, which the base's rolled-back test transaction can't give. A method-level exit doesn't help either: an `@AfterEach` that deletes committed rows would wait on the uncommitted inserts of the class's other scenes, whose test transaction is still open. So `OrderEventOutboxImmutabilityTest` leaves the transaction at class level, as `BaseCommittingApplicationServiceTest` does, and deletes what it committed.
- **All six event columns are changed, not only the payload.** The ticket's red check names the payload, and the scene covers the rest of rule 7.
- **Each refusal names its constraint**, so a duplicate can't pass for an invalid payload or the other way round.
- **Two scenes beyond the criteria.** The next attempt time is cut to micros (`…123456789Z` is held and re-read as `…123456Z`; MySQL would round it up), and a deleted row is a missing row, which the `BaseEntity` convention asks of every root.
- **No foreign key to `orders`.** Design section 2 names two constraints and no key, so `scalar-foreign-keys.sql` is untouched. It is why `createOrderEventOutbox` needs no saved order.
- **The implementation is named `JacksonOrderEventSerializer`**, technology first, like `SpringEventOrderEventPublisher`.

### Review

No review ran on this change: the ticket lists none. The reasoning above is the implementer's own.

### Left for later

- **Issue 193 (the row rules).** `OrderEventOutboxTest` holds the first confirmation only. Its helper `confirmLeavingARow` asserts one row, so the other paths and the scenes with no row need their own setup. `OrderEventPublishingTest` has them to move.
- **Issue 194 (atomicity).** The identifier is `IDENTITY`, so the row's `INSERT` goes to MySQL at `save`, not at a flush. The design's "저장 뒤" scene with `failTxBOnceAfterItsWrites` still fails after the insert, but the insert was sent before that flush rather than inside it. `count()` reads the rows after the rollback. `OrderEventOutboxRepository` is the bean to spy for the "저장 전" scene.
- **Issue 195 (removing week 6's path).** `orderEventPublisher` and its call in `applyResult` go, and so does the paragraph of `applyResult`'s KDoc that says the publish stays beside the row.
- **Step 2.** The immutability scene is where to read the event columns again after a state change. `OrderEventOutboxStatus` is open for `BLOCKED`, with no schema change.

## Issue 194: a failed outbox write rolls the confirmation back, and a reconcile recovers it

Recorded 2026-11-02 from the closing comment of [issue 194](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/194). The comment carries full blob URLs where this section links files relatively.

Implemented on `worktree-agent-a6b28654a11f7f137`, cut from the integration tip `09392b4`, as `f753ac8`; the commit after it appends this comment to the writing material. `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check`: **83 classes, 955 tests, 0 failures, 0 errors, 0 skipped**, up from 82 and 953 on `09392b4`. This commit adds 1 class and 2 tests. No main code changed: the write from issue 192 needed no fix.

The decisions are [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md) rule 2 and sections 5 and 8 of the [order outbox design](../design/order-outbox.md). The code is [`OrderEventOutboxAtomicityTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventOutboxAtomicityTest.kt), on `BaseCommittingApplicationServiceTest` with the test PG. Its name matches the assignment's `*OutboxAtomicityTest` filter.

The failures are controlled injections, not OS kills, and no test overwrites a status to PENDING.

### Correction to the spec and the design

Spec 191 and design section 8 say the flush in `failTxBOnceAfterItsWrites` contains the insert. It does not. The row's ID is `IDENTITY`, so its `INSERT` goes to MySQL at `save`, before that flush. The scene still fails after the insert, which is how it is worded here. Each scene captures the statements its confirm sent, so the side of the insert it failed on is asserted: 0 `insert into order_event_outbox` before, 1 after. The design doc is not edited here. Its dated note belongs to issue 196.

### Acceptance criteria

- [x] **After each failure, read in a new transaction: order 결제 중, payment 결과 미확인, stock as reserved, 0 rows, 1 PG create.** Both scenes, below. The payment's transaction key is also still null.
- [x] **Each scene then reconciles: order CONFIRMED, payment SUCCESS, the reservation consumed once (stock unchanged, nothing released), 1 row, still 1 PG create.** Both scenes. The stock stays 3, neither released to 5 nor reduced to 1. The row is found by its event ID and is `PENDING`. The reconcile made 1 lookup, and `unmatchedRequests()` is empty.
- [x] **Red check, a local edit never committed: running the write in `REQUIRES_NEW` turns the scenes red.** One of the two, not both. See the deviation below.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** Run from `cleanTest`, read by `scripts/test-counts`: 83 classes, 955 tests, 0 failures, 0 errors, 0 skipped. `OrderEventOutboxAtomicityTest` 2, `OrderEventOutboxTest` 5, `OrderPaymentIntegrationTest` 63, `HexagonalArchitectureTest` 7.
- [x] **The closing comment, with one run's values, is appended to `docs/research/week7-order-outbox-writing-material.md`.**

### One run's values

Read with `scripts/scene-lines OrderEventOutboxAtomicityTest` from that run. The order ID is 1 in both because the base truncates the tables between scenes.

```
Atomicity, after the insert: outbox inserts sent 1; after the failure: order 1 PAYMENT_PENDING, payment UNKNOWN key null, stock 3, rows 0, creates 1, lookups 0; after the reconcile: order 1 CONFIRMED, payment SUCCESS key 20261102:TR:194a7c, stock 3, rows 1, creates 1, lookups 1
Atomicity, before the insert: outbox inserts sent 0; after the failure: order 1 PAYMENT_PENDING, payment UNKNOWN key null, stock 3, rows 0, creates 1, lookups 0; after the reconcile: order 1 CONFIRMED, payment SUCCESS key 20261102:TR:194a7c, stock 3, rows 1, creates 1, lookups 1
```

Stock 5, quantity 2, amount 2,000.

### Red checks

Local edits to `leaveInOutbox`, run with `--tests '*OutboxAtomicityTest'` and reverted; none is committed. They ran before the scenes' last rename and line wrapping, which changed no assertion.

- **The write in a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`: the ticket's red check. 1 of 2 scenes red.**
  - After the insert: red at the row count of the rolled-back state. The row committed on its own while the confirmation rolled back:
    ```
    org.opentest4j.AssertionFailedError:
    expected: 0L
     but was: 1L
    ```
  - Before the insert: **stayed green.** The stub throws inside the new transaction, nothing is written, and the exception still leaves through TX B and rolls it back, so everything this scene can see is the same.
- **The same `REQUIRES_NEW`, with the write's failure also swallowed by `runCatching`: 2 of 2 red.** This is the usual reason to reach for `REQUIRES_NEW`, that a failed event must not fail the confirmation.
  - Before the insert: `Expected org.springframework.dao.DataAccessResourceFailureException to be thrown, but nothing was thrown.`
  - After the insert: `expected: 0L but was: 1L`, as above.

So the scene before the insert catches a swallowed failure, and the scene after it catches a row that commits on its own.

### Deviation from the ticket

The criterion says `REQUIRES_NEW` turns "the scenes" red. Plain `REQUIRES_NEW` turns only the scene after the insert red. No assertion can change that, because that mutant behaves correctly when the save throws before writing anything. I kept the scene as the ticket specifies and added the second mutant to show what it does catch.

### Decisions made while implementing

- **Each scene asserts the outbox inserts it sent, with `captureSql`.** Beyond the criteria. Without it, nothing shows that "before" and "after" are what the names say, given the correction above.
- **One INFO line per scene**, after its assertions, as the earlier handoff scenes do, for `scripts/scene-lines`.
- **`failTxBOnceAfterItsWrites` is unchanged.** Its KDoc says it flushes the writes, which stays true of the order and the payment.
- **The class has its own context**, because of its two `@MockkSpyBean` fields, the repository and `OrderPaymentSagaSteps`.
- **No `CODING_STANDARDS.md` change.** Spying a repository, spying TX B's bean and `captureSql` are already there.

### Review

No review ran on this change: the ticket lists none. The reasoning above is the implementer's own.

### Left for later

- **Issue 195.** The scenes never mention week 6's publisher, so removing it needs no edit here. Until then, each scene's reconcile also publishes to the test broker through week 6's relay, which these scenes don't read.
- **Issue 196.** Design section 8's "flush에 insert가 들어 있다" needs a dated note with the correction above.

## Issue 193: every other path to confirmation leaves the right number of rows

Recorded 2026-11-02 from the closing comment of [issue 193](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/193). The comment carries full blob URLs where this section links files relatively.

Implemented on `worktree-agent-a1a643b4b2d3ff3b8`, cut from the integration tip `09392b4`, as `1ff26d9`, then merged with issue 194's `d6067a9` as `c2ba8b4`; the commit after that appends this comment to the writing material. `scripts/final-check --app commerce-api` on `c2ba8b4`, with `BOOTSTRAP_SERVERS` unset: **83 classes, 966 tests, 0 failures, 0 errors, 0 skipped**, up from 83 and 955 on `d6067a9`. This commit adds 11 tests. No main code changed: the write from issue 192 needed no fix.

The decisions are section 3 of the [order outbox design](../design/order-outbox.md) and [ADR 0034](../adr/0034-order-confirmed-is-left-in-an-outbox-row-by-tx-b.md) rule 7. The code is [`OrderEventOutboxTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventOutboxTest.kt). `OrderEventPublishingTest` stays in place for issue 195 to delete.

### Acceptance criteria

- [x] **A customer reconcile that first confirms the order leaves one row, and reconciling again leaves no other.** The scene asserts no row after the pending confirm, one row after the first reconcile and one after the second. One lookup.
- [x] **The PG's callback that first confirms the order leaves one row, and a duplicate callback leaves no other.** Same shape, one lookup.
- [x] **A duplicate callback racing the first one leaves one row.** The first callback pauses holding the order, and the duplicate doesn't finish within `LOCK_WAIT_PROBE`. Two lookups: both reached a TX B.
- [x] **A create answer arriving after a callback confirmed the order leaves one row.** One row right after the callback, read from inside the PG-call spy, and one after confirm's TX B.
- [x] **Each create answer that leaves the order unconfirmed leaves no row, with the publishing test's parameter set.** `pending`, `lost`, `failed`, `rejected` and `conflicting`, with the same order and payment statuses.
- [x] **A conflict and a rejection before creation leave no row.** `conflicting` and `rejected` in that set, and a scene of its own for a conflict that says success (below).
- [x] **Two confirmed orders leave two rows with different event IDs and message keys.** Each row is found by its own order's event ID.
- [x] **Scenes assert rows only, and need no quiet wait on a broker.** No `TopicReader` and no wait: TX B has committed or rolled back by the time the port returns.
- [x] **Red check, a local edit never committed: writing the row on `Unchanged` as well turns the duplicate scenes red.** Two of the four, not all. See the deviation below.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** Read by `scripts/test-counts`: 83 classes, 966 tests, 0 failures, 0 errors, 0 skipped. `OrderEventOutboxTest` 16, up from 5. `OrderEventOutboxAtomicityTest` 2, `OrderEventPublishingTest` 15, `OrderPaymentIntegrationTest` 63, `HexagonalArchitectureTest` 7.
- [x] **The closing comment is appended to `docs/research/week7-order-outbox-writing-material.md`.**

### Deviation from the ticket

The ticket's red check says writing on `Unchanged` turns "the duplicate scenes" red. It turns the racing callback and the late create answer red. The two sequential duplicates stay green, because a repeated reconcile or callback finds the order finished and returns before any lookup or TX B (`checkLookupNeeded`). They never reach `Unchanged`. Mutant 2 below confirms that this is the reason.

### Red checks

Local edits, run with `--tests '*OrderEventOutboxTest'` and reverted; none is committed. All 11 new scenes passed on their first run against issue 192's write, so these mutants are their only reds. Counts are out of the class's 16 tests.

1. **The row on `Unchanged` as well: the ticket's red check. 2 red.** The racing callback and the late create answer. Neither fails on a count of 2: the unique constraint refuses the insert first, so the failure is the exception from the second TX B.
   ```
   org.springframework.dao.DataIntegrityViolationException: could not execute statement [Duplicate entry 'order-confirmed:v1:1' for key 'order_event_outbox.UK_ORDER_EVENT_OUTBOX_ON_EVENT_ID']
   ```
2. **Mutant 1, and a finished order also goes to the lookup and TX B. 4 red.** The same two, plus the sequential reconcile and callback scenes, with the same exception.
3. **The row on every `Changed`. 7 red.** `pending`, `failed` and `rejected`, the conflict that says success, and the three scenes that set up through a pending confirm. They fail before any row assertion, at `OrderConfirmedEvent.from`'s guard: `IllegalArgumentException: Order 1 is not confirmed.`
4. **The row on `Conflicted` as well. 2 red.** `conflicting` and the conflict that says success, at the same guard.
5. **A row built past that guard on every `Conflicted` and non-success `Changed`. 8 red.** Mutants 3 and 4 never reach `assertNoRow`, so this one shows it can fail. `pending`, `failed`, `rejected`, `conflicting` and the customer reconcile's first step fail there with `expected: 0L but was: 1L`. The other three fail on the unique constraint when the scene's next write repeats the event ID.

`lost` passed under all five, since it reaches no TX B. No mutant here tells the two-orders scene apart from a correct write, since all of them leave its successful confirmations alone.

### Decisions made while implementing

- **All the scenes went into `OrderEventOutboxTest`,** as the ticket says. Its two `@MockkSpyBean` fields, `PgTransactionService` and `PaymentModifyService`, give the class a context of its own.
- **One scene beyond the criteria: a reconcile that finds a successful PG transaction for another amount.** The parameter set's conflict is a rejection, which never says success. This one is the conflict where a write keyed on the PG's word instead of `Changed` would leave a row for an unconfirmed order. The order stays payment pending, and the payment stays `PENDING`.
- **`assertOnlyTheConfirmation` compares the whole payload,** as well as the count and the message key. A count alone doesn't show that the row is this confirmation's.
- **The sequential scenes assert after both calls.** A count only at the end wouldn't show that the first call left the row.
- **No `CODING_STANDARDS.md` change.** These spies and their stubs are already there.

### Review

No review ran on this change: the ticket lists none. The reasoning above is the implementer's own.

### Left for later

- **Issue 195.** Nothing here reads week 6's path, so removing it needs no edit to this class. Each of `OrderEventPublishingTest`'s scenes now has a home or a planned end: the rows here and in issue 192's scenes, the rolled-back TX B in `OrderEventOutboxAtomicityTest`, and the INFO line, virtual thread and outside-a-transaction scenes are on design section 7's list to delete. Until then, these scenes also publish to the test broker on the default topic, and nothing reads it.
- **Step 2.** A duplicate that reaches TX B is kept to one row by `ReconcileResult.Changed`, with the unique constraint behind it. Mutant 1 shows that the constraint turns a wrong write into a failed TX B, not a second row.
