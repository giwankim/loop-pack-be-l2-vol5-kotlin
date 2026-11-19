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

Recorded 2026-11-19 from issue 261's closing comment. The text below is the comment's.

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
