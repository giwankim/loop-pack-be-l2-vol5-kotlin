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
