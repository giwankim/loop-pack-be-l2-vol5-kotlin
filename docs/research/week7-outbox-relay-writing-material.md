# Week 7 outbox relay: writing material

Material for the technical writing quest, not the write-up itself. Each section after the preface is recorded from one ticket of [spec 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)'s closing comments. The numbers are in the [probe note](./week7-outbox-relay-probe.md).

## Preface: what this branch is

`feat/outbox-relay` is step 2 of week 7's three: the single relay. It was cut from `week7` at `e6a11ac`, and goes back into `week7` by its own PR. Step 3 (`feat/outbox-recovery`) gets its own interview.

- **What it adds.** A relay that claims due outbox rows in batches of 20 with `FOR UPDATE SKIP LOCKED` and a 60 s lease, sends them outside any transaction, and saves each row's own result. A row that fails waits with a growing backoff, and after 20 attempts it is `BLOCKED` until someone runs the documented SQL. A scheduler in a new `adapter.scheduling` layer runs it where `order-events.relay.enabled` is on.
- **What it shows.** One event ID's path from a confirmed order to its row to the broker, and the same event at a second offset after a stop between the broker's ack and `PUBLISHED`. The scenes stop at the broker. The consumer's half of that path, purchase activity, is step 3's.
- **What it does not change.** The wire contract, commerce-streamer's code, and the confirm, reconcile and callback answers. `OrderPaymentIntegrationTest` (63) passes unedited.
- **What it costs.** Publishing is at least once. One relay is a setting, not ownership, and the default profile being `local` means a deployment that forgets its profile turns it on. An outage beyond about 77 minutes leaves rows for a person.
- **The numbers.** About 336 rows/s through the real jar, against 480 for bare SQL. `scripts/final-check` on `8ee98a2`: commerce-api 90 classes and 1032 tests, commerce-streamer 18 and 67, no failures.
- **Records.** [ADR 0035](../adr/0035-the-relay-claims-rows-with-skip-locked-and-a-lease-and-keeps-a-result-per-row.md), [design section 13](../design/order-outbox.md#13-중계), where 13.11 is what was measured last, 13.12 the tickets and 13.13 the commands, and the [probe note](./week7-outbox-relay-probe.md).

### Where each closing comment sits

| Issue | What it built | Section |
| --- | --- | --- |
| [199](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/199) | the decisions, before the code | [Issue 199](#issue-199-record-the-relays-decisions) |
| [200](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/200) | the tracer: row to broker to `PUBLISHED` | [Issue 200](#issue-200-a-confirmed-orders-event-reaches-the-broker) |
| [205](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/205) | the trigger and `kafka.yml` | [Issue 205](#issue-205-the-relay-runs-by-itself-when-enabled) |
| [201](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/201) | a failure stays with its row | [Issue 201](#issue-201-a-failed-publish-stays-with-its-own-row) |
| [203](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/203) | the stop scenes | [Issue 203](#issue-203-a-stopped-relays-rows-are-published-again-by-a-new-relay) |
| [204](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/204) | draining, and concurrent claims | [Issue 204](#issue-204-rounds-drain-a-backlog-and-concurrent-claims-never-take-the-same-row) |
| [202](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/202) | `BLOCKED` and the resume | [Issue 202](#issue-202-a-row-that-keeps-failing-becomes-blocked-and-someone-can-resume-it) |
| [206](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/206) | these records | [Issue 206](#issue-206-step-2s-records-match-what-was-built) |
| [198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198) | the spec's differences | [Spec 198](#spec-198-where-the-implementation-differs) |
| [198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198) | the closing review | [Spec 198's closing review](#spec-198-the-closing-review) |

The sections keep the order the tickets landed in. A draft of the PR body is at the [end](#draft-pr-body).

## What the interview overturned

The design came out of several rounds in which measurements and the user's questions kept replacing the first proposal. That may be the most useful material here, since the assignment asks to compare an AI's suggestions against your own expectations. Each row was proposed or backed by the assistant first.

| First proposal | What replaced it | What changed it |
| --- | --- | --- |
| Send one at a time, end the round at the first failure | Batches of 20 with per-row results | The user asked how batch failures are handled, and whether results could be recorded row by row. Each `send()` has its own future |
| `linger.ms=0` | The client default | A real gain one at a time, close to nothing in batches. The user's challenge that it looked like an extreme trade-off exposed that it wasn't a reliability setting at all |
| `@Version` for winning a row | `FOR UPDATE SKIP LOCKED` | The user's preference for the idiom. Measured: two relays split the work rather than collide |
| Local resume endpoint and a JVM lock | One documented SQL statement | The user asked why an endpoint was needed. The lock then had no job, and wouldn't have extended to several relays |
| A relay producer with its own settings and a dedicated template | Two lines in `kafka.yml`, and the shared template | The user valued simplicity over overrides |
| `TransactionTemplate` in one class | Two beans with annotations | The user didn't want transaction control mixed into business logic |
| T = 30 s, then 25 s | 21 s | 30 s had no measurement behind it, and the user doubted it. A timed restart gave about 16 s. 25 s came from confusing `linger.ms`'s 5 ms with 5 s, and running the real client settled it |
| No retry limit, and error classification | 20 attempts, no classification | The user asked for a limit. With one, the classifier only duplicated it |
| `OrderEventOutboxRecord`, the user's suggestion from reading namastack, which the assistant then recommended | `OrderEventOutbox` | The assistant's own note that "record" is also a verb led the user to reopen it. The word already has two meanings here, and the assistant reversed its recommendation |

Two other things worth telling:

- **The index isn't about speed.** Without `(status, next_attempt_at, id)`, the first `SKIP LOCKED` claim locks what it scans and a second claim gets nothing. An index that looks like a tuning decision decides correct behaviour.
- **`SKIP LOCKED` alone doesn't protect a row during the send.** The lock ends when the claim commits, and the send happens afterwards. The lease is what covers the send.

## The closing comments

Added below by each ticket.

### Issue 199: record the relay's decisions

Written in the interview's session rather than a fresh one, because the reasoning behind the rejected options was only there. `/grilling` had been run without `/domain-modeling`, so nothing had reached the repo during the interview, unlike step 1, whose documents came before its spec.

- ADR 0035, section 13 of the order outbox design, and dated notes on ADR 0034 (rule 9, rule 10, a revisit condition) and the design (status, the 1.1 table, 1.2, 2, 4, 7 and 10).
- The probe note holds the raw results and the three probe sources. The corrected batch run's source isn't saved: the note describes how it differs from the first version.
- One deviation from the ticket: `CONTEXT.md` changed, though the ticket said nothing outside `docs/` would. The glossary gains 막힌 이벤트 (BLOCKED), because the domain language now has that state. 중계 and 발행하다 still hold, and the last ticket checks them against the code.
- Still unverified, and the next ticket's first job: that Hibernate emits `for update skip locked` from the lock hint.

### Issue 200: a confirmed order's event reaches the broker

Calling `OrderEventRelayer.publishDueEvents()` by hand now takes a row left by a real confirmation to the broker and then to `PUBLISHED`. Nothing schedules it yet.

**The unverified assumption holds.** Hibernate 7.4 turns the lock timeout hint of -2 into `… order by oeo1_0.next_attempt_at,oeo1_0.id limit ? for update of oeo1_0 skip locked`.

**One difference from the criteria:** the statement ends in `for update of oeo1_0 skip locked`, not the literal `for update skip locked`. On one table the two lock the same rows, so the test pins the real form with a regex. ADR 0035 and design 13.2 have dated notes. Issue 204's two-claims scene is what will exercise this form with two open claims, since the probe's no-overlap result came from bare SQL.

**Other choices:**

- `BLOCKED` isn't in the enum yet, because issue 202 adds it. So the "other statuses" and "each status" tests reach `PUBLISHED` only. The code already answers correctly for `BLOCKED`, but no ticket's criteria cover `startPublishAttempt` and `markPublished` on it. They should go in with issue 202.
- A round claims one batch, and draining is issue 204's. A failed rebuild, send or future leaves the round as an exception, and the rows it took come due when their leases end, until issue 201 makes each one that row's result. The adapter's KDoc says so.
- The whole-record scene is in `OrderEventRelayerTest` rather than an adapter-only class, so it starts from a real confirmation and costs one context less. That closes design 7's envelope gap.
- The index test reads `information_schema` for the columns and their order. Its behaviour is issue 204's scene.

**Red checks, local edits run and reverted, none committed:**

| Edit | What went red |
| --- | --- |
| `NEVER` → `REQUIRED` on the relay | the refusal scene |
| Lock timeout hint removed | the `skip locked` statement scene |
| `next_attempt_at <= now` → `<` | the clock scene, and two scenes whose row is due at exactly the pinned instant |

**Final check:** `ktlintCheck` and `check` on commerce-api passed: 84 classes, 983 tests, 0 failures, 0 errors, 0 skipped. `HexagonalArchitectureTest` is unchanged. commerce-streamer wasn't run, since nothing it reads changed here. `kafka.yml` is issue 205's.

### Issue 205: the relay runs by itself when enabled

`OrderEventRelayScheduler`, in the new `adapter.scheduling` layer, calls only `OrderEventRelayer`. It's off by default, on under `local` and off under `test`. `kafka.yml` gains `delivery.timeout.ms: 21000` and `max.block.ms: 1000` under the producer's `properties`, and nothing overrides them in Kotlin.

**Manual run, under `local` against the compose broker (`bitnamilegacy/kafka:3.5`).** With no PG running, the row was committed by hand with an `INSERT`, not by a confirmation. Nothing else touched the app. The row became `PUBLISHED` with `attempt_count` 1 on the `scheduling-1` thread, and the console consumer read its record at **partition 0, offset 0**. The producer's logged values were `delivery.timeout.ms = 21000`, `max.block.ms = 1000`, `linger.ms = 5`, `request.timeout.ms = 20000` and `retries = 3`.

**What an in-flight round does when the app stops.** One run each, with SIGTERM to the jar. The broker was held with `docker pause` so that a round was certainly in flight, and SIGTERM came 1 s after the row was claimed.

| Case | Time to exit | Row at exit | Records |
| --- | --- | --- | --- |
| Broker stays paused | 39.9 s | `PENDING`, attempt 1, lease untouched, no `last_error` | 2 after the restart below |
| Broker returns 3 s after SIGTERM | 3.2 s | `PUBLISHED`, attempt 1 | 1 |

- **Shutdown waited for the round in both.** In the second case the log runs `Published` (03.276), producer closing (03.315), JPA closing (03.333), so the ack was still saved. I read that order from the logs, and didn't trace which Spring lifecycle step does the waiting.
- **The 39.9 s is two waits.** The record expired at its 21 s delivery timeout, about 20 s after SIGTERM, and the round left as an exception. Closing the producer then took 20 s more on a metadata request that a paused broker couldn't answer.
- **The first case recovered by itself.** After a restart with `ddl-auto=none`, the row went to `PUBLISHED` with attempt 2 once its lease ended. It has two records with the same event ID, at offsets 0 and 1 of partition 1. The client had given up on the first, but its request was already in the socket, and the paused broker processed it on resume. So a delivery timeout doesn't mean the record is absent.

Nothing worse than a republish after the lease appeared, so there's nothing to raise. Three limits on that answer:

- **Measured before issue 201.** Failures still leave the round as an exception here. After 201 the first case should save a failure and a backoff if the database is still up, so it needs measuring again. Nobody owns this yet, and issue 206 is the natural place.
- **`kill -9` wasn't run.** A supervisor with a grace period under 40 s would reach it during a broker outage. A republish after the lease is what the design predicts, and it's unobserved at the process level.
- **Boot's shutdown phase timeout wasn't exercised.** Neither run reached the point where a long round outlasts it. Issue 204's draining makes rounds longer, so a stop during a backlog is worth a look there.

**A trap in the enabled scene.** Spring caches a context after its class ends, so an enabled context's scheduler would go on claiming from the shared MySQL during later classes, and publish rows they expect to find `PENDING`. `OrderEventRelaySchedulerTest` closes its context with `@DirtiesContext`, which is the first use in this repo. `CODING_STANDARDS.md` lists the annotations a subclass may add as a closed list, so it changed too, along with the new test shapes.

**Choices and additions:**

- "One thread" is Boot's default scheduler pool of one, set nowhere in this repo. `OrderEventRelaySchedulerTicksTest` observes it on the real scheduler with a relayer that takes 1.5 s: one round at a time, the same thread, and at least 1 s from one round's end to the next round's start.
- The layer rule can't express "only the ports", because `OrderEventRelay` and a required port are "the application layer" to it. A second rule allows only `application..provided..`, which is stricter than the ticket's wording on required ports.
- Not in the criteria: `a round that throws doesn't stop the next tick`, because the design relies on it, and `OrderEventRelayEnabledPropertyTest`, which reads each profile's value from the real `application.yml`. The default profile falling to `local` is an accepted limit (design 13.10), and no test pins it, so that a later fix fails nothing.
- 20,005 ms is the real floor: the client starts at that value and refuses 20,004 ms with a `ConfigException`.

**Red checks, local edits run and reverted, none committed:**

| Edit | What went red |
| --- | --- |
| No trigger class | the enabled scene, after its 10 s wait |
| No `kafka.yml` lines | the test reading the two values |
| No `local` value | the property test |
| The trigger depends on `OrderEventRelay` | `schedulingReachesOnlyProvidedPorts` |
| `fixedDelay` → `fixedRate` | the ticks scene: 0.0009 s between rounds against 1 s |

My first `fixedRate` run was invalid. I made it together with the dependency edit, and the ticks scene timed out only because that edit left the context with no bean to inject. Run alone, it failed on the gap.

**Final check** (`scripts/final-check`, on the code as committed, before this comment was added): ktlint passed. commerce-api: 88 classes, 993 tests, 0 failures, 0 errors, 0 skipped. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped.

### Issue 201: a failed publish stays with its own row

A rebuild failure, a `send()` that throws and a failed future now all become that row's result. The row stays `PENDING` with `last_error` and a backoff of 5 s doubling to a 5 minute cap, and the rest of its batch publishes. The relay still doesn't catch database exceptions.

**The criteria's wording conflicted, and a test found it.** The ticket says the failure text names "the exception's type and message and never the payload". Jackson's message quotes the value it refused, so both can't hold. The first fix checked the root cause for a Jackson type, and a scene with a bad date still went red: unwrapping went past Jackson to `DateTimeParseException: Text 'secret-value' could not be parsed`. Now any failure with Jackson in its chain names Jackson's type and the JSON path, such as `tools.jackson.databind.exc.MismatchedInputException: at …OrderConfirmedMessage$Payload["orderId"]`. Every other failure names its root cause's type and message. ADR 0035 rule 4 and design 13.3 have dated notes.

**One overlap with issue 202.** The criteria ask for `markFailed`'s no-op on `BLOCKED`, and the ticket says no test overwrites `status`, so the only honest way to reach `BLOCKED` is 20 failures. So this ticket adds the enum value, `MAX_PUBLISH_ATTEMPTS` and the 20th failure's transition, with entity tests for the 19th and 20th. Issue 202 still owns the distinct WARN, the scenes and the SQL. Until then a row that becomes `BLOCKED` logs only the ordinary WARN. The publisher can't see that transition, so 202 has to put its WARN somewhere that can.

**Other choices:**

- The failing sends are in `OrderEventRelayFailedSendTest`, because a spy on `KafkaTemplate` makes a context of its own. Everything past the template is real, so a healthy row still reaches the broker. `CODING_STANDARDS.md` has dated notes for the spy and the class's dependencies.
- `prepareOrderEventOutbox` and `committedOrderEventOutbox` are on `BaseCommittingApplicationServiceTest`, since two classes use them. The two classes still repeat their log capture and `TopicReader` setup.
- The later-success scene can't use the unrebuildable row, since `payload` never changes and no test writes `status`. It fails the send once and then lets it through.
- A row with no attempt waits like the first attempt rather than throwing. Only a bug gets there, and a throw would end the round.

**Red checks, local edits run and reverted, none committed:**

| Edit | What went red |
| --- | --- |
| `send`'s catch rethrows | 9 scenes, including the throwing send and the rebuild scenes |
| The await's catch rethrows | 11 scenes, including the failed future |
| Names the outermost wrapper, not the root cause | 3 scenes |
| Keeps Jackson's message | 6 scenes, including all 4 payload cases |
| `markFailed` writes on any status | the `PUBLISHED` and `BLOCKED` no-ops |
| Backoff cap removed | the backoff sequence and the 19th failure |
| Blocks at the 21st failure | the 19th/20th test and the `BLOCKED` no-op |
| `save` removed from `markFailed` | nothing |

The last one survives because the row is loaded inside the transaction, so JPA flushes it anyway. The explicit `save` follows the repo's convention, and `markPublished` is the same. These runs came before the review's fixes, which changed names, helpers and the no-attempt case.

**Rebased onto issue 205.** That ticket landed while this one was in progress. Its table of an in-flight round at shutdown was measured before this failure path existed, so its "no `last_error`" row describes the code as it was then. A round that now reaches its delivery timeout would record a failure, and I didn't measure that again.

**Final check:** `ktlintCheck` and `check` on commerce-api passed on the rebased code: 89 classes, 1012 tests, 0 failures, 0 errors, 0 skipped. Only this section's wording changed after that run. commerce-streamer wasn't run, since nothing it reads changed here.

### Issue 203: a stopped relay's rows are published again by a new relay

`OrderEventRelayerTest` gains the assignment's last three stop scenes. They add no main code: the tracer's order of send, then save, already behaves this way, so the scenes passed once they compiled, and their red evidence is the check below. These are controlled stops. The JVM stays up, no process is killed, a restart is a new `OrderEventRelay` on the same database and topic, and no scene writes `status`. Purchase activity is step 3's.

**One run's lines, from `scripts/scene-lines` on the final check's reports:**

```text
Relay stop, after the commit before the relay: order 1 CONFIRMED; row order-confirmed:v1:1 PENDING attempts 0 published_at null next_attempt_at 2026-10-27T01:02:03.123456Z; records 0
Relay stop, a new relay: row order-confirmed:v1:1 PUBLISHED attempts 1 published_at 2026-10-27T01:02:08.123456Z next_attempt_at 2026-10-27T01:03:08.123456Z; record key 1 partition 0 offset 0
Relay stop, after the ack before PUBLISHED: after the stop: row order-confirmed:v1:1 PENDING attempts 1 published_at null next_attempt_at 2026-10-27T01:03:08.123456Z, record key 1 partition 0 offset 5; inside the lease: row order-confirmed:v1:1 PENDING attempts 1 published_at null next_attempt_at 2026-10-27T01:03:08.123456Z, new records 0; past the lease: row order-confirmed:v1:1 PUBLISHED attempts 2 published_at 2026-10-27T01:03:09.123456Z next_attempt_at 2026-10-27T01:04:09.123456Z, record key 1 partition 0 offset 6
```

The two records of one event sit at offsets 5 and 6. The scene asserts that their key, partition and value are equal. It compares the value as a UTF-8 string, which is how `TopicReader` reads it. The lease is tested on both sides: one microsecond before it ends nothing is published, and one second after it ends the row goes out again.

**Red checks, local edits run and reverted, none committed:**

| Edit | What happened |
| --- | --- |
| `markPublished` before the send | The stop scene went red with no record on the broker, because the stub threw before the send. The other seven scenes had no objection in the clean run: a round that isn't stopped can't tell the two orders apart |
| The same, plus a stop after `PUBLISHED` committed and before the send, with a temporary scene | The row was `PUBLISHED` at attempt 1 with 0 records. A new relay's round an hour later left the row as it was and still published 0 records |

The first edit alone doesn't show a loss: its stop lands before the commit, so the row stays `PENDING` and would come due again. The second is the counterexample to "PUBLISHED first reduces duplicates". It does remove this stop's duplicate, but what it loses is the event: the row says published, nothing is on the broker, the claim never takes the row again, and no error remains. Sending first turns the same stop into a duplicate, which the consumer's idempotency handles.

**Choices:**

- The stub is a private function in the test class rather than in `com.loopers.support`, because `CODING_STANDARDS.md` keeps a stub one class uses in that class. A first draft had its own file, and the review moved it. It throws `DataAccessResourceFailureException`, because the same path is the assignment's "state transaction fails after the broker's success".
- The spy goes in the existing class, which already has a context of its own for its topic, so a separate class would start one more.
- `TopicReader`'s KDoc said it returned everything since it was opened. Successive calls return what arrived since the last read, and the stop scene relies on that, so the KDoc now says so.

**Failures on the way, not caused by this change:** three runs failed on a machine with several sessions at work (load average 128 right after the first, 22 after the third). Each was in a class's or JVM's cold start:

| Run | Failure |
| --- | --- |
| The relay class | The JVM's first scene: its PG create hit `SocketTimeoutException`, so the payment stayed unknown, no row was written, and `committedOutbox()`'s `!!` threw an NPE. Cleaning up Redis also failed to connect |
| The first red check | The JVM's first scene again: Redis failed to connect in its cleanup. That's not the red edit's doing, since the second red check's run passed that scene with the same edit |
| A full check | `OrderAdminApiTest`, a class this change doesn't touch: a PG create hit the same timeout, so the payment was `UNKNOWN` rather than `PENDING` |

The next two full checks passed with no code change that could affect those scenes. Load as the cause is an inference from the timeouts and the load average, and I didn't reproduce it on purpose. The scene's helper doesn't check that the confirmation left a row, so the first failure reads as an NPE. Nothing was changed for this, and it's worth knowing when a scene fails on a busy machine.

**Rebased onto issues 205 and 201.** Both landed while this one was in progress. The scenes now read rows through the base class's `committedOrderEventOutbox`, and they sit beside issue 201's failure scenes in the class. The red checks ran before the rebase and weren't repeated, so they say nothing about issue 201's failure path. The relay's order of send, then save, is the same after it, and a save's exception still leaves the round. The lines above were the same in the rebased run.

**Final check:** `ktlintCheck` and `check` on commerce-api passed on the rebased code: 89 classes, 1015 tests, 0 failures, 0 errors, 0 skipped. Only this section's wording changed after that run. commerce-streamer wasn't run, since nothing it reads changed here.

### Issue 204: rounds drain a backlog, and concurrent claims never take the same row

A round now claims until a batch comes back short, so 45 rows are all `PUBLISHED` after one call. Two open claims took rows 1–20 and 21–40 of 500.

**The first scene overturned part of ADR 0035.** The two-claims scene started at 45 rows, and the second claim came back empty with the index in place. The ADR's "with the index they split" had been measured at 2,000 rows. A throwaway run on the real query found this statement table-scanned at 150 rows and used the index from 200. At 45 rows the optimizer's plan changed with the statistics: a filesort locked everything and the second claim got 0, while a primary-key scan stopped at the limit and it got 20. So the index is necessary, not sufficient. Nothing changed in the code, since a second claim that gets nothing only stops early and the single relay has none. The scenes use 500 rows, and the ADR, the design, the entity's KDoc and the probe note say so. The choice remains the optimizer's, so a much smaller table or a changed statement could make the scene false-red.

**Red checks, local edits run and reverted, none committed:**

| Edit | What went red |
| --- | --- |
| The index removed from the entity | the two-claims scene, whose second claim was `[]`, and the `information_schema` test |
| The lease removed from `startPublishAttempt` | the leased-row scene, the two-relay scene and the published scene's `next_attempt_at` |
| `order by o.nextAttemptAt, o.id desc` | the order scene |
| `o.id` dropped from the order | **nothing.** MySQL still returned tied rows in ID order, so the scene refuses a reversed tie break but not a missing one. Its KDoc says so |
| `markFailed`'s guard removed | the late-result scene: the row stays `PUBLISHED`, since `markFailed` never writes that, but it gains a `last_error` and a new `next_attempt_at` |
| The claim's query takes `status <> PUBLISHED` | the not-due scene, on the `BLOCKED` row |

**The late result.** The first relay's claim stands for one still waiting on the broker when its lease ends, the second relay's round publishes the row, and then the late failure arrives through `OrderEventOutboxModifyService`. It's called directly because holding a real send past the lease would need a stubbed publisher. So this shows the state rule, and no relay was actually stalled.

**Two relays.** The scene asserts one record per row and `attempt_count` 1, and its KDoc says this is not a supported setup. It doesn't assert how the rows were divided, since that depends on timing. In the final run the relays published 260 and 240 of 500.

**The event columns.** The new scene overwrites them by reflection in the same transactions as a real `startPublishAttempt`, `markFailed` and `markPublished`. Without the overwriting, Hibernate would rewrite the values they already hold and the test couldn't fail.

**What the review changed.** The spec review caught a false sentence of mine in the round's KDoc, "no row is claimed twice within a round". It doesn't hold in an outage, and my first correction overstated the other way, before issue 201's backoff was in view. A batch there takes a 21 s delivery timeout, so failed rows can come due again before a short batch, and the round goes on. The doubling backoff ends that for a modest backlog: once a row waits longer than the rest of the backlog takes to go round, a batch comes back short. At the 5 minute cap that span is about 15 batches of 20, so roughly 300 rows or more keep the round going until the broker returns or rows reach `BLOCKED`. This is reasoned from the numbers, not measured. The timeout paces it, so it doesn't spin, but "1 s rest between rounds" doesn't describe a large backlog in an outage. The standards review caught `tally` missing from the start-gate scene and `CODING_STANDARDS.md` naming `OrderEventOutboxImmutabilityTest` as the only class that commits in the JPA slice.

**Rebased onto issues 205, 201 and 203.** The late-result scene waited for 201's `markFailed` rather than writing a competing copy. 201 also added `BLOCKED` and its transition, so the not-due scene covers it without waiting for 202, reaching it through 20 real failures rather than writing the status.

**Not looked at:** issue 205's closing comment asked what a stop during a backlog does, since draining makes rounds longer and neither of its runs reached Boot's shutdown phase timeout. The round's loop doesn't check for shutdown, and no run here stopped the app during a drain. Issue 206 is the natural place, alongside 205's other re-measurement.

**The flake issue 203 recorded, seen here once too:** the JVM's first PG create hit `SocketTimeoutException` while several sessions were running, so the order stayed payment-unknown and left no row, and the scene that ran first failed on a null row. It didn't recur in the later full runs.

**Final check:** `ktlintCheck` and `check` on commerce-api passed on the rebased code, before this comment's last edits: 90 classes, 1022 tests, 0 failures, 0 errors, 0 skipped. commerce-streamer wasn't run, since nothing it reads changed here.

### Issue 202: a row that keeps failing becomes BLOCKED, and someone can resume it

A row failing 20 times is `BLOCKED` with its `last_error`, isn't claimed again, and doesn't hold up other rows. The transition logs its own WARN once, alongside the 20 ordinary ones. The one way back is the SQL statement in design 13.7, and the scenes read that statement from the document rather than copying it. Issue 201 had already brought in the enum value and the transition, so the only new main code here is the WARN in `OrderEventOutboxModifyService.markFailed`, the one place that sees the row on both sides of a failure.

**Running the documented statement found a bug in it.** The design had `next_attempt_at = NOW(6)`. The columns hold UTC (`hibernate.jdbc.time_zone`), and `NOW(6)` follows the session's time zone. The test container's session is UTC, so the first resume scene passed and couldn't have shown this. I suspected it from reading the config, and wrote a scene that runs the statement in a `+09:00` session:

```text
Expecting actual:
  2026-11-04T14:30:36.208006Z
to be close to:
  2026-11-04T05:30:36.213697Z
within 1 Minutes but difference was 539 Minutes
```

So on a server set to Seoul, a resumed row would have sat for 9 hours with nothing explaining why, on an app whose JVM is set to Seoul. The statement now says `UTC_TIMESTAMP(6)`. No server here was observed in that zone: the compose file sets no time zone, and the `+09:00` comes from the scene. Design 13.7 has a dated note, and a new line says the same trap applies when working out a wait from `oldest_created_at`.

**One scene writes `status`, on purpose.** The rule against it is about the stop scenes, where it would fake a recovery. This scene is running the documented manual procedure, and its KDoc says so.

**A reading of the criteria.** "Oldest wait" is `MIN(created_at)`, a time and not a duration, because that's what the design's statement returns. The scene checks it against literal `created_at` values it sets itself, since that column follows the system clock.

**Other choices:**

- The scenes are in `OrderEventRelayFailedSendTest`, because a blocked row needs its sends to fail and later succeed, and its `payload` never changes. The same spy does both, and a new class would start another context.
- `apps/commerce-api/build.gradle.kts` declares the document as an input of the test task. Without that, a doc-only edit would leave `check` UP-TO-DATE and the scenes wouldn't run, which defeats reading from the document. The cost is that any edit to that file, dated notes included, runs the app's tests again.
- The statement is found by its SQL comment, so those comment lines now have a job. A renamed comment fails the scene with a message naming it.
- `startPublishAttempt` and `markPublished` on `BLOCKED` are in no ticket's criteria, and go in here as issue 200's section asked. They passed on arrival: the code already answered correctly.
- `CODING_STANDARDS.md` has two dated notes: the helper that calls the relay's port as preparation, because only real failures may reach `BLOCKED`, and the class's two new dependencies. The late-failure scene calls `OrderEventOutboxModifyService` directly, because a round can't stage a relay past its lease.
- Nothing deletes a row: the outbox's port and services have no delete, and the backlog scene still counts all four rows at the end.

**Accepted, and worth knowing:** the WARN is logged inside the transaction, before the commit. If that commit fails, the line says `BLOCKED` while the row is still `PENDING`. The row then fails again and logs again. An after-commit hook costs more than that risk, so I left it.

**Red checks, local edits run and reverted, none committed:**

| Edit | What went red |
| --- | --- |
| The WARN drops its check of the status before the failure | the late-failure scene |
| Doc only: the resume statement also sets `attempt_count = 0`, and the backlog statement says `MAX(created_at)` | both resume scenes and the backlog scene |
| Doc only: `NOW(6)`, as first written | the `+09:00` scene: 539 minutes |

The second row also shows the Gradle input working: nothing but the document changed, and the test task ran instead of staying UP-TO-DATE. The first two ran before the review's fixes, which moved the document-reading helper into the test class and changed the WARN's wording. The third ran with that helper in its first home.

**Not exercised:** a real broker outage reaching `BLOCKED`. The failures are injected at the template, and the 77 minutes is arithmetic from design 13.4. `TestClock` moves through the rounds in seconds.

**Rebased onto issue 204.** It landed while this one was in progress, and a round now claims until a batch comes back short. That doesn't change these scenes' rows, since each batch here is short, and `CODING_STANDARDS.md` and this file needed only their additions merged. The red checks ran before the rebase and weren't repeated.

**Final check** (`scripts/final-check --app commerce-api`, on the rebased code, before this paragraph and the one above were added): ktlint passed. commerce-api: 90 classes, 1032 tests, 0 failures, 0 errors, 0 skipped. commerce-streamer wasn't run, since nothing it reads changed here.

### Issue 206: step 2's records match what was built

The last ticket. Its only commit outside documents is `TopicReader`'s KDoc, `8ee98a2`. Everything after that is documents.

**Stopping the app, measured again, and further.** Issue 205 asked for this once issue 201 landed, and issue 204 added a stop during a drain. All on the real jar under `local` with the compose broker, one run each:

| Case | Time to exit | Row at exit | After a restart |
| --- | --- | --- | --- |
| Broker paused, SIGTERM 1 s after the claim | 40.1 s | `PENDING`, attempt 1, `last_error` a `TimeoutException`, next attempt at failure + 5 s | `PUBLISHED`, attempt 2 at once, with no lease to wait for. 3 records for 2 rows |
| The same, broker back after 3 s | 3.1 s | `PUBLISHED`, attempt 1 | Unchanged. 2 records for 2 rows |
| 60,000 row backlog, SIGTERM at 1,034 published | 30.1 s | 11,052 `PUBLISHED`, 8 `PENDING` with attempt 1, 11,060 records | 60,000 `PUBLISHED`, 60,008 records, those 8 at attempt 2 |
| 3,000 row backlog, SIGKILL at 1,020 published | at once | 1,020 `PUBLISHED`, 20 `PENDING` with attempt 1, 1,020 records | 3,000 `PUBLISHED` after about 52 s, 3,000 records, those 20 at attempt 2 |

- The first row confirms issue 205's guess about issue 201: the failure is saved, so the row has a backoff rather than a lease.
- **The third row is the finding.** The round's loop doesn't look at shutdown, so a SIGTERM drains on for Boot's 30 s phase limit, publishing 10,018 more rows. Then the context closes around the running scheduler thread, the next save throws, and rows acked in that last batch go out again after their lease. From the code that's at most one batch. It measured 8 rows.
- The fourth row is the first `kill -9` at the process level. Its republish after the lease happened, but the kill fell before the batch reached the broker, so it shows no duplicate. That's luck of timing.

Nothing was worse than a republish, which the consumer's idempotency handles, so nothing changed. A shutdown check in the loop would save the 30 s and a few duplicates, and nothing needs that now. Not measured: a stop during an outage with more than one batch due.

**Throughput.** About 336 rows/s sustained, over 48,948 rows, against 480 for the bare SQL on the same images. A batch takes about 60 ms rather than 40. From a JVM that had just started, 271 with `show-sql` off and 293 with it on. That ordering is noise from single runs. The gap also includes an INFO line per row, so it isn't Hibernate's cost alone.

**`TopicReader`'s 1 s stays.** The ticket asked to look at it against polling, and polling turned out not to bear on it: scenes run rounds by hand, a round has awaited its sends when it returns, and the one scheduler scene reads a row already `PUBLISHED`. A temporary line over the relay's three classes counted 22 reads, 2 ms median and 20 ms at most to read what was there, 0 extra records and 22 s of waiting in total. Too short a wait fails silently, as a scene expecting nothing passes on a busy machine. Reading only up to the end offsets would drop the wait, but it misses a send a bug left unawaited. The KDoc now says this.

**`CONTEXT.md`, against the code:**

- **중계** changed. "한 번에 하나만 돈다" claimed more than the code provides: rounds don't overlap within an instance, but nothing stops a second instance, and `OrderEventRelayerTest` runs two on purpose. It now says the relay is on one instance, and that this is a setting rather than ownership.
- **발행하다** gained a sentence: the broker's confirmation is what makes it published, and published doesn't mean recorded as purchase activity. It avoids the word 소비, because the streamer's glossary keeps that for reading a record. The rest holds.
- **막힌 이벤트** holds. Its "정해진 횟수" is `MAX_PUBLISH_ATTEMPTS`, and the resume is the one way back.
- **누락 구간** and **아웃박스** hold.

**Retention.** Design 13.8's condition is met today, because the streamer's processed order confirmation has no retention. Writing it down turned up two things nobody had said. A `BLOCKED` row can wait indefinitely, so the republish window has no upper end until it's resolved, and a redelivery doesn't depend on the row at all, so a limit on those records has to outlast the topic's retention too. And step 3's recovery adds rows that are missing from stored facts, so a cleanup that deletes `PUBLISHED` rows has to be thought through with that recovery. Both come from reading the code and the assignment, and no scene runs them.

**What the review changed.** The spec review checked the numbers against the raw lines. It found the long drain's row count wrong, at 48,956 where the log has 48,948: I had added the 8 republished rows, which were already among them. The rate didn't move. It also found a row of the differences table citing the spec for what was issue 201's criterion, and throughput listed as a difference when the spec had said it was bare SQL. So there are seven differences rather than eight. The PR draft had joined two scenes as one run without saying so, and the retention note left out redelivery. The standards review caught `소비` and `보내다`, which the glossaries avoid, in new Korean text. All of these are fixed. Its duplication smell stays: the counts repeat across the design, this file and the PR draft, as they did in step 1.

**Final check:** `scripts/final-check` on `8ee98a2`, a clean tree, started 2026-11-04T06:05:08Z, `BOOTSTRAP_SERVERS` unset: ktlint passed. commerce-streamer: 18 classes, 67 tests, 0 failures, 0 errors, 0 skipped (50 s). commerce-api: 90 classes, 1032 tests, 0 failures, 0 errors, 0 skipped (3 min 45 s). Each filtered command in design 13.13 ran from that tree and gave its count.

**Red checks:** none. This ticket changes no behaviour, and the KDoc edit can't fail a test.

### Spec 198: where the implementation differs

Step 2 is built. These are the differences from the spec and its tickets. Six have a dated note where a reader would go wrong, the seventh is in `OrderEventRelay`'s KDoc, and design 13.12 lists them.

| The spec or ticket said | What is there | How it was found |
| --- | --- | --- |
| The claim emits `for update skip locked` | `for update of <alias> skip locked`, which locks the same rows on one table | The statement scene, issue 200 |
| With the index, a second open claim gets the next rows | The index is necessary, not sufficient. The statement table-scanned up to 150 rows, and at 45 rows a filesort emptied the second claim with the index present. A second claim that gets nothing only stops early, so no code changed, and scenes about the split use 500 rows | The first two-claims scene, issue 204 |
| Issue 201: the failure text names the exception's type and message | With Jackson in the chain it's the type and JSON path, because Jackson's message and its cause's quote the payload | A bad date's scene, issue 201 |
| The adapter holds the INFO and WARN lines | The transition's WARN is in `OrderEventOutboxModifyService.markFailed`, the one place that sees the row on both sides of a failure | Issue 202 |
| Resume sets the next attempt to now, written as `NOW(6)` in the design | `UTC_TIMESTAMP(6)`. In a `+09:00` session `NOW(6)` delayed the row by 539 minutes | A scene running the design's own statement, issue 202 |
| The whole-record check is an adapter test | It's in `OrderEventRelayerTest`, starting from a real confirmation, which costs one context less | Issue 200 |
| A round ends and rests 1 s | True unless a large backlog meets an outage. Past about 300 rows, failed rows come due before a short batch, and the delivery timeout paces the round instead. Reasoned, not measured | The spec review of issue 204 |

**Order of work.** `BLOCKED` and the 20th failure's transition came in with issue 201 instead of 202, because 201's criteria needed the no-op on `BLOCKED` and no test may write `status`.

**Settled from "to check in implementation":**

- Hibernate emits the lock from the hint: yes.
- Throughput through Hibernate, which the spec's bare SQL left open: about 336 rows/s against 480.
- An in-flight round at shutdown: a republish after the lease at worst, in every case measured. A drain runs on for up to 30 s after SIGTERM. Design 13.11 has the table.
- The graceful restart that once failed with `Topic not present in metadata`: not investigated further, and it didn't recur.

**Additions the spec didn't ask for:** `@DirtiesContext` on the enabled scheduler scene, since a cached context would keep claiming rows during later classes; a second architecture rule, that scheduling reaches only provided ports; and the design document as a Gradle input of the test task, so a doc-only edit runs the scenes that read its SQL.

**Not exercised:** a real broker outage reaching `BLOCKED`, since the failures there are injected at the template and the 77 minutes is arithmetic; a stop during an outage with more than one batch due; and anything past the broker, which is step 3's.

The spec stays open for its closing review.

### Spec 198: the closing review

Recorded 2026-11-05 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198#issuecomment-6650986545) of [spec 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198). The text is the comment's, with its blob URLs and its headings one level down.

Step 2 of week 7 is built, and its closing review is done. `scripts/final-check` on `7ec8227`, a clean tree, started 2026-11-05T05:49:08Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-streamer 18 classes, 67 tests (48 s), commerce-api 90 classes, 1031 tests (3 min 45 s), 0 failures, 0 errors, 0 skipped in both**. Only documents changed after `7ec8227`.

The spec's differences are in the [earlier comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198#issuecomment-6624177023). This one adds what the review found, and corrects that comment on one point.

#### What the review covered

A two-axis review of `e6a11ac...8ee98a2`, with one reviewer on the standards and one on this spec. `b322b62` added only documents and had issue 206's own review. The standards axis reported 12 findings, 5 of them hard violations, and the spec axis 6. The hard violations and the spec findings were checked against the code, and each finding was then decided one by one.

#### A correction: a broker restart can fail rows

The earlier comment says the graceful restart that failed with `Topic not present in metadata` "didn't recur". It does. Nothing had met a real restart at `max.block.ms: 1000`, so this review measured it through the real jar, with new rows coming due throughout:

| | Shipped, `rebootstrap` | `metadata.recovery.strategy=none` |
| --- | --- | --- |
| Graceful restarts with failed rows | 5 of 18 (14 rows) | 0 of 12 |
| `kill -9` restarts with failed rows | 2 of 6 (28 rows) | 0 of 3 |
| Longest wait, graceful | 10.0 s | 5.8 s |
| Longest wait, `kill -9` | 21.4 s | 16.8 s |
| Rows lost or duplicated | 0 | 0 |

On one broker, every restart makes the client rebootstrap, which drops the topic's metadata. A `send()` that comes before it returns blocks for 1 s and fails. The rows went out on their second attempt, so the cost is a WARN and an ERROR per row, a 5 s backoff and 1 of 20 attempts.

**Story 31, "a short restart to pass without failed attempts", isn't met, and that's accepted.** `none` passed all 15 restarts, but it's only needed because there is one broker, so it wasn't added. Several brokers weren't measured. The limit is in [design 13.10](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/outbox-relay/docs/design/order-outbox.md#1310-받아들인-한계), and the raw lines are in the [probe note](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/outbox-relay/docs/research/week7-outbox-relay-probe.md#a-broker-restart-at-the-shipped-limits).

#### Also accepted, not fixed

These are in design 13.10 too.

- **Story 16, "a late result never to overwrite a row's newer state".** `markFailed` checks the status only. A failure from a relay past its lease, on a row another relay has re-claimed, overwrites `last_error` and cuts that relay's lease. The worst case is a republish, which the consumer handles, and it needs two relays at once. The spec's state table allows it. This was read from the code and not run as a scene.
- **Story 26's WARN comes before the commit.** If that commit fails, the line says `BLOCKED` on a row that is still `PENDING`. It takes a database failure at that moment, on a row's last attempt.
- **A second line per failure.** Spring Kafka's `LoggingProducerListener` logged an ERROR for each failed send in the restart runs. Its text holds the key and the message's first 100 characters, which stop before the body, so story 27 holds. Anyone alerting on `ERROR` would need to read it as a failure that gets retried.

#### What changed

No behaviour changed.

- `claimDueOutboxes` saves through `saveAll`, and `markFailed` reads what `save` returned (catalog design 5.37).
- `retryBackoffAfter` lost `coerceAtLeast(0)` and the one scene that pinned it, which is the one test fewer. Production can't reach attempt 0, and a bug there now throws, as `ensurePending` does.
- The committing base's helper is now `prepareOrderEventOutboxWithoutOrder`, and it sets its field (`CODING_STANDARDS.md`).
- `OrderEventRelaySchedulerTest` uses the base's `committedOrderEventOutbox`.
- `findDueForUpdate`'s KDoc says why it returns a `List`, after measuring it: with a `Slice`, a second open claim began at row 22 rather than 21.
- `CODING_STANDARDS.md` names two more exceptions: `OrderEventOutboxRepositoryTest`'s `@Import(SqlCaptureConfig)`, and two scenes that arrange through the relay.

#### Left as they were

- **Three one-class change helpers in `BaseRepositoryTest`.** Six others are already there, so moving these would create the inconsistency.
- **English log messages in `OrderEventOutboxModifyService`.** The application layer's other messages are Korean, but the relay's lines share their wording, so a search for one event's lines finds them all.
- **`inNewTransaction` in two repository tests.** A shared one would have to be `REQUIRES_NEW`, for three duplicated lines.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/outbox-relay/docs/research/week7-outbox-relay-writing-material.md#spec-198-the-closing-review).

## Draft PR body

A draft for the PR from `feat/outbox-relay` into `week7`, in the shape of the step 1 PR, with the Loopers guide's review points and the assignment's submission items. Updated after the spec's closing review. The checklist waits for step 3's upstream PR. The PR's copy should link these documents with blob URLs, as step 1's did.

Title: `feat(commerce-api): publish the outbox's order confirmed events through a single relay, and resume what it can't`

```markdown
## Summary

Week 7 step 2. A single relay now takes due rows from `order_event_outbox` to the broker, and marks each `PUBLISHED` only after its future succeeded.

- **Spec:** issue 198, with tickets 199–206
- **Decision:** ADR 0035
- **Design:** `docs/design/order-outbox.md` §13. 13.2 is a round, 13.3 the states, 13.7 the SQL, 13.10 the limits, 13.11 the measurements, 13.13 the commands
- **Measurements:** `docs/research/week7-outbox-relay-probe.md`
- **Writing material:** `docs/research/week7-outbox-relay-writing-material.md`

    OrderEventRelayScheduler                     # 1 s fixedDelay, one thread, only where enabled
      OrderEventRelay.publishDueEvents           # NEVER: refuses to run inside a transaction
        repeat until a batch comes back short
          claimDueOutboxes(20)                   # TX: FOR UPDATE SKIP LOCKED, attempt + 1, 60 s lease
          OrderEventPublisher.publish            # no TX: send all, then await all
          for each row: markPublished | markFailed   # own TX, re-read under its lock

### One event's path

Order to row to broker for event ID `order-confirmed:v1:1`, from one run of `OrderEventRelayerTest` (`scripts/scene-lines`). The first line is its own scene, whose order only happens to have the same ID. The other three are one scene, which asserts that the two records' key, partition and value are equal, and the value carries the event ID. The consumer's half, purchase activity, is step 3's.

    after the commit, before a relay: order 1 CONFIRMED; row order-confirmed:v1:1 PENDING attempts 0; records 0

    after the stop:   row order-confirmed:v1:1 PENDING attempts 1 published_at null, record key 1 partition 0 offset 220
    inside the lease: row order-confirmed:v1:1 PENDING attempts 1, new records 0
    past the lease:   row order-confirmed:v1:1 PUBLISHED attempts 2, record key 1 partition 0 offset 221

Those are controlled stops: the JVM stays up, and a restart is a new `OrderEventRelay`. Real signals are in design 13.11.

### The single-relay limit

`order-events.relay.enabled` is a setting, not ownership. A second instance switched on by mistake doesn't publish a row twice while its lease lives (`two relays over one backlog publish each row once`), but it isn't a supported setup. The default profile is `local`, so a deployment that forgets its profile turns the relay on.

### An unresolved error

A row failing 20 times is `BLOCKED`, logs a distinct WARN, and doesn't hold up other rows. There is no endpoint. Design 13.7 lists these rows and resumes one with an `UPDATE`, and `OrderEventRelayFailedSendTest` runs the document's statements as written. `attempt_count` stays, so a resumed row that fails again is `BLOCKED` at once.

`PUBLISHED` means the broker confirmed. It doesn't mean the streamer recorded it: check consumer lag and purchase activity for that.

## Review Points

#### 1. Is a lease worth having with one relay?

The lock ends when the claim commits, so the lease is what protects a row during the send. With one relay it only matters for a second instance started by mistake and for how soon a stopped relay's rows come back. It also makes the index matter for behaviour rather than speed: without it, a second claim gets nothing.

**In practice, would you keep this for a setup you only ever run once, or trust the setting?**

#### 2. Should every failure share one retry limit?

Nothing is classified. A bad row and a stopped broker both use up 20 attempts, about 77 minutes of full outage. A classifier could block a bad row at once, but with a limit it only saves time, and a wrong classification could stop every row.

**In practice, do you separate retriable errors here, or does the limit do enough?**

#### 3. Should a round stop when the app does?

The loop doesn't check for shutdown. With a 60,000 row backlog, SIGTERM drained on for Boot's 30 s phase limit, then the context closed around the round, and 8 rows the broker had confirmed were republished after their lease. Every case measured ended no worse than a republish, so I left it.

**In practice, do you make the loop stop, or accept republishes that the consumer already handles?**

## Evidence

**Before:** on `week7`, rows pile up as `PENDING` and nothing publishes.

**After:** the path above, and `scripts/final-check` on `7ec8227`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-api | 90 (82 before) | 1031 (969 before) | 0 |
| commerce-streamer | 18 | 67 | 0 |

Red checks are in the writing material, ticket by ticket. About 336 rows/s through the real jar, against 480 for bare SQL.

## Merge Danger

**Door:** two-way

Rows already `PUBLISHED` stay so after a revert, and the rest wait.

**Blast Radius:** configuration

- **`kafka.yml` is shared.** `delivery.timeout.ms: 21000` and `max.block.ms: 1000` apply to both apps' producers. Only the relay publishes in main code.
- **Backlog on first start.** Rows left since step 1 are published when the relay first runs.
- **Schema.** There's no migration tool, so production needs the new index before this runs. It decides behaviour, so it isn't optional.
- **Republishes.** Publishing is at least once. A stop can put a batch's rows on the broker twice, at different offsets.
- **A broker restart can fail rows.** On one broker, a `send()` during a restart can fail with `Topic … not present in metadata`: 5 of 18 graceful restarts and 2 of 6 `kill -9` in the runs measured. Those rows went out on their second attempt, with nothing lost or duplicated. Story 31 isn't met, and that's accepted (design 13.10).
- **Out of scope:** the cross-app run, recovery of week 6's cases, and the E2E are step 3's.
```
