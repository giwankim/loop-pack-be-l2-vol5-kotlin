# Week 7 outbox relay: writing material

Material for the technical writing quest, not the write-up itself. Each section after the preface is recorded from one ticket of [spec 198](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/198)'s closing comments. The numbers are in the [probe note](./week7-outbox-relay-probe.md).

## Preface: what this branch is

`feat/outbox-relay` is step 2 of week 7's three: the single relay. It was cut from `week7` at `e6a11ac`, and goes back into `week7` by its own PR. Step 3 (`feat/outbox-recovery`) gets its own interview. The last ticket fills in the rest of this preface.

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
