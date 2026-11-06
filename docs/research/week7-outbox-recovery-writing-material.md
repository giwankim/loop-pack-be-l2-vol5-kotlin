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
