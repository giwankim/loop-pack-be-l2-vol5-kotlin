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
