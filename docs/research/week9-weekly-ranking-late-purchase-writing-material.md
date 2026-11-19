# Week 9 weekly ranking late purchase: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 259](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/259), then the spec's own.

## Issue 260: show a late purchase changes nothing until a later revision is run

Recorded 2026-11-19 from [issue 260's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/260#issuecomment-7007021165). The text below is the comment's.

Implemented as `41f1a280`, on top of `e1496f5f`, which commits the 늦은 구매 entry of `apps/commerce-batch/CONTEXT.md` on its own. `scripts/final-check --app commerce-batch` on `41f1a280`, a clean tree, started 2026-11-19T10:47:19Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 9 classes, 65 tests (31 s), 0 failures, 0 errors, 0 skipped**. Spec 252 ended at 8 classes and 60 tests, so this ticket adds 1 class (`WeeklyRankingLatePurchaseTest`) and 5 tests. No production code changed. Only this document changed after `41f1a280`.

### Acceptance criteria

- [x] **The glossary edit is committed first, on its own.** `e1496f5f`, `docs(context): add late purchase to the weekly ranking glossary`.
- [x] **Revision 1 runs on the assignment's section 6 input and completes; a late purchase leaves its snapshot, scores, ranking and the publication unchanged.** First scene. The late purchase is order 45, product 8 × 5, confirmed at `2026-08-20T09:00:00Z`: inside the period and recorded after revision 1 was frozen. The scene compares literals, not before and after copies: the snapshot (4 inputs, `ASSIGNMENT_CHECKSUM`, frozen at 01:00), scores 7 → 5, 8 → 1, 9 → 5, the ranking 7, 9, 8, and the publication of `2026-08-17-r1` at 01:00.
- [x] **Revision 2 under a new snapshot ID completes with one more input, a different checksum, the late purchase in its scores and ranking, and the publication moved; revision 1's rows are unchanged.** Two scenes.
  - The second scene reads revision 2: 5 inputs, checksum `0c19c2a5…6716`, scores 7 → 5, 8 → 6, 9 → 5, and the ranking 8 (6), 7 (5), 9 (5).
  - The third reads the publication, `2026-08-17-r2` revision 2 at 02:00. It also reads revision 1's snapshot row, inputs, scores and ranking, all unchanged.
- [x] **A rerun of revision 1's three values changes nothing: the publication keeps revision 2's snapshot ID, revision and published-at time.** The fourth scene sets the clock to 03:00 first. The launch is refused with `JobInstanceAlreadyCompleteException`, and the whole publication, 02:00 included, is read back as it was. A fifth scene, added with the user's approval, covers the restart path (see below).
- [x] **Red check, not committed, recorded with the scene that failed.** See Red check.
- [x] **`scripts/final-check --app commerce-batch` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### The scene line

The third scene logs the two revisions after its assertions, from the final check's run (`scripts/scene-lines --results build/final-check/commerce-batch-test-results WeeklyRankingLatePurchaseTest`):

```
Late purchase: 2026-08-17-r1 revision 1: 4 inputs, checksum d07eff39ac33dc7bb7f63110fcf1c2a3f1c52fec496f369dada87d83a5c4720b; 2026-08-17-r2 revision 2: 5 inputs, checksum 0c19c2a5661d9d3ce7f1e679c7311a74f220bf1f916b27b7fee00ad2ff156716; publication holds 2026-08-17-r2 (revision 2)
```

The restart scene's publish step says it left the publication:

```
Publication of period 2026-08-17 not moved to snapshot 2026-08-17-r1 (period 2026-08-17, revision 1): it holds snapshot 2026-08-17-r2 (revision 2)
```

Revision 2's checksum was worked out apart from the code: `printf` of the assignment's four lines and `45\t8\t5\t2026-08-20T09:00:00.000000Z\n`, piped to `shasum -a 256`. The same `printf` without the fifth line gives `ASSIGNMENT_CHECKSUM`, which checks the format.

### Red check

The publication's condition was removed, uncommitted, so it always moves: the three `IF`/`GREATEST` assignments of `WeeklyRankingPublishStore.publish` became `snapshot_id = incoming.snapshot_id, published_at = incoming.published_at, revision = incoming.revision`. Then `WeeklyRankingLatePurchaseTest` and `WeeklyRankingPublishTest` were run, and the change reverted.

- **First run, four scenes: the late purchase class stayed green, 4 of 4.** The spec expected the rerun of revision 1 to go red. It cannot, because two guards stand in front of `publish`:
  - Batch refuses to launch a `COMPLETED` job instance's identifying parameters again (`JobInstanceAlreadyCompleteException`), before any step.
  - A new job instance of the same three values would fail `freeze`'s conflict check, since the snapshot ID is taken.

  `WeeklyRankingPublishTest` went red, 1 of 9: `an earlier revision run after a later one is published completes with its ranking and leaves the publication`. Its revision 1 is a first launch over a publication inserted by SQL, so it reaches `publish`.
- **The path that does reach `publish`.** Revision 1's three values reach `publish` after revision 2 only by a restart. Revision 1's publish fails, revision 2 completes, and revision 1 is restarted. Batch skips the completed `freeze` and `score`, and `publish` ranks revision 1. The user chose to add this as a fifth scene: `restarting revision 1's failed publish after revision 2 is published completes and leaves revision 2's publication`.
- **Second run, five scenes: red, 1 of 5, the restart scene.**

  ```
  Expecting actual:
    [StoredPublication(periodStart=2026-08-17, snapshotId=2026-08-17-r1, revision=1, publishedAt=2026-08-24T03:00)]
  to contain exactly (and in same order):
    [StoredPublication(periodStart=2026-08-17, snapshotId=2026-08-17-r2, revision=2, publishedAt=2026-08-24T02:00)]
  ```

  The publication went back to revision 1 at the restart's 03:00. `WeeklyRankingPublishTest`'s scene failed again, 1 of 9. The other four late purchase scenes stayed green.

### What was built

- **`WeeklyRankingLatePurchaseTest`** on `BaseBatchJobTest`, modelled on `WeeklyRankingPublishTest`: `@TestPropertySource` for the job name, `@Sql` for the test-only `purchase_activity`, `emptyTables("purchase_activity", *RANKING_TABLES)` before each scene.
- **A settable clock.** The nested `SettableClockConfig` puts a `SettableClock` in place of the context's primary `Clock`. Each scene sets it before each run: revision 1 at 01:00, revision 2 at 02:00, the rerun or restart at 03:00. A stopped clock would give both revisions one time, and "the published-at time is unchanged" would then show nothing.
- **The restart scene** fails revision 1's publish through a `@MockkSpyBean` on `WeeklyRankingPublishStore`: `countScores(2026-08-17-r1)` throws. That is the publish step's first store call, so nothing is ranked or published. It then clears the stub. The restart is the same job instance, runs the `publish` step alone, ranks revision 1 as 7, 9, 8, and leaves revision 2's publication.
- **`WeeklyRankingJobs.snapshots()` and `StoredSnapshot`** moved from `WeeklyRankingFreezeTest`, where they were private. CODING_STANDARDS.md 도우미 puts a helper shared by more than one test class in `com.loopers.support`. The freeze test now calls `jdbcClient.snapshots()`; its 25 scenes are otherwise as they were.

### Decisions made while implementing

- **The fifth scene (user's decision).** The spec's rerun scene stays as the ticket states it, since a refused launch is what an operator repeating the old command sees. The restart scene is the one the publication's condition guards. Spec 259's "배치의 있는 장면은 손대지 않는다" holds: no existing scene changed.
- **No production change.** Every scene was green on the code as it stood.
- **A completed run inside a scene's arrange** goes through `runToCompletion`, which stops the scene with `check(…)` unless the run is `COMPLETED`. It is not an assertion of the scene.

### Review

A two-axis review ran before the commit.

- **Standards.** No hard finding. Fixed:
  - The run times were written both as literals and through the instants. They now all come from `REVISION_1_AT` and `REVISION_2_AT` through one `utcWallTime`.
  - The freeze test has its own `utc(String)`, so the new helper got a different name.
- **Spec.** No missing requirement. Fixed: the third scene now also compares revision 1's snapshot inputs before and after revision 2. Kept: the helper move out of the freeze test, recorded above.
- **Left as they are:**
  - The same arrange in scenes 2 and 3.
  - `SettableClock.withZone` returning a fixed clock. Nothing calls it.

## Issue 261: run commerce-batch as a child process from commerce-api tests

Recorded 2026-11-19 from [issue 261's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/261#issuecomment-7007520718). The text below is the comment's.

Implemented as `06a7bdbf`, with review fixes in `3c64946b`, rebased onto issue 260's `9f7f12f2`. Before the rebase they were `b2f4fdaf` and `f44b1c79` on `72a24070` (step 3's merge into `week9`). Issue 260 changed only commerce-batch's tests, its glossary and this document, so the batch's bootJar and everything commerce-api's tests read are the same after the rebase. `scripts/final-check --app commerce-api` on `f44b1c79`, a clean tree, started 2026-11-19T10:54:07Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 117 classes, 1207 tests (5 min 45 s), 0 failures, 0 errors, 0 skipped**. The same check on `b2f4fdaf` (started 10:46:50Z) gave the same counts in 6 min 6 s. This ticket adds 2 classes (`CommerceBatchProcessTest`, `CommerceBatchProcessRunTest`) and 4 tests. No production code changed.

### Acceptance criteria

- [x] **A test starts the batch on an empty period (tables from the batch's real schema file) and gets exit code 0, with a snapshot row and a publication row in the context's database.** This is `CommerceBatchProcessRunTest`'s first scene, which took 9.3 s in the final check. The rows read `2026-08-17-r1 2026-08-17 revision 1 FROZEN inputs 0` and `2026-08-17 2026-08-17-r1 revision 1`. The batch's log has `Froze period 2026-08-17 revision 1 as 2026-08-17-r1: 0 inputs`, `Ranked 0 products` and `Published snapshot 2026-08-17-r1`.
- [x] **The same start with a non-Monday `periodStart` gives a non-zero exit code, and the failure text carries the tail of the batch's log.** This is the second scene, which took 4.5 s. The failure matches `The batch exited with [1-9][0-9]*\.`, and its last 40 lines carry the validator's `periodStart must be a Monday, but 2026-08-18 is a TUESDAY`. No snapshot row is left. The refusal reaches Boot's runner as `InvalidJobParametersException` (`Application run failed`), so the JVM exits before `SpringApplication.exit`.
- [x] **A missing or unreadable jar property fails the test; it never skips it.** `CommerceBatchProcessTest` covers both cases with no container: no property, and a property that names no file. Each throws an `AssertionError` naming `commerce-batch.jar`, and the second also names the path. A file that exists but is unreadable fails the same `isRegularFile && isReadable` check. The schema property goes through the same check.
- [x] **`WeeklyRankingApiTest` and `WeeklyRankingFinderTest` pass when run after a class that left the batch's full tables behind.** Shown once with a temporary, uncommitted ordering: `junit.jupiter.testclass.order.default=…ClassOrderer$OrderAnnotation` and `@Order(1)` on `CommerceBatchProcessRunTest`, with the three classes filtered. See Red check.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section. Issue 260's branch also creates this file, so the two sections are joined when the branches meet.

### What was built

- **`CommerceBatchProcess`** (commerce-api test support) is modelled on `CommerceStreamerProcess`, but it runs to its exit.
  - It starts `java -jar <batch bootJar>` with `--spring.profiles.active=test`, the context's three `datasource.mysql-jpa.main.*` values, `--job.name=weeklyRanking` and `periodStart=…,java.time.LocalDate snapshotId=… revision=…,java.lang.Long`. That is the spelling design section 8 gives an operator.
  - It waits up to 120 s. Past that, it forces the process to end and fails.
  - A non-zero exit fails too. Both failures carry the last 40 lines of the log, which is kept as `build/commerce-batch/<snapshotId>-<millis>.log`.
  - A shutdown hook ends a process the test JVM leaves behind.
  - `run` returns only after an exit with 0.
- **No Redis address is passed.** The batch's `test` profile points Redis at `localhost:6379`. Lettuce connects on first use, and the batch's main code never uses Redis, so the start never connects. Both runs above started without a Redis container's address.
- **Gradle.** `:apps:commerce-api:test` gets a `commerceBatchBootJar` configuration on the batch's `bootArchives`, declared like the streamer's.
  - It declares the jar and `apps/commerce-batch/src/main/resources/sql/weekly-ranking-schema.sql` as test inputs.
  - It passes them to the test JVM as `-Dcommerce-batch.jar` and `-Dcommerce-batch.weekly-ranking-schema`.
  - There is no module dependency in either direction. A changed batch jar or schema file reruns commerce-api's tests.
- **`WeeklyRankingBatchTables.reset`** gives a scene that runs the batch the batch's own tables.
  - It drops the five ranking tables and Batch's nine metadata tables, then runs the batch's schema file through `ResourceDatabasePopulator`.
  - Issue 262's flow test is meant to reuse it.
- **`purchase_activity` is never written by hand.** The freeze reads it, so the empty-period scene first starts the real streamer once. The streamer's `ddl-auto=create` makes the table empty, and the streamer is terminated before the batch runs. The class names its own topic and group.
- **The copy.** `sql/weekly-ranking-tables.sql` is now `DROP TABLE IF EXISTS` (publication, item, snapshot), then `CREATE TABLE`. Before, it was `CREATE TABLE IF NOT EXISTS` and `DELETE`. Its header says why.

### Red check

The ordered run of `CommerceBatchProcessRunTest` → `WeeklyRankingApiTest` → `WeeklyRankingFinderTest` described under the acceptance criteria was run twice:

| Copy script | `CommerceBatchProcessRunTest` | `WeeklyRankingApiTest` | `WeeklyRankingFinderTest` |
|---|---|---|---|
| old (`IF NOT EXISTS` + `DELETE`) | 2/2 pass | **9 of 15 fail**: `Field 'revision' doesn't have a default value` on `insert into weekly_ranking_snapshot (snapshot_id, period_start, frozen_at)` | 1/1 pass |
| new (`DROP` + `CREATE`) | 2/2 pass | 15/15 pass | 1/1 pass |

The report timestamps confirm the order in the green run: the batch class at 10:45:15Z, then `WeeklyRankingApiTest` at 10:45:51Z. `WeeklyRankingFinderTest` passes either way, because its one scene is refused by the Monday check before any SQL runs. The ordering was reverted before the commit.

### Decisions made while implementing

- **Batch's metadata is dropped, not only the ranking tables.** The spec's "표" section names only the ranking tables. A `COMPLETED` job instance left by an earlier class with the same three parameters would refuse the run, though, and the flow test uses `2026-08-17-r1` too. The batch's `test` profile (`initialize-schema: always`) creates the metadata tables again at its start.
- **`run` fails on a non-zero exit instead of handing the code back.** That follows the spec: "종료 코드가 0이 아니면 로그의 끝과 함께 실패한다". The ticket's "returns the exit code" is kept as `Finished.exitCode`, which is always 0 when `run` returns.
- **The copy drops only its own three tables.** The batch's `weekly_ranking_snapshot_input` and `weekly_ranking_score` may stay behind after a batch class. The copy's classes never read them, and the batch schema has no foreign keys, so the drops cannot be refused.
- **The two child-process classes share shapes but no code:** the jar check, `javaCommand`, the log tail and the datasource arguments. With two copies, extracting a helper was deferred.

### Review

`code-review` since `72a24070` (before the rebase), with Standards and Spec in parallel:

- **Standards.**
  - No hard violations.
  - Fixed in `f44b1c79` (now `3c64946b`):
    - The empty-period scene's `exitCode` assertion could never fail. It was dropped, and the non-Monday scene now asserts a non-zero code by pattern instead of the absence of 0.
    - `Finished.logLines()` had no caller. It was dropped.
    - `CODING_STANDARDS.md` gained dated memos for the copy's drop, the reset, and `CommerceBatchProcessRunTest`'s constructor dependencies.
  - Not changed:
    - The duplication with `CommerceStreamerProcess` and the `jdbcUrl`/`username`/`password` clump, deferred as above.
    - The streamer's jar check, which still lacks the readability check.
- **Spec.**
  - Nothing missing.
  - The metadata drop goes past "랭킹의 표를 지우고". It is recorded above for design section 14 (issue 263).

## Issue 262: link an order confirmation to the weekly ranking response in one test

Recorded 2026-11-19 from [issue 262's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/262#issuecomment-7008047581). The text below is the comment's.

Implemented as `35a75e44` on issue 261's `2443b191`. `scripts/final-check --app commerce-api` on `35a75e44`, a clean tree, started 2026-11-19T11:30:19Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 118 classes, 1208 tests (6 min 5 s), 0 failures, 0 errors, 0 skipped**. This ticket adds 1 class (`WeeklyRankingFlowTest`) and 1 test, which took 17.0 s in that run. No production code changed, and neither did the copy or the batch's test-only `purchase_activity` file: the test exposed no fault in any of them.

### Acceptance criteria

- [x] **One of the three first orders' events is published twice: the streamer logs two `Recorded` lines for it and `purchase_activity` holds one row per order and product.** The second order (A×3) is the one. Its lines end `result=RECORDED` and `result=ALREADY_RECORDED`, the other two orders have one line each, and the table holds four rows.
- [x] **Before any run the API answers `NOT_PUBLISHED`.** With a null `snapshotId` and no items.
- [x] **Revision 1 exits 0; the API answers A 5 rank 1, C 5 rank 2, B 1 rank 3 with `snapshotId` `2026-08-17-r1`.**
- [x] **After the fourth order is relayed and recorded, the API answer is unchanged.** The whole response body equals revision 1's, `inputFrozenAt` included.
- [x] **Revision 2 exits 0; the API answers B 6 rank 1, A 5 rank 2, C 5 rank 3 with `snapshotId` `2026-08-17-r2` and an `inputFrozenAt` later than revision 1's.**
- [x] **`weekly_ranking_snapshot` holds input counts 4 and 5 and two different checksums; both are logged and copied here with `scripts/scene-lines`.** See Recorded run.
- [x] **The streamer is terminated before the tables are cleaned, and no child process outlives the test.** One `@AfterEach` terminates the streamer and then truncates in `finally`. `terminate` checks the process is gone, and `CommerceBatchProcess.run` returns only after the batch exited. After the final check `pgrep -fl "commerce-(batch|streamer)"` found nothing.
- [x] **Red check, not committed.** See Red check.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### Recorded run

`scripts/scene-lines --results build/final-check/commerce-api-test-results WeeklyRankingFlowTest`, from the final check on `35a75e44`. Products A, B and C are 1, 2 and 3.

```text
Flow 1, three orders relayed and one republished: products A 1, B 2, C 3; deliveries 4 [Recorded the order confirmed event. eventId=order-confirmed:v1:2, orderId=2, partition=2, offset=0, result=RECORDED; Recorded the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, partition=0, offset=0, result=RECORDED; Recorded the order confirmed event. eventId=order-confirmed:v1:2, orderId=2, partition=2, offset=1, result=ALREADY_RECORDED; Recorded the order confirmed event. eventId=order-confirmed:v1:3, orderId=3, partition=2, offset=2, result=RECORDED]; purchase_activity [order 1 product 1 quantity 2 confirmed_at 2026-08-18T01:02:03.123456Z; order 1 product 2 quantity 1 confirmed_at 2026-08-18T01:02:03.123456Z; order 2 product 1 quantity 3 confirmed_at 2026-08-19T01:02:03.123456Z; order 3 product 3 quantity 5 confirmed_at 2026-08-20T01:02:03.123456Z]; order 4 confirmed at 2026-08-21T01:02:03.123456Z, its outbox row PENDING
Flow 2-3, before any run NOT_PUBLISHED; revision 1 exited with 0: snapshotId 2026-08-17-r1, inputFrozenAt 2026-11-19T11:32:50.939346Z, items [rank 1 product 1 score 5; rank 2 product 3 score 5; rank 3 product 2 score 1]
Flow 4-5, the late purchase recorded after revision 1 froze: delivery [Recorded the order confirmed event. eventId=order-confirmed:v1:4, orderId=4, partition=1, offset=0, result=RECORDED]; purchase_activity 5 rows; the response is unchanged: snapshotId 2026-08-17-r1, items [rank 1 product 1 score 5; rank 2 product 3 score 5; rank 3 product 2 score 1]
Flow 6-7, revision 2 exited with 0: snapshotId 2026-08-17-r2, inputFrozenAt 2026-11-19T11:32:55.027157Z, items [rank 1 product 2 score 6; rank 2 product 1 score 5; rank 3 product 3 score 5]; snapshots [2026-08-17-r1 revision 1 inputs 4 checksum e14b0382a9ce98607490b80ff51ff6a998e9eca5dd9b2d1fd85feb022f5f1108; 2026-08-17-r2 revision 2 inputs 5 checksum d5c448e17824b5ee5ab33787723e1e0b9f84a42d45e3d79b1d7332364e1a35b4]
```

- The two checksums were the same in three runs (two filtered runs and the final check). The order and product IDs start at 1 after the truncation and the confirmation times are pinned, so the snapshot's inputs are the same rows each time.
- The streamer logged order 2's first delivery before order 1's, though order 1 was relayed first. The two records are on different partitions (0 and 2), and the streamer's consumers don't order deliveries across partitions. The test asserts each order's results, not one order across all lines.

### What was built

- **`WeeklyRankingFlowTest`** (`com.loopers.adapter.webapi.v1.ranking`) is one `@Test` on `BaseWebApiAdapterTest`, outside the test transaction, cleaned with `DatabaseCleanUp`.
  - Orders, confirmations and ranking reads are MockMvc requests. The test PG accepts each create.
  - The relay rounds are new `OrderEventRelay` instances on the context's beans, and the batch runs are `CommerceBatchProcess.run`. Nothing else is driven by hand.
  - `WeeklyRankingBatchTables.reset` gives it the batch's own tables before anything else. The copy is never applied.
  - `purchase_activity` is only read. The streamer's start creates it and the streamer alone writes it.
- **Exactly one event is republished.** The stop stub fails the first `markPublished` of a round, and a round sends its whole claimed batch before it marks any row. So the stopped round has to claim one row. The scene confirms and relays the first order, confirms the second and runs the stopped round, then confirms the third and runs a round a day later by the pinned clock. That round is past the 60 s lease and sends the second order's event again with the third's.
- **The late purchase is late by order of events alone.** The fourth order (B×5) is confirmed at a pinned 2026-08-21T01:02:03.123456Z, inside the period. Its outbox row is asserted `PENDING` and is relayed only after revision 1's run returned.
- **`inputFrozenAt` is compared, not asserted as a literal.** The batch child runs on the real clock: revision 2's was 4.1 s after revision 1's in the recorded run.

### Red check

Run once with an uncommitted change and reverted: after `WeeklyRankingBatchTables.reset`, the `@BeforeEach` also ran the copy `sql/weekly-ranking-tables.sql`, so the snapshot, item and publication tables had only the contract's columns. Batch's metadata was still dropped, so a completed instance from an earlier run could not be what refused the run.

- The scene failed at revision 1's run: `The batch exited with 5.`
- The log's tail carried the cause: `java.sql.SQLSyntaxErrorException: Unknown column 'revision' in 'field list'`, on the freeze's `SELECT snapshot_id, period_start, revision FROM weekly_ranking_snapshot WHERE snapshot_id = ? OR (period_start = ? AND revision = ?)`.
- The streamer was terminated by the `@AfterEach`, and no child process was left.

So the copy lacking a column the batch reads fails this test. The batch's `CREATE TABLE IF NOT EXISTS` doesn't repair a table that already exists.

### Decisions made while implementing

- **Which event is republished: the second order's (A×3).** The spec names A at 8 as what a double count would show. A double count would also fail the four-row `purchase_activity` assertion and the snapshot's input count of 4.
- **The first three confirmations are interleaved with relay rounds** instead of confirming all three and then relaying. The spec lists "confirm three, relay"; one round over three rows would republish all three.
- **`TopicReader` is not used.** The broker's records are not something this scene asserts: the streamer's `Recorded` lines are the deliveries.
- **The run's exit code is not asserted in the test.** `run` throws on any other code, so an assertion on 0 could never fail (issue 261's review dropped the same one).
- **The streamer start, the new relay and the `purchase_activity` SQL repeat what the two E2E classes have.** Those are on `BaseCommittingApplicationServiceTest`; this class is on `BaseWebApiAdapterTest`, as the spec decided. Moving them was left out, with a dated note in `CODING_STANDARDS.md`.

### Review

`code-review` of the uncommitted change on `2443b191`, with Standards and Spec in parallel. All fixes are in `35a75e44`.

- **Standards.** Fixed before the commit:
  - The first body is now `body`, and the later ones `revision1Body` and `revision2Body`.
  - `status` and `snapshotId` are asserted with `extractingPath`. The items are still read as lines from the `JsonNode`: nine path lines per revision was the alternative.
  - The order ID comes from the `asNumber()` assertion.
  - The helpers are `requestCreate` and `requestConfirmAt`, both returning `MvcTestResult`, with `createdOrderId` and `assertConfirmed` beside them. Before, they were `createOrder` and `confirmOrderAt`.
  - The products take the fixture's default name and stock (100 or more).
  - The `relayDue` flag is gone: the scene pins the clock before each round.
  - The confirm helper takes the PG `amount`, not a unit count.
  - The event ID is built in one `eventIdOf`.
  - The KDoc says four INFO lines, not one per step.
- **Spec.** Nothing missing or wrong. Fixed: the fourth order's outbox row is asserted `PENDING`, not only logged. A dated note's count of what makes the class's context was corrected.

## Issue 263: record the late purchase step (design section 14, final check, PR notes)

Recorded 2026-11-19 from [issue 263's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/263#issuecomment-7008430768). The text below is the comment's, with its two blob links written here as a relative link and a pointer.

Recorded as `0be42e63`, documents only. `scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer` ran once on `09f0f121`, a clean tree, starting 2026-11-19T11:42:15Z with `BOOTSTRAP_SERVERS` unset. ktlint passed (1 s). **commerce-batch: 9 classes, 65 tests (28 s). commerce-api: 118 classes, 1208 tests (5 min 59 s). commerce-streamer: 18 classes, 68 tests (51 s). 0 failures, 0 errors, 0 skipped in all three.** The code of `09f0f121` is issue 262's `35a75e44`, and only documents changed after it. Section 14 was being written in the working tree while the check ran; no file Gradle reads changed.

### Acceptance criteria

- [x] **Design section 14 is written and section 1.1's fourth row says the step is done, with a link to the spec.** [`docs/design/weekly-ranking.md`](../design/weekly-ranking.md) section 14 has eleven subsections. The 1.1 row changed only its status cell, which `scripts/check-doc-notes` reports as the one changed base line.
- [x] **Every difference between the spec and what was built is in a "명세와 다른 것" table, each with its ticket.** Section 14.11, eleven rows.
- [x] **`scripts/final-check` passes for the three apps on one commit; the commit, timings and test counts are in section 14.** Section 14.10, the run above. One invocation with three `--app` flags, so ktlint ran once and the three apps ran on the same commit.
- [x] **The draft PR body is in the writing material.** Below, under Draft PR body.
- [x] **The closing comment is appended to the writing material.** This section.

### What was written

- **Design section 14**: what the step built, the batch as a child process, the two ways commerce-api's tests make the ranking tables, what step 3 deferred and how it was closed, the contract and glossary, what was not chosen, limits, scenes, red checks, the final check, and differences from the spec.
- **The note left on this issue by issue 261** is in 14.3: `WeeklyRankingBatchTables.reset` also drops Batch's nine metadata tables, with the three reasons. The batch child getting no Redis address is in 14.2, and as a limit in 14.7.
- **How section 13.6's two deferred limits were closed** is in 14.4.
  - The table definitions are still in two places. What changed is that one test runs the batch and the API on tables made from the batch's own script. That a changed contract column would fail it is a judgement from the test's shape; the red check that was run is the reverse one (with the copy run over the batch's tables, the batch's first statement fails).
  - The two revisions are real runs in two places: two job runs in `WeeklyRankingLatePurchaseTest`, two batch processes in `WeeklyRankingFlowTest`.
- **The "later revision frozen and scored but not yet published" moment** is pointed at two existing scenes: step 3's failed publish (design 13.7) on the batch side, and `WeeklyRankingApiTest`'s ranked-but-unpublished revision on the API side.
- **The limit that nobody is told a late purchase arrived** is the first item of 14.7.
- **Dated notes** on the header, 13.6, 13.12 and on ADR 0038's consequence that named this step's linking test. No new ADR. `CONTEXT-MAP.md` is unchanged: the flow test changed no contract.

### Differences from spec 259

Section 14.11 has eleven rows. The ones that change what a reader would assume:

- **The rerun of revision 1 is refused, not a quiet no-op.** Batch throws `JobInstanceAlreadyCompleteException` before any step, so the spec's red check could not go red on that scene. The restart of revision 1's failed publish, a fifth scene, is the path the publication's condition guards.
- **The table reset drops Batch's metadata too**, beyond the spec's "랭킹의 표를 지우고".
- **The non-Monday parameter is a committed scene**, where the spec listed it as an uncommitted red check.
- **Confirmations are interleaved with relay rounds** so that exactly one event is republished.
- **The exit code 0 is not asserted in a test**: `run` throws on any other code.

### What was checked in the recorded run

`scripts/scene-lines --results build/final-check/commerce-api-test-results WeeklyRankingFlowTest` on the final check's results gave the same two snapshot checksums as issue 262's three runs (`e14b0382…1108` and `d5c448e1…35b4`), with `inputFrozenAt` `2026-11-19T11:45:10.216297Z` and `2026-11-19T11:45:14.523448Z`. Section 14.8 records them. `WeeklyRankingFlowTest`'s one scene took 15.4 s in this run.

### Review

A two-axis review ran on the uncommitted documents, with one reviewer on the standards and one on the ticket and spec 259.

- **Standards.** No hard violation: `scripts/check-doc-notes` reports only the 1.1 status cell, every section reference resolves, and no Avoid word of either glossary is used for its concept. Fixed:
  - Section 14 named `WeeklyRankingFinderTest` as a user of the copy. Only `WeeklyRankingApiTest` carries the `@Sql`; spec 259 has the same slip, and 14.3 now says so.
  - The third late purchase scene compares revision 1's snapshot row, scores and ranking with literals; only the snapshot inputs are read before and after.
  - "The scene sets the clock before each run" now says each run that completes: the restart scene's failing first run sets none.
  - Three sentences were stronger than their evidence: that a leftover `COMPLETED` instance ends the child with a non-zero code (never run that way), that the batch's start does not connect to Redis (inferred from runs that started without the address), and the streamer's delivery order (it differed between issue 262's run and this one).
- **Spec.** No acceptance criterion missing. Fixed in 14.11:
  - Added: issue 261's two `CODING_STANDARDS.md` memos, and that the flow test's red check ran the copy after the reset, not in place of it.
  - Dropped: a row that read the spec's three `--app` values as three separate runs.
  - Corrected: Flyway's rejection is ADR 0038's, not ADR 0039's, and the reset is shared test support, not only the flow test's.
- **Left as they are:**
  - "a clean tree". The final check's header printed `commit 09f0f121 (clean)`; the documents were edited after it started, as in step 3.
  - 운영자 for the person who runs the job. The root glossary lists it under 관리자's Avoid words, but section 4 and spec 259 already use it for this role.
  - "두 정의는 그대로 둘" appears in the 13.6 note, 14.4 and 14.7.
  - `CommerceBatchProcess`'s KDoc and issue 261's section cite design section 8 for the command's spelling; it is section 4. The KDoc is code, outside this documents-only ticket.

### Left for the spec's closing

The spec's own review, closing comment and PR into `week9` (`docs/agents/spec-closing.md`). The draft below gets its final numbers and the comment's URL then.

## Spec 259: the closing review

Recorded 2026-11-19 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/259#issuecomment-7009639238) of [spec 259](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/259). The text is the comment's, with its blob URLs.

Step 4 of week 9 is built, and its closing review is done. `scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer` on `7333ffb6`, a clean tree, started 2026-11-19T12:38:32Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 9 classes, 65 tests (32 s); commerce-api 118 classes, 1208 tests (6 min 7 s); commerce-streamer 18 classes, 68 tests (47 s); 0 failures, 0 errors, 0 skipped in all three**. Step 3 ended commerce-batch at 8 classes and 60 tests and commerce-api at 115 and 1203, so this step adds 1 class and 5 tests to the first and 3 classes and 5 tests to the second. The review's fixes add and remove no test, so the counts match the run on `09f0f121` that issue 263 recorded. Only documents change after `7333ffb6`.

Tickets 260–263 are closed, each with its own comment. [Design 14.1](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md#141-4단계가-만든-것) lists what the step built and [design 14](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md#14-늦은-구매와-연결-4단계) holds the step. No production code changed, and there is no ADR.

### Where the implementation differs

[Design 14.11](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md#1411-명세와-다른-것) has fourteen rows: issue 263's eleven and this review's three. The ones that change what a reader would assume:

| The spec said | What is there | How it was found |
| --- | --- | --- |
| A rerun of revision 1's parameters after revision 2 changes nothing (story 4) | Batch refuses it with `JobInstanceAlreadyCompleteException` before any step. Nothing changes, but the operator sees a refused launch, not a quiet pass | Issue 260 |
| Three batch scenes: revision 1, revision 2, the rerun | Five. Revision 2 is split into snapshot and publication, and a restart of revision 1's failed publish is added. That restart is the only path the publication's condition guards | Issue 260 |
| A non-Monday `periodStart` is an uncommitted red check | A committed scene, `CommerceBatchProcessRunTest` | Issue 261 |
| (Ticket 261) `run` returns the exit code | `run` returns nothing. It throws on any non-zero exit, so the returned code was always 0 and nothing read it | This review |
| The batch's existing scenes are not touched | The scenes are unchanged. Step 2's `MovableClock` moved to test support and replaces this step's second clock, and the restart test reads the snapshot through the shared helper | Issue 260, this review |

**Now proven:** a late purchase leaves revision 1's snapshot, scores, ranking and publication as they were; a later revision includes it and replaces the publication; revision 1's rows are kept; a republished order confirmed event counts once in the ranking response; and the three apps work on one database, from the confirm request to the ranking response.

**Proven by a red check in this review, not by a committed test:**

- **The deadline.** With the wait cut to 1 s, both runner scenes failed with "The batch did not exit within 1 s." and the log's tail, and no batch process was left behind ([design 14.2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md#142-자식-프로세스의-배치)). A job that hangs after its start was not produced.
- **Drift in a contract column.** With `product_rank` renamed in the batch's script and its publish SQL together, revision 1's run still exited with 0 and the flow test's next read was 500, `Unknown column 'item.product_rank'` ([design 14.4](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md#144-3단계가-미룬-것)). One column was tried.

**Not proven here:** drift outside the contract (indexes, column lengths), throughput, and two runs of one period at once. Nobody is told a late purchase arrived; a later revision is an operator's decision ([design 14.7](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md#147-한계)).

### The closing review

A two-axis review of `week9...feat/weekly-ranking-late-purchase` at `0c52d1b0` (14 commits, 15 files), with one reviewer on the standards and one on this spec. Standards found 3 breaches of a documented rule, 2 judgement calls and 2 possible smells. Spec found 2 partial requirements, 3 points where the implementation or its record was off, and no addition that design 14.11 had not recorded. Each was checked against the code and decided.

#### What changed

All in `7333ffb6`.

- **Design section 14 says 중계.** It used the glossary's avoided word on five lines.
- **`CommerceBatchProcess.run` returns nothing,** and `Finished` is gone.
- **The KDoc cites design section 4** for the command's spelling, not 8. Issue 261's comment and issue 263's note keep the old number, as posted.
- **The deadline's comment cites measured times:** starts of 3.2 s to 4.0 s and completed runs of 0.15 s to 0.26 s in that day's 13 kept logs (design 14.2).
- **The flow test's later reads are `bodyAfterRevision1` and `bodyAfterRevision2`.** `<request>Body` is for a write's response. Issue 262's review had chosen the old names.
- **One test clock in commerce-batch,** `MovableClock` in `com.loopers.support`, and the restart test's private snapshot row is gone.
- **The two red checks above,** with dated notes in design 14.2 and 14.4.

A dated note in design 14.10 records the final check above.

#### Left as they were

- **The flow test asserts each revision's items as lines read from `JsonNode`.** The same lines are the scene's logged output, and `WeeklyRankingApiTest` asserts the item fields by path. `CODING_STANDARDS.md` now carries a dated note for it.
- **"The start never connects" to Redis, in issue 261's comment.** Design 14.2 already narrows it to an inference: the runs started without a Redis address and exited with 0, and the connection was not measured.
- **Duplication between `CommerceBatchProcess` and `CommerceStreamerProcess`, and the flow test's helpers copied from the E2E classes.** Design 14.6 and `CODING_STANDARDS.md` already record both.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/research/week9-weekly-ranking-late-purchase-writing-material.md#spec-259-the-closing-review).

## Draft PR body

A draft for the PR from `feat/weekly-ranking-late-purchase` into `week9`, in the shape of step 3's, written before the spec's closing review and given its final numbers, red checks and the comment's link at that review (2026-11-19). It carries the assignment's section 8 notes: one job instance from failure to restart, checksums and expected scores, and the publication's revision with the late purchase's result. It makes no throughput claim; none was measured.

Title: `test: show a late purchase reaches the weekly ranking only through a later revision, from the confirm request to the ranking response`

````markdown
## Summary

Week 9 step 4, the last. No production code changes. A purchase recorded after a snapshot was frozen (늦은 구매) changes nothing until an operator runs the same period under a new snapshot ID and a higher revision; this branch shows that in two places. commerce-batch gets five scenes over two real revisions. commerce-api gets one test that goes from the confirm request to the ranking response through the real relay, broker, streamer and batch, with commerce-batch run as a child process.

- **Spec:** issue 259, with tickets 260–263, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/259#issuecomment-7009639238)
- **Design:** [`docs/design/weekly-ranking.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/design/weekly-ranking.md) section 14: 14.2 the child process, 14.3 the tables, 14.4 what step 3 deferred, 14.7 the limits, 14.8–14.9 the scenes and red checks, 14.11 what differs from the spec
- **Writing material:** [`docs/research/week9-weekly-ranking-late-purchase-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/docs/research/week9-weekly-ranking-late-purchase-writing-material.md), which holds every ticket's closing comment
- **Glossary:** 늦은 구매 (late purchase) in [`apps/commerce-batch/CONTEXT.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-late-purchase/apps/commerce-batch/CONTEXT.md): late relative to a snapshot, not to the period's end
- **No ADR.** `CONTEXT-MAP.md` is unchanged.

```text
WeeklyRankingFlowTest (commerce-api, one @Test, everything over HTTP except relay rounds and batch runs)
  tables from commerce-batch's own weekly-ranking-schema.sql     # never the contract-only copy
  confirm orders 1–3 (A×2 B×1, A×3, C×5), relay                  # order 2's event is republished once
    streamer child process records purchase_activity             # 4 rows; order 2: RECORDED, ALREADY_RECORDED
  confirm order 4 (B×5) inside the period, leave its outbox row PENDING
  GET /api/v1/rankings/weekly                                    # NOT_PUBLISHED
  batch child process: revision 1 (2026-08-17-r1)                # exit 0
  GET                                                            # A 5, C 5, B 1
  relay order 4, streamer records it                             # the late purchase: 5 rows
  GET                                                            # unchanged, the whole body
  batch child process: revision 2 (2026-08-17-r2)                # exit 0
  GET                                                            # B 6, A 5, C 5; a later inputFrozenAt
```

## Review Points

#### 1. Is refusing a repeated old command enough, or should it be a quiet no-op?

Running revision 1's three values again after revision 2 is published is refused by Batch (`JobInstanceAlreadyCompleteException`) before any step. Nothing changes, but the operator sees a failed launch. The only path on which revision 1 reaches `publish` after revision 2 is a restart of its failed publish, and there the publication's condition keeps revision 2 (design 14.8, 14.11).

**Would you make the repeat exit 0?**

#### 2. Is it acceptable that nobody is told a late purchase arrived?

A late purchase sits in `purchase_activity` until an operator decides to run a later revision. Nothing detects it, and no read that compares the table with the published snapshot's input count is written down (design 14.7).

**Would you add that read as an operator's query now, or wait for a real need?**

#### 3. Is one cross-app test enough to keep two table definitions?

The ranking tables are still defined twice: the batch's schema script, and a contract-only copy for commerce-api's API tests. The flow test runs the batch and the API on the batch's script, and the copy now drops and recreates its tables, so test order cannot decide a result. A red check that renamed one contract column in the batch's script and its SQL failed the flow test's read with 500 (design 14.4). Drift outside the contract is not caught.

**Would you move the definitions into one shared module instead?**

## Evidence

**Before:** step 3's two revisions were rows seeded by SQL, commerce-api's tests made the ranking tables from a copy, and no run went from an order confirmation to the ranking response.

**After:** on real MySQL and a real broker.

- **One job instance, from failure to restart** (`WeeklyRankingLatePurchaseTest`): revision 1's publish fails (`FAILED`), revision 2 completes and is published, and revision 1 is started again with the same three values. The new execution belongs to the same `JobInstance`, runs the `publish` step alone, ranks revision 1 as 7, 9, 8, and ends `COMPLETED`. The publication still holds `2026-08-17-r2`, revision 2, published at 02:00, though the restart's clock was at 03:00.
- **Checksums and expected scores, commerce-batch.** Revision 2's checksum was computed apart from the code with `printf … | shasum -a 256`.

  | Snapshot | Inputs | Checksum | Scores | Ranking |
  | --- | --- | --- | --- | --- |
  | `2026-08-17-r1` | 4 | `d07eff39…720b` | 7 → 5, 8 → 1, 9 → 5 | 7, 9, 8 |
  | `2026-08-17-r2` | 5 | `0c19c2a5…6716` | 7 → 5, 8 → 6, 9 → 5 | 8, 7, 9 |

- **The publication's revision and the late purchase's result.** After the late purchase and before revision 2, revision 1's snapshot, scores, ranking and publication equal their literals. After revision 2 the publication holds revision 2, and revision 1's rows are unchanged.
- **The same over HTTP** (`WeeklyRankingFlowTest`): `NOT_PUBLISHED`, then A 5, C 5, B 1 under `2026-08-17-r1`; the same body after the late purchase is recorded; then B 6, A 5, C 5 under `2026-08-17-r2` with a later `inputFrozenAt`. The snapshots hold 4 and 5 inputs with checksums `e14b0382…1108` and `d5c448e1…35b4`.
- **A republished event counts once:** order 2's event is delivered twice, `purchase_activity` holds one row per order and product, and A scores 5, not 8.

| Red check (local, never committed) | Result |
| --- | --- |
| The publication's three assignments unconditional | The restart scene: the publication goes back to revision 1 at 03:00. The plain rerun scene stays green, since Batch refuses it first |
| The copy back to `CREATE TABLE IF NOT EXISTS` + `DELETE`, run after a batch class | 9 of `WeeklyRankingApiTest`'s 15: `Field 'revision' doesn't have a default value` |
| The flow test also runs the copy after the batch's script | Revision 1's run exits with 5: `Unknown column 'revision' in 'field list'` |
| The batch runner's deadline cut from 120 s to 1 s | Both scenes of `CommerceBatchProcessRunTest`: "The batch did not exit within 1 s." with the log's tail, and no batch process left behind |
| `product_rank` renamed in the batch's script and its publish SQL together | Revision 1's run exits with 0, and the flow test's next read is 500: `Unknown column 'item.product_rank' in 'field list'` |

`scripts/final-check --app commerce-batch --app commerce-api --app commerce-streamer` on `7333ffb6`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-batch | 9 (8 before) | 65 (60 before) | 0 |
| commerce-api | 118 (115 before) | 1208 (1203 before) | 0 |
| commerce-streamer | 18 (untouched) | 68 | 0 |

Not shown here: throughput (not measured), two runs of one period at once, drift outside the contract's columns, and a job that hangs after its start.

## Merge Danger

**Door:** two-way

Tests, test support, a Gradle test input and documents. No production code, no entity, no migration.

**Blast Radius:** commerce-api's test task

- **`:apps:commerce-api:test` now needs commerce-batch's bootJar**, as it already needs the streamer's. A changed batch jar or schema script reruns commerce-api's tests.
- **A class that runs the batch drops Batch's metadata tables** in the test database, and the contract-only copy drops its three tables before each class that uses it.
- **Out of scope:** detecting or announcing a late purchase, an automatic later revision, a shared schema module, the upstream `[volume-9]` PR.
````
