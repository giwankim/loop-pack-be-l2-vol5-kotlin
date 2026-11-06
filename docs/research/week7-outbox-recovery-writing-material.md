# Week 7 outbox recovery: writing material

Material for the technical writing quest, not the write-up itself. Each section after the preface is recorded from one ticket of [spec 209](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/209)'s closing comments. The W6-jar numbers are in the [probe note](./week7-outbox-recovery-probe.md).

## Preface: what this branch is

`feat/outbox-recovery` is step 3 of week 7's three: 누락 복구 and the run across both apps. It was cut from `week7` at `92c007d`, and goes back into `week7` by its own PR.

- **What it adds.** 누락 복구: an operator names orders in `order-events.recovery.order-ids` and restarts the app, and each confirmed PG order without an outbox row gets one from its stored facts, with the event ID week 6 used. An identical row is left alone, and anything missing or different is refused, not guessed.
- **What it shows.** The assignment's §5 (week 6's unpublished and consumed cases reproduced and recovered in one run), §7 (one order from confirmation to purchase activity, through a republish) and the two §6 scenes step 2 left at the broker, all with the real commerce-streamer consuming.
- **How it shows it.** §5's preparation is the preserved W6 test configuration, not the W6 revision's jar: week 6 and week 7 differ at one point in TX B, so a test double replaces that point. The streamer runs as its own bootJar, so commerce-api gains no dependency on it.
- **What it costs.** The week 6 code that runs is today's TX B plus a test hook, and A's stop is a send that never happened, not a process kill. Adding the outbox table to a week 6 database is measured once, not tested.
- **Records.** [ADR 0036](../adr/0036-missing-outbox-rows-are-recovered-for-named-orders-from-stored-facts.md), [design section 14](../design/order-outbox.md#14-누락-복구와-앱을-건너는-실행) and the [probe note](./week7-outbox-recovery-probe.md).

### Where each closing comment sits

| Issue | What it built | Section |
| --- | --- | --- |
| [210](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/210) | the decisions, before the code | [Issue 210](#issue-210-record-step-3s-decisions) |
| [214](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/214) | the real streamer in commerce-api's tests | [Issue 214](#issue-214-the-real-streamer-consumes-in-commerce-apis-tests) |
| [211](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/211) | 누락 복구's happy paths through a provided port | [Issue 211](#issue-211-누락-복구-adds-a-missing-outbox-row-and-the-relay-publishes-it) |
| [212](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/212) | 누락 복구's refusals | [Issue 212](#issue-212-누락-복구-refuses-rather-than-guesses) |
| [213](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/213) | the startup runner that triggers 누락 복구 | [Issue 213](#issue-213-a-restart-with-order-eventsrecoveryorder-ids-triggers-누락-복구) |
| [215](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/215) | §7: one order through a republish to the streamer | [Issue 215](#issue-215-one-order-through-a-republish-recorded-once) |
| [216](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/216) | §5: week 6's two cases reproduced and recovered in one run | [Issue 216](#issue-216-week-6s-two-cases-reproduced-and-recovered-in-one-run) |

The sections keep the order the tickets land in.

## What the interview overturned

Each row was proposed or backed by the assistant first.

| First proposal | What replaced it | What changed it |
| --- | --- | --- |
| Run week 6's revision as a child-process jar to prepare §5 | The preserved W6 test configuration: a spy on the outbox repository in today's app | The user was hesitant to restart through a separate jar and asked for a way without code changes. A diff showed week 6 and week 7 differ at one call in TX B, and the assignment allows "보존한 W6 테스트 구성" |
| Rule out a test-source W6 path because TX B would write rows that would then have to be deleted | A double whose `save` writes nothing | The first dismissal was too quick: a double can keep the row from being written at all |
| Name the step 보충 (backfill), citing the glossary's ban on 복구하다 | 누락 복구, the assignment's word | The user asked what the assignment says. The ban sits under 예약을 돌려주다 and covers returning stock only |

## Issue 210: record step 3's decisions

Closed 2026-11-06 by `2789ae1` on `feat/outbox-recovery`. Documents only, no production code.

- **What it recorded.** [ADR 0036](../adr/0036-missing-outbox-rows-are-recovered-for-named-orders-from-stored-facts.md), [design section 14](../design/order-outbox.md#14-누락-복구와-앱을-건너는-실행), `CONTEXT.md`'s 누락 복구 with the 아웃박스 and 발행하다 amendments, the [probe note](./week7-outbox-recovery-probe.md), and this file's header. The ADR, the design and the glossary were drafted during the interview and checked against spec 209 here. They agreed, so they changed only to link the probe note.
- **What the review of the raw files corrected.** The probe's report gave the W6 jar's start without a broker as "49 s instead of 9 s". The saved logs say 46.8 s against 6.9 s, and the note uses those. Two values stay as the report's word, not a saved file: the confirm's 0.12 s and the empty topic list after the kill. The note says so.
- **Language.** The ADR and the design are Korean. The probe note and this file are English, like step 2's.

## Issue 214: the real streamer consumes in commerce-api's tests

Test code and build wiring only, no production code. Design 14.4 has the implementation's dated note.

- **What it added.**
  - `CommerceStreamerProcess` runs commerce-streamer's bootJar as a child process. It uses the same MySQL and broker, the streamer's `test` profile, `ddl-auto=create`, free ports, and the class's topic and group.
  - commerce-api's `test` task takes the streamer's `bootArchives` configuration as an input. That gives the task its dependency on `bootJar` and passes the jar's path in `commerce-streamer.jar`. Nothing puts the jar on a class path.
  - `OutboxDeliveryE2ETest`'s first scene carries step 2's two §6 rows to the consumer.
  - `CommerceStreamerProcessTest` shows that a missing jar property, or one that names no file, fails the run and doesn't skip it.
- **The scene.** The order is prepared before the streamer starts.
  - The start leaves every commerce table's `SHOW CREATE TABLE` as it was.
  - After the confirmation commits and before any relay runs:
    - the order is `CONFIRMED`;
    - the row is `PENDING` at attempt 0;
    - the topic has no record;
    - both streamer tables are empty;
    - the streamer has logged no `Recorded` line.
  - A new `OrderEventRelay` then publishes the row. The broker holds one record, the streamer logs one `RECORDED` line for it, and its tables hold one processed confirmation and one activity at the order's confirmation time.
- **Waits.** Each wait has a deadline:

  | Wait | Done when | Deadline |
  | --- | --- | --- |
  | Broker | the topic shows its 3 partitions | 30 s |
  | Assignment | the admin client sees the group `STABLE`, every member holding a partition, and all 3 held | 90 s |
  | Result | the streamer has logged the expected `Recorded` lines | 30 s |

  Assignment checks every member because a first generation can give one member all three partitions. On a timeout the run prints the lines so far, the group's marked-read offsets against the topic's ends, the scene's rows and the streamer's last 40 log lines. If the process exits during a wait, the wait fails at once.
- **Ending the streamer.** The class's `@AfterEach` sends SIGTERM and waits up to 40 s. It then forces the end if the process is still alive, and checks that the process is gone. JUnit runs this before the base truncates. A shutdown hook covers a JVM that exits first.
  - The method is named `terminate`. The streamer's glossary uses 멈추다 for its consumer halting on an event, so the word "stop" was avoided.

**One run's line, from `scripts/scene-lines` on the final check's reports:**

```text
Delivery, after the commit before the relay: order 1 CONFIRMED; row order-confirmed:v1:1 PENDING attempts 0 published_at null; records 0; purchase activities 0; a recreated relay: row order-confirmed:v1:1 PUBLISHED attempts 1 published_at 2026-11-06T01:02:08.123456Z; record key 1 partition 0 offset 0; deliveries 1 (Recorded the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, partition=0, offset=0, result=RECORDED); processed_order_confirmation [order-confirmed:v1:1 order 1 confirmed_at 2026-11-06T01:02:03.123456Z]; purchase_activity [order 1 product 1 quantity 2 confirmed_at 2026-11-06T01:02:03.123456Z]
```

**Measured once, in a filtered run of the scene.** Uncommitted tree on `a5507d5`, laptop Docker, Testcontainers' `mysql:8.4` and `apache/kafka-native:4.4.0`. The streamer started in 7.4 s and its group settled 0.6 s later. It recorded about 3 s after that, which includes the topic reader's 1 s quiet wait. Its graceful end took 20 ms. The scene took 43 s, and the Spring context took the rest.

**Checks of the failure paths.** These were local edits, run and reverted:

| Edit | What happened |
| --- | --- |
| The scene expects 2 deliveries | It failed after 30 s with `Waited 30 s for 2 recorded order confirmed events.`, then the state (`1 recorded; marked read/records 0: 1/1, 1: none/0, 2: none/0`, the outbox row, both tables) and the streamer's last 40 lines. No streamer process was left. This ran once before the review and once after it moved the waits to Awaitility. |

**What the review changed:**
- The waits use Awaitility with `failFast` on the process's exit, instead of a sleep loop.
- The broker-record assertion now carries the rows and the streamer's log.
- The "commerce tables unchanged" check now compares `SHOW CREATE TABLE`, not table names.
- The log is decoded leniently, and only complete lines are counted, because the streamer is still writing it.
- `stop` became `terminate`, and "committed offset" became "marked read". Both renames follow the streamer's glossary.
- The scene's two native-query helpers became one.

**Kept on purpose:**
- **One group per class.** The issue names the class's group, so a later scene's streamer reads on from the offsets an earlier one marked read. The harness's KDoc says so, for issue 215's scenes.
- **The helper sits in `com.loopers.support` with one user today.** Design 14.1 gives it a second, `OrderEventRecoveryE2ETest`.

**Final check:** `scripts/final-check` on the uncommitted tree on `a5507d5` that this issue's commit holds. ktlint passed. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped (53 s). commerce-api: 92 classes, 1034 tests, 0 failures, 0 errors, 0 skipped (4 min 15 s). Only this section's text changed after that run.

## Issue 211: 누락 복구 adds a missing outbox row, and the relay publishes it

Closed 2026-11-06 by `f0593bf`, `204ed37` and `b2e6e24` on `feat/outbox-recovery`, rebased onto issue 214's `0743f5d`. A confirmed PG order without an outbox row now gets the row TX B would have written, and the next relay round publishes it. Calling the port is the only way in: the runner is issue 213's, and the refusals are issue 212's.

**Prefactor first.** `OrderEventOutboxFactory` builds the row from the order (`OrderConfirmedEvent.from` and `OrderEventSerializer`), due at the `Clock` bean's now. TX B's `leaveInOutbox` saves what it builds, and recovery does too. `OrderEventOutboxTest` (16) and `OrderEventOutboxAtomicityTest` (2) passed unchanged before anything else landed, and the prefactor is its own commit.

**Names settled.**

| Working name or role | Final |
| --- | --- |
| `OrderEventRecoverer` (provided port) | kept: `recover(orderIds): List<OrderEventRecoveryResult>` |
| the result | `OrderEventRecoveryResult(orderId, eventId, outcome)`, outcome `ADDED` or `ALREADY_PRESENT` |
| the implementation | `OrderEventRecovery`, `Propagation.NEVER`, like `OrderEventRelay` |
| one order's transaction | `OrderEventOutboxModifyService.recover(orderId)`, beside the relay's short transactions |
| reading the order | `OrderFinder.findForRecovery(orderId)`, named for its caller like `findForPg`, no lock |
| the shared row-building | `OrderEventOutboxFactory`, a `@Component` like `OrderInfoAssembler` |

**How a confirmed order without a row is made.** The spy on `OrderEventOutboxRepository` answers TX B's `save` once with its argument and writes nothing. Later saves are the real ones. This is ADR 0036 rule 5's week 6 configuration in miniature, and no scene deletes a row or writes `status`.

**What the scenes show** (`OrderEventRecovererTest`, 7 scenes, `BaseCommittingApplicationServiceTest`, own topic `order-events-recovery`):

- The added row's six event columns equal the literals `OrderEventOutboxTest` pins for TX B, the payload as a whole string with items in product ID order. It is `PENDING` with 0 attempts, due at the pinned recovery instant rather than the confirmation, read in a new transaction.
- One relay round afterwards puts one record keyed by the order, carrying its event ID, read from the broker.
- A second recovery reports `ALREADY_PRESENT` and leaves the one row's ID, state, attempts and times as they were, for a `PENDING` row (an earlier recovery's), a `PUBLISHED` row (TX B's, published by the relay) and a `BLOCKED` row (a recovered row whose sends failed for 20 rounds through the spied `KafkaTemplate`, as in `OrderEventRelayFailedSendTest`).
- Two orders, one missing and one present, give two results and two INFO lines, in the order given: `Ran 누락 복구 for the order confirmed event. orderId=…, eventId=…, result=ADDED|ALREADY_PRESENT`.
- The test PG's request count is the same before and after two recoveries.

**What this ticket does not decide.** "An identical row is left alone" holds only for identical rows so far: the check is a row with that event ID, not the six-column comparison. A differing row would read as `ALREADY_PRESENT` until issue 212 refuses it, and a point-confirmed order would get a row until 212 refuses it too. When 212 adds the comparison, `ALREADY_PRESENT` may want a name that says "identical". The KDocs say "a row of its event ID" so they don't claim more than the code does.

**What the review changed** (two parallel reviewers, standards and spec):

- The two-order scene read the `order` field after preparing two orders. It now uses return values (fixture rule).
- KDocs said "operator" and "sends", both on CONTEXT.md's Avoid lists. They now say "a person" and "publishes".
- The INFO line began "Recovered …" even for `ALREADY_PRESENT`. It now reads for both.
- CODING_STANDARDS.md got dated notes for the class's two spies, its constructor dependencies and its preparation through the recovery and relay ports (the `BLOCKED` and `PUBLISHED` rows can only be reached that way).
- Kept as they are: `findForRecovery`'s body equals `findForPg`'s (the name-the-caller convention). `OrderEventRecovery` only sequences and logs, like `OrderEventRelay`. The 20 rounds and 5 minute steps copy `OrderEventRelayFailedSendTest`'s helper.

**Red check, a local edit run and reverted, not committed:**

| Edit | What went red |
| --- | --- |
| the `findByEventId` check disabled, so recovery always adds | the three second-recovery scenes, the log scene and the no-PG scene (each recovers an order that already has its row) |

**Final check:** `scripts/final-check` on the rebased tree (`a63c863`, clean, which holds `b2e6e24`'s code; this commit changes only this section). ktlint passed. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped (57 s). commerce-api: 93 classes, 1041 tests, 0 failures, 0 errors, 0 skipped (4 min 19 s), issue 214's 92 and 1034 plus this class and its 7. Before the rebase, the same code passed on `a5507d5` with 91 classes and 1038 tests. `HexagonalArchitectureTest` passed unchanged: the new classes sit in `application.order`. The runner, and its place in the adapter layer, is issue 213's.

## Issue 212: 누락 복구 refuses rather than guesses

Closed 2026-11-06 by `9ff71eb` and `54a24d0` on `feat/outbox-recovery`. 누락 복구 now refuses rather than guesses: an order whose facts don't fit, or a row of its event ID that isn't the row recovery builds, ends the run with a refusal, and nothing is written for that order. The runner that turns a refusal into a failed start is still issue 213's.

**The refusals.** They are four subclasses of the sealed `OrderEventRecoveryRefusedException`, one per refusing row of ADR 0036 rule 3's table:

| Refusal | When | Message |
| --- | --- | --- |
| `OrderNotFound` | no order has the ID (`findForRecovery` throws it, not `ORDER_NOT_FOUND`) | `Refused 누락 복구: order 404 doesn't exist.` |
| `OrderNotConfirmed` | `confirmedAt` is null: draft, payment pending, payment failed | `… order N has no confirmation time.` |
| `OrderNotPaidThroughPg` | confirmed, but not through the PG | `… order N wasn't paid through the PG.` |
| `OutboxRowDiffers` | the row of the event ID differs in one or more of the six event columns | `… the outbox row of event order-confirmed:v1:N differs in payload.` |

The order checks run before the row is built, so `OrderConfirmedEvent.from`'s `requireNotNull` is never what refuses. `OrderEventRecovery` logs each refusal at WARN once its transaction has rolled back (`<the message> orderId=N`) and rethrows, so the list stops there. The sealed supertype is a deliberate difference from the PG's exceptions (ADR 0023 rule 2): whoever runs recovery handles all four alike.

**Which row is compared.** The one `findByEventId` finds. The lookup and the unique key share the column's collation (`utf8mb4_general_ci`), so the row compared is exactly the row a new insert would collide with. That is how `event_id` can differ at all: a row whose event ID differs only in case is found, and Kotlin's exact comparison refuses it. Under a binary collation such a row would be neither found nor collided with, and recovery would add a second row, consistently. The comparison is `OrderEventOutbox.eventColumnsDifferingFrom`, which names every differing column in table order, the payload compared as the exact string. State columns are not compared.

**How the differing row is reached without the test writing it.** `OrderEventRecovererTest` spies `OrderEventOutboxFactory`. For one confirmation the spy answers TX B's `create` with the real row rebuilt with one column changed, and the real TX B saves that. Recovery's `create` is the original call. The six changes: event ID upper-cased, order ID + 1000, event type `OrderCancelled`, schema version + 1, message key + `-other`, and the payload with a space after each comma (the same JSON, so only an exact-string comparison refuses it). The point-confirmed order is the one state no code path makes now, so it is an `update orders` through `JdbcTemplate`, as the order API tests do. No scene writes an outbox row.

**What the scenes show** (`OrderEventRecovererTest`, 12 new tests on top of issue 211's 7):

- A missing order, a draft, a PG order whose payment failed, and a point-confirmed order are each refused with their own type and message, and the outbox stays empty.
- For each of the six columns (one parameterized test), the refusal's message names the event ID and the column, and neither it nor the single WARN line contains the stored payload or `"items"`. The row keeps its ID, state, attempts and times.
- `[first, draft, last]`: the draft is refused. The first order's row is there in a new transaction, the last has none, and only the first logged an INFO line. Once the draft is confirmed, the same list gives `ALREADY_PRESENT`, `ADDED`, `ADDED`: the first row keeps its ID and the table has 3 rows.
- Two recoveries of one order through `runConcurrently` end with one row and one `ADDED`. The other either finds the row (`ALREADY_PRESENT`) or fails on the unique key. `tally` counts that failure as the expected `DataIntegrityViolationException` and anything else as a technical error, which must be 0. A throwaway probe confirmed `DataIntegrityViolationException` is the exact type a duplicate event ID raises here (not `DuplicateKeyException`). The first run of the scene took the collision branch, so that path has been observed and not only allowed.

**What the review changed** (two parallel reviewers, standards and spec; the spec reviewer found no gap):

- The service's private guards were `ensure*`, which CODING_STANDARDS.md keeps for an entity's own guard. They are `refuseUnrecoverableOrder` and `refuseDifferingRow`.
- The six-column comparison moved from the service to `OrderEventOutbox.eventColumnsDifferingFrom`, beside the columns (Feature Envy).
- The refusal's KDoc said "caller" (CONTEXT.md's Avoid list) and "like the PG's exceptions" of a sealed type. It now says "request" and why the supertype is sealed.
- The test helper's parameter is `draftOrder`, no longer a shadow of the base's `order` field.
- Kept: the test's per-column `if` cascade that builds the differing row. It is test data, and a shared enum would tie the test to the code under test. `OrderEventRecoveryResult.Outcome.ALREADY_PRESENT` keeps its name. Its KDoc now says "the same row, equal in all six event columns", which is what issue 211's note asked the name to say.

**Red check, a local edit run and reverted, not committed:**

| Edit | What went red |
| --- | --- |
| the order checks disabled | the point scene (got a row), and the draft, failed-payment and rerun scenes (`IllegalArgumentException` from `OrderConfirmedEvent.from` instead of the refusal) |
| before the comparison existed | all six differing-row runs returned `ALREADY_PRESENT`, including `event_id`, which showed the case-insensitive lookup finds the row |

**Final check:** `scripts/final-check --app commerce-api` on `54a24d0`'s code (the run preceded its commit; this commit changes only this section). ktlint passed. commerce-api: 93 classes, 1053 tests, 0 failures, 0 errors, 0 skipped (4 min 47 s), issue 211's 1041 plus this ticket's 12. commerce-streamer was not rerun: nothing under it changed.

## Issue 213: a restart with order-events.recovery.order-ids triggers 누락 복구

**What landed.** `OrderEventRecoveryRunner`, a one-class `ApplicationRunner` in a new `adapter.startup` package. It binds `order-events.recovery.order-ids` and, when the list isn't empty, makes one call to `OrderEventRecoverer.recover`. It decides nothing, like `OrderEventRelayScheduler`. There is no endpoint. `application.yml` documents the property under the `local` profile, next to `order-events.relay.enabled`, which is the only place that switch is written. No profile sets the property.

**Choices the ticket left open.**

| Question | Answer | Why |
| --- | --- | --- |
| Which adapter package | a new `adapter.startup` layer | it's a trigger, but not a schedule. `HexagonalArchitectureTest` gives it its own layer (no other adapter may reach it) and a second trigger rule: it depends only on itself and the provided ports. Both trigger rules now come from one helper, `triggerReachesOnlyProvidedPorts` |
| How to read the property | `Binder` over the `Environment` | "one class" rules out a separate `@ConfigurationProperties` type, and `@Value` silently misses a YAML list (`order-ids[0]`). `Binder` takes `--…=3,7`, `ORDER_EVENTS_RECOVERY_ORDER_IDS` and a list alike. `bindOrCreate` can't create a `List` (an interface), so it is `bind(…).orElse(null).orEmpty()` |
| "the refusal as the cause" | the start throws the refusal itself | Boot 4.1's `callRunner` wraps only checked exceptions (`ThrowingConsumer`, "Failed to execute ApplicationRunner"). An unchecked refusal comes out of `SpringApplication.run` as is, after the context is closed. The scene asserts that, so the ticket's "cause" reads as "the exception" |

**What the scenes show** (`OrderEventRecoveryRunnerTest`, 6 scenes, `BaseCommittingApplicationServiceTest`, own topic `order-events-recovery-runner`). Each scene leaves an order confirmed without a row, with the spy from issue 211 that writes nothing for TX B's save once. It then starts a second app on the same containers, the way `OrderPaymentIntegrationTest.restarted()` does: `test` profile, `ddl-auto=none`, no `LoggingApplicationListener`. Rows are read in a new transaction.

- Property absent, and property empty (`--order-events.recovery.order-ids=`): no row, and no `Ran 누락 복구` INFO line.
- Property empty, with the runner built by hand over a `MockEnvironment` and a mockk port: the port is never called. The two scenes above can't tell no call from a call with no IDs.
- Two IDs and the relay on: both rows reach `PUBLISHED` within 10 s, and the topic holds two records, keyed by the two orders.
- One ID and the relay off: the row is there, `PENDING`.
- A nonexistent order ID: the start throws the exception the port throws for that ID when called directly (same class and message), and no row committed. The expected exception comes from the port rather than a literal. The scene was written against `CoreException(ORDER_NOT_FOUND)`, and after the rebase onto issue 212 it passed unchanged against `OrderEventRecoveryRefusedException.OrderNotFound`.

**What the review changed** (two parallel reviewers, standards and spec):

- The spec reviewer found that "calls nothing" wasn't observed: a call with an empty list adds nothing and logs nothing too. Hence the hand-built runner scene. Disabling the runner's empty-list guard turns only that scene red (`MockKException: no answer found for … recover([])`). The other five stay green.
- CODING_STANDARDS.md names the exception that `assertThrows` returns `exception`. The expected refusal is now a `runCatching` fixture, so only the start failure is named `exception`.
- `MISSING_ORDER_ID` became `NONEXISTENT_ORDER_ID`. In this class, "missing" already means an order missing its row.
- The yml comment no longer says "Empty, as on every profile". It says no profile sets the property.
- Kept as they are: `started` repeats `OrderPaymentIntegrationTest.restarted()` apart from its extra arguments. With two users, moving it to `support` can wait for a third. `committedRowOrNull` spells the event ID as the base class's `committedOrderEventOutbox` does. The documented empty key sits in the `local` block beside the relay's switch, as the ticket asks, not in the default document.

**Final check:** `scripts/final-check` on the tree rebased onto issue 212 (`17ad415`, clean, which holds `6d7643c`'s code; this commit changes only this section), started 2026-11-06T03:02:42Z, `BOOTSTRAP_SERVERS` unset. ktlint passed. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped (50 s). commerce-api: 94 classes, 1060 tests, 0 failures, 0 errors, 0 skipped (4 min 31 s). That is issue 212's 93 classes and 1053 tests, plus this class with its 6 scenes and `HexagonalArchitectureTest`'s new rule (9 → 10). Before the rebase, the same code passed on `596124c` with 1048 tests (issue 211's 1041 plus the same 7).

## Issue 215: one order through a republish, recorded once

Closed 2026-11-06 by `2f63981` on `feat/outbox-recovery`, rebased onto issue 213's `e49f831`. Test code and notes only, no production code. This is the assignment's §7, design 14.5's table, run once with the real commerce-streamer consuming.

**The scene.** `OutboxDeliveryE2ETest`'s second `@Test`, beside issue 214's scene, on the class's topic and group:

1. One order of two products, 2 and 1, confirmed through the provided ports with the test PG. 10,000 points are charged first, so "no point deduction" has a balance to keep.
2. Step 2's stop: the first `markPublished` throws after the broker's ack. The broker holds a record, the row stays `PENDING` at attempt 1 under its lease, and the scene waits for the streamer's `RECORDED` line before going on.
3. A recreated `OrderEventRelay`, a second past the lease, publishes the event again as a new record at the next offset. The streamer logs `ALREADY_RECORDED` for it.

**What was observed, per row of design 14.5's table:**

| Observation | Expected | Observed |
| --- | --- | --- |
| Order and PG transaction | 1 confirmed order, 1 external transaction | 1 row in `orders`, `CONFIRMED`; the test PG got 1 request, the create |
| Outbox | 1 row of the event ID, `PUBLISHED` at the end | 1 row, `PUBLISHED` at attempt 2 |
| Kafka | ≥ 2 records of the event ID, different offsets | 2 records, same key, partition and value, offsets 1 and 2 |
| Consumer | the event received ≥ 2 times | `RECORDED` at offset 1, then `ALREADY_RECORDED` at offset 2 |
| Processed confirmation | 1, for the order and event ID | 1 |
| Purchase activity | 1 row per product, quantities 2 and 1 | 2 rows, 2 and 1, at the confirmation time |
| Stock and points | no extra take or give-back, no deduction | `[8, 9]` of 10 after the confirmation and at the end; 10,000 of 10,000 |

The offsets are 1 and 2 because issue 214's scene ran first and wrote offset 0 to the shared topic. In another order they would be 0 and 1.

**One run's line, from `scripts/scene-lines` on the final check's reports:**

```text
Delivery, republished after the broker's ack: order 1 CONFIRMED, PG requests 1; after the stop: row order-confirmed:v1:1 PENDING attempts 1 published_at null, record key 1 partition 0 offset 1; past the lease: row order-confirmed:v1:1 PUBLISHED attempts 2 published_at 2026-11-06T01:03:09.123456Z, record key 1 partition 0 offset 2; deliveries 2 (Recorded the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, partition=0, offset=1, result=RECORDED; Recorded the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, partition=0, offset=2, result=ALREADY_RECORDED); processed_order_confirmation [order-confirmed:v1:1 order 1 confirmed_at 2026-11-06T01:02:03.123456Z]; purchase_activity [order 1 product 1 quantity 2 confirmed_at 2026-11-06T01:02:03.123456Z; order 1 product 2 quantity 1 confirmed_at 2026-11-06T01:02:03.123456Z]; stock [8, 9] after the confirmation, [8, 9] at the end, of 10 each; points 10000 of 10000
```

**Where the spec's words and the test differ:**

- **Products 7 and 8 are not IDs.** The tables are emptied after each test, so IDs restart at 1, and the two products are 1 and 2. Preparing six throwaway products to reach 7 and 8 would show nothing.
- **"At least two" is asserted as exactly two.** This holds for the records and for the deliveries. The group's assignment is awaited until `STABLE` before anything publishes, so a rebalance redelivery would be a defect worth seeing, not noise.
- **Two checks hold by construction.** Neither the relay nor the streamer touches stock or points, so those assertions show the republish has no such path more than they test one. Stock is read right after the confirmation and again at the end, so "extra" is a comparison, not just a final value.

**What the review changed** (two parallel reviewers, standards and spec):

- **The stop's stub moved.** It was copied line for line from `OrderEventRelayerTest`. CODING_STANDARDS.md says a stub two classes use is an extension in `OrderPaymentSpies.kt`, so it is now `OrderEventOutboxModifyService.stopRelayAfterBrokerAckBeforeMarkingPublished()`, used by both. Its list of shared stubs names it.
- **CODING_STANDARDS.md got the class's spy note**, as every new `@MockkSpyBean` has. The note says why the first scene isn't split off: design 14.1 places both scenes in the class, sharing its topic, group and streamer.
- **Design wording.** "같은 record를 다시 보낸다" used a word from 발행하다's Avoid list and contradicted 다시 발행하다, since a republish is a new record. It now reads "같은 이벤트를 새 record로 다시 발행한다".
- **Stock is read after the confirmation too**, as described above.
- **Smaller fixes.** The first scene uses the new `recordedLine` helper, and its local was renamed so it no longer shadows the helper. The lease constant's KDoc says "a second past".
- **Kept as they are:**
  - the exact counts, explained above;
  - the `order-confirmed:v1:` literal in both scenes, the event ID's spelled-out contract, as in step 2;
  - the scene's length, one §7 run whose table is the behaviour;
  - `describe(record)`, private in both classes, as step 2 left it.

**Red check, a local edit run and reverted, not committed:**

| Edit | What went red |
| --- | --- |
| the stop not stubbed | the scene: `Expected DataAccessResourceFailureException to be thrown, but nothing was thrown.` The other scene stayed green. |

**Final check:** `scripts/final-check` on the rebased tree (`7259334`, clean, which holds `2f63981`'s code; the amended commit changes only this section), started 2026-11-06T03:28:14Z, `BOOTSTRAP_SERVERS` unset. ktlint passed. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped (59 s). commerce-api: 94 classes, 1061 tests, 0 failures, 0 errors, 0 skipped (5 min 3 s): issue 213's 1060 plus this scene. Before the rebase, the same code passed on `03773a5` with 1054 tests (issue 212's 1053 plus this scene).

## Issue 216: week 6's two cases reproduced and recovered in one run

**What landed.** `OrderEventRecoveryE2ETest`, one `@Test` on `BaseCommittingApplicationServiceTest` with its own topic and streamer group, and the real commerce-streamer as a child process. It is a reproduction in this run, not a recovery of week 6's incident: week 6's database is gone, and the week 6 code that runs is today's TX B with a test hook at the one point where week 6 differed.

- **Week 6, in this class's context.** A spy on `OrderEventOutboxRepository` makes TX B's `save` write no row and register an after-commit hook instead. A's hook sends nothing, so A's stop is a send that never happened, not a process kill. B's hook sends the row it was given through today's `KafkaOrderEventPublisher` and waits for the broker. Both orders go through the provided ports with the test PG, at pinned confirmation times.
- **Recovery.** The app is restarted on the same containers, without the spy, with `ddl-auto=none`, the relay on and `order-events.recovery.order-ids` set to A and B. A second restart uses the same IDs and stays up for 2 s with its relay on.
- **Nothing is cleaned in between.** No step deletes a row, inserts a `SUCCESS`, writes a `status` or empties a table before the end.

**What the scene shows:**

| When | Observed |
| --- | --- |
| Before recovery | Both orders `CONFIRMED`, stock down by 2 and 1 (the reservations consumed), no outbox row. One record on the broker, B's, and one `RECORDED` line. Only B has purchase activity. C=2 (one create per payment), T=2, and the stored transaction keys are those T created. |
| First restart | `ADDED` for A and B, in that order. Both rows reach `PUBLISHED` at attempt 1. Two new records, and B's record value is byte for byte the one week 6's path sent. The streamer logs `RECORDED` for A and `ALREADY_RECORDED` for B. A gains its purchase activity and B's is unchanged. |
| During recovery | Orders, payments and stock are as before recovery: status, `confirmed_at`, `updated_at`, PG order ID, payment status and transaction key. PG creates stay at C, the same T transaction keys, 0 lookups, no unmatched request. |
| Second restart | `ALREADY_PRESENT` for both. Every column of both rows is unchanged while its relay runs. No record, no streamer line, no purchase activity, no PG request. |

**One run's lines, from `scripts/scene-lines` on the final check's reports:**

```text
W6 reproduction, before 누락 복구: A order 1 CONFIRMED event order-confirmed:v1:1 confirmed_at 2026-10-29T01:02:03.123456Z items [product 1 quantity 2] pg_order_id f2a4d44f-0fc2-42e2-8b6e-44facc03ce26 transaction_key 82eba3e7-4b6e-4df7-86fe-ee42cda57c40, sent nothing after the commit; B order 2 CONFIRMED event order-confirmed:v1:2 confirmed_at 2026-10-29T01:02:04.654321Z items [product 2 quantity 1] pg_order_id d3758364-e185-421b-b427-bd8360331f69 transaction_key 5fa58e4f-d708-4801-b3b6-b12346d32439, sent after the commit (record key 2 partition 2 offset 0); PG creates C=2, PG transactions T=2, PG lookups 0; stock 431/212 -> 429/211; outbox rows 0; deliveries 1 (Recorded the order confirmed event. eventId=order-confirmed:v1:2, orderId=2, partition=2, offset=0, result=RECORDED); purchase_activity [order 2 product 2 quantity 1 confirmed_at 2026-10-29T01:02:04.654321Z]
누락 복구, the first start with A and B: Ran 누락 복구 for the order confirmed event. orderId=1, eventId=order-confirmed:v1:1, result=ADDED; Ran 누락 복구 for the order confirmed event. orderId=2, eventId=order-confirmed:v1:2, result=ADDED; rows [order-confirmed:v1:1 PUBLISHED attempts 1 published_at 2026-11-06T04:09:55.754415Z; order-confirmed:v1:2 PUBLISHED attempts 1 published_at 2026-11-06T04:09:55.760553Z]; records [key 2 partition 2 offset 1; key 1 partition 0 offset 0]; deliveries [Recorded the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, partition=0, offset=0, result=RECORDED; Recorded the order confirmed event. eventId=order-confirmed:v1:2, orderId=2, partition=2, offset=1, result=ALREADY_RECORDED]; purchase_activity [order 2 product 2 quantity 1 confirmed_at 2026-10-29T01:02:04.654321Z; order 1 product 1 quantity 2 confirmed_at 2026-10-29T01:02:03.123456Z]; PG creates 2, PG transactions 2, PG lookups 0; orders, payments and stock unchanged: order 1 CONFIRMED confirmed_at 2026-10-29T01:02:03.123456Z updated_at 2026-11-06T04:09:51.350619Z payment f2a4d44f-0fc2-42e2-8b6e-44facc03ce26 SUCCESS 82eba3e7-4b6e-4df7-86fe-ee42cda57c40 updated_at 2026-11-06T04:09:51.350666Z; order 2 CONFIRMED confirmed_at 2026-10-29T01:02:04.654321Z updated_at 2026-11-06T04:09:51.375105Z payment d3758364-e185-421b-b427-bd8360331f69 SUCCESS 5fa58e4f-d708-4801-b3b6-b12346d32439 updated_at 2026-11-06T04:09:51.375125Z; stock 429/211
누락 복구, the second start with the same IDs: Ran 누락 복구 for the order confirmed event. orderId=1, eventId=order-confirmed:v1:1, result=ALREADY_PRESENT; Ran 누락 복구 for the order confirmed event. orderId=2, eventId=order-confirmed:v1:2, result=ALREADY_PRESENT; rows 2, unchanged while it ran 2 s with the relay on; records 0; deliveries 3; purchase_activity 2, unchanged; PG requests 0 more
```

**Where the spec's words and the test differ** (design 14.3 carries a dated note):

- **The first context isn't closed.** It is JUnit's test context, which a scene can't close. Its relay is off under `test`, so it publishes nothing. If another of its beans acted, the PG lookup count or an order's or payment's `updated_at` would change.
- **Callbacks are inferred, not counted.** The test PG sends none. A callback's reconcile looks the payment up (ADR 0031), or finds it finished and changes nothing, so the scene reads "no callback" as 0 lookups added and payments unchanged.
- **T is the test PG's count.** It is the transaction keys of the creates the test PG answered with a PG transaction (`TestPg.createdTransactionKeys()`), checked against the keys both payments stored.
- **The restarts' banner is off.** Printed after a step's INFO line, it read as that line's continuation in `scripts/scene-lines`.

**What the review changed** (two parallel reviewers, standards and spec):

- **One restart helper.** `restarted`/`started` had three copies (`OrderPaymentIntegrationTest`, `OrderEventRecoveryRunnerTest`, this class). It is now `com.loopers.support.restartedCommerceApi`, and CODING_STANDARDS.md's restart note names it and its fifth override, `spring.main.banner-mode=off`.
- **Shared streamer pieces.** `CommerceStreamerProcess.Settings.of(…)` builds the settings both E2E classes spelled out. `storedPurchaseActivities()`, `nativeStrings` and `STORED_UTC_TIME` moved into `BaseCommittingApplicationServiceTest`.
- **Creates per payment.** CODING_STANDARDS.md asks a scene with several payments to count creates per PG order ID, so both counts are `createCount(pgOrderId)` = 1, besides the total.
- **The second restart is held.** It used to close straight after startup, so its relay never ran. It now stays up 2 s, two relay ticks, while every row stays as it was.
- **T is tied to stored facts.** The created transaction keys must equal the payments' stored keys.
- **Smaller.** `createdTransactionKeys()` names the response when a 202 carries no key, instead of an NPE.
- **Kept as they are:** comparing state as one joined line (a failure prints both lines), the local `streamer` beside the field `terminateTheStreamer` reads (as in `OutboxDeliveryE2ETest`), and one long `@Test`, since the steps must share one run with no cleanup between them.

**Red check, a local edit run and reverted, not committed:**

| Edit | What went red |
| --- | --- |
| `OrderEventRecoveryRunner` doesn't call the port | the scene, with `ConditionTimeoutException` at the first restart's wait for both rows `PUBLISHED` |

**The test heap.** The first final check failed: commerce-api's test JVM ran out of heap (`Java heap space`), and `HexagonalArchitectureTest`, running last, was skipped. Nothing set the test heap, so Gradle's 512 MB default applied.

- A control run of the same tree without this class passed, but ended at about 419 MB live after 13 full GCs. The suite was already near the limit.
- Sampling the live heap every 3 s after a forced full GC, with soft references cleared, shows no single test using it up. Spring keeps up to 32 test contexts, the run starts 35, and a new one costs about 40 MB (`BrandRegisterConcurrencyTest`, with no restart, adds 45 MB). Each restarted context leaves about 6 MB of classes that are never unloaded (`OrderEventRecoveryRunnerTest`, with six restarts, adds 78 MB). This class adds about 47 MB: its own context and two restarts.
- So `build-logic`'s Spring convention now sets `maxHeapSize = "1g"` on `tasks.test` (`fc28dbc`). With it the suite ends at about 590 MB live with no full GC. The user chose this over closing single-use contexts with `@DirtiesContext`, which would have left the suite near its limit as classes are added.

**Final check:** `scripts/final-check` on `fc28dbc` (clean), rebased onto issue 215, started 2026-11-06T04:05:40Z, `BOOTSTRAP_SERVERS` unset. ktlint passed. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped (57 s). commerce-api: 95 classes, 1062 tests, 0 failures, 0 errors, 0 skipped (5 min 12 s). That is issue 215's 94 classes and 1061 tests plus this class's scene. Only this section's text changed after that run.
