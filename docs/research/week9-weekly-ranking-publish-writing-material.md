# Week 9 weekly ranking publish: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 252](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/252), then the spec's own.

## Issue 253: rank a snapshot's scores and publish them as the period's weekly ranking

Recorded 2026-11-19 from [issue 253's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/253#issuecomment-6996994688). The text below is the comment's.

Implemented as `02e76662`, on top of `4ee5649b`, which commits the 순위 and 게시 entries of `apps/commerce-batch/CONTEXT.md` and the publish rules added to 게시하다. `scripts/final-check --app commerce-batch` on `02e76662`, a clean tree, started 2026-11-19T01:31:45Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 8 classes, 50 tests (28 s), 0 failures, 0 errors, 0 skipped**. Spec 246 ended at 7 classes and 45 tests, so this ticket adds 1 class (`WeeklyRankingPublishTest`) and 5 tests. Only this document changed after `02e76662`.

### Acceptance criteria

- [x] **With the assignment's section 6 inputs the run is `COMPLETED` and the weekly ranking is product 7 rank 1 score 5, product 9 rank 2 score 5, product 8 rank 3 score 1, and nothing else.** `WeeklyRankingPublishTest`, first scene. It reads the whole `weekly_ranking_item` table, so "nothing else" covers the table.
- [x] **The period's publication holds that snapshot ID, its revision and the test clock's time; the snapshot is still `FROZEN`.** Two scenes. The publication scene runs revision 3 under a clock stopped at `2026-08-24T01:02:03.123456789Z`, and finds `published_at = 2026-08-24T01:02:03.123456`, cut to microseconds and stored as UTC. A separate scene reads the snapshot's state, `FROZEN`. `WeeklyRankingScoreTest`'s own FROZEN scene now runs through `publish` too.
- [x] **A period with no purchase activity is `COMPLETED`, has no ranking rows and has a publication.** The log line reads `Ranked 0 products of snapshot 2026-08-17-r1 …`. The count check compares 0 with 0.
- [x] **Another period's publication is untouched.** A seeded `2026-08-10` publication is read back as it was, beside the new `2026-08-17` one.
- [x] **Red check, not committed: ranking with `RANK()` turns the assignment's scene red.** See Red checks. The literal swap alone stays green.
- [x] **The existing scenes of `WeeklyRankingFreezeTest`, `WeeklyRankingScoreTest` and `WeeklyRankingJobRestartTest` pass with the job running to `publish`; `BatchRestartProbeTest` and its job are untouched.** These are 25, 6 and 6 scenes, all passing.
  - The failing-freeze scene still asserts `steps == [freeze]`.
  - The restart scenes that fail in `score` never reach `publish`.
- [x] **Test cleanup empties the two new tables too.** `WeeklyRankingJobs.RANKING_TABLES` lists all five ranking tables. The four ranking test classes empty it with `purchase_activity`.
- [x] **`scripts/final-check --app commerce-batch` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### What was built

- **Job.** `weeklyRanking` runs `freeze`, `score`, then `publish`. `publish` is `StepBuilder.tasklet(publishTasklet, transactionManager)` on the application's transaction manager. It has no isolation override, so it runs at MySQL's REPEATABLE READ.
- **Tasklet.** `PublishTasklet` is `@StepScope` and takes the job's three parameters, the shape of `FreezeTasklet`. In one transaction it does three things:
  1. It compares `COUNT(*)` of the snapshot's scores with `COUNT(DISTINCT product_id)` of its inputs. A difference fails the step through `check(…)` before anything is written.
  2. It ranks the scores.
  3. It writes the publication, with `published_at` from the injected `Clock`, cut to microseconds and bound through `JdbcUtcTimes.toJdbc`.
- **Ranking.** `WeeklyRankingPublishStore.rankScores` makes the ranking in one statement, so the scores never enter memory:

  ```sql
  INSERT INTO weekly_ranking_item (snapshot_id, product_id, score, product_rank)
  SELECT snapshot_id, product_id, score, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC)
  FROM weekly_ranking_score WHERE snapshot_id = ?
  ```

- **Publication.** `WeeklyRankingPublishStore.publish` writes it in one statement with no lock taken first:

  ```sql
  INSERT INTO weekly_ranking_publication (period_start, snapshot_id, revision, published_at)
  VALUES (?, ?, ?, ?) AS incoming
  ON DUPLICATE KEY UPDATE
      snapshot_id  = IF(incoming.revision > weekly_ranking_publication.revision, incoming.snapshot_id,  weekly_ranking_publication.snapshot_id),
      published_at = IF(incoming.revision > weekly_ranking_publication.revision, incoming.published_at, weekly_ranking_publication.published_at),
      revision     = GREATEST(incoming.revision, weekly_ranking_publication.revision)
  ```

  MySQL assigns the columns left to right, and each later expression reads the values already assigned. So `revision` goes last: if it moved first, the two `IF`s would compare the incoming revision with itself and never move the row. The same revision and an earlier one both leave all three columns as they are.
- **Schema.** Both tables go in the same script with `CREATE TABLE IF NOT EXISTS`.
  - `weekly_ranking_item (snapshot_id, product_id, score, product_rank)` has primary key `(snapshot_id, product_rank)` and unique key `UK_WEEKLY_RANKING_ITEM_ON_SNAPSHOT_ID_AND_PRODUCT_ID`.
  - `weekly_ranking_publication (period_start, snapshot_id, revision, published_at)` has primary key `period_start`.
- **State.** The snapshot's state stays `FROZEN`, and no `PUBLISHED` is written.

### Red checks

These are mutations made without committing, run, then reverted:

- **`ROW_NUMBER()` → `RANK()`, keeping `ORDER BY score DESC, product_id ASC`: green, 4 of 4.**
  - Within one snapshot, `product_id` is unique, so the tie-break leaves no two rows equal. `RANK()` then gives the same numbers as `ROW_NUMBER()`.
  - The spec's prediction (product 9 ranked first, or a unique-key failure) does not hold for this mutation.
- **`RANK() OVER (ORDER BY score DESC)`, ranking by score alone: red, 3 of 4.**
  - Products 7 and 9 both get rank 1, and the primary key refuses the second: `Duplicate entry '2026-08-17-r1-1' for key 'weekly_ranking_item.PRIMARY'`.
  - The step fails, so the assignment's scene ends `FAILED`, not `COMPLETED`. The publication scene and the other-period scene find no `2026-08-17` publication: the ranking and the publication share one transaction, and both rolled back.
  - The empty-period scene stays green, since it has nothing to tie.
  - The test that catches a tie, then, is the primary key `(snapshot_id, product_rank)`, not the scene's expected order. Whatever the window function, the key refuses a shared rank.

The spec's other red checks belong to the next tickets. The unconditional publication goes to 254. `ResourcelessTransactionManager` and removing "already ranked" go to 255.

### Decisions made while implementing

- **The count check fails through `check(…)`, an `IllegalStateException`, not a named exception.** It runs once, at one place, and nothing catches it. The step's exit description carries its message: "`<snapshot>` has N scores for M products, so its ranking is incomplete". Issue 254 owns the scene that shows it.
- **The publication write returns nothing yet.** Issue 254 needs the step to say when the publication did not move. By default Connector/J reports *found* rows for `ON DUPLICATE KEY UPDATE`, not *changed* rows (`useAffectedRows=false`). So an unchanged row counts 1, the same as a fresh insert. The update count therefore cannot tell "moved" from "not moved" without a URL setting. Reading the row back inside the same transaction can.
- **The publication test stops its own clock** (`FixedClockConfig`). It does not share `WeeklyRankingFreezeTest`'s, because a nested `@TestConfiguration` is part of each class's context.
- **The "already ranked" skip (step 1 of the spec's `publish` order) is not in this ticket.** Issues 254 and 255 list it, and no scene here reruns a published snapshot.

### Review

A two-axis review ran before the commit.

- **Standards.** No hard finding. Fixed:
  - The new import's position in the three existing test classes.
  - "the assignment's week" in a test name became "period", since 주차 is on the glossary's Avoid list.
  - The FROZEN assertion moved to its own scene (단언: one behaviour per test).
- **Spec.** No missing requirement in scope and no scope creep. The upsert's left-to-right semantics were checked and hold.
- **Left as they are:**
  - "item" in `weekly_ranking_item`, where the spec's table decided the name.
  - `RANKING_TABLES` as an `Array`, so `emptyTables(…, *RANKING_TABLES)` spreads it directly.
  - The three job parameters repeated in both tasklets, as `FreezeTasklet` does.
  - The duplicated fixed-clock config.

## Issue 254: let only a later revision replace a period's publication

Recorded 2026-11-19 from [issue 254's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/254#issuecomment-6997445432). The text below is the comment's.

Implemented as `e60c9950`. `scripts/final-check --app commerce-batch` on `e60c9950`, a clean tree, started 2026-11-19T01:55:48Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 8 classes, 54 tests (32 s), 0 failures, 0 errors, 0 skipped**. Issue 253 ended at 8 classes and 50 tests, so this ticket adds 4 tests to `WeeklyRankingPublishTest`. Only this document changed after `e60c9950`.

### Acceptance criteria

- [x] **Revision 1 published, then revision 2 run: the publication holds revision 2's snapshot; revision 1's ranking rows are unchanged.** Revision 1's ranking (two items, deliberately unlike what the scores would give) and its publication are inserted by SQL. The revision 2 run is `COMPLETED`, the publication is `2026-08-17-r2`, revision 2, at the test clock's time, the revision 1 items read back as inserted, and the step's exit description is empty.
- [x] **Revision 2 published, then revision 1 run: `COMPLETED`; revision 1's ranking rows exist; the publication's snapshot ID, revision and published-at time are revision 2's; the `publish` step's exit description says the publication was not moved.** Revision 2's publication is inserted by SQL at `2026-08-24T00:30:00`. Revision 1 ranks 7, 9, 8 and leaves that row exactly. The exit description reads `Publication of period 2026-08-17 not moved to snapshot 2026-08-17-r1 (period 2026-08-17, revision 1): it holds snapshot 2026-08-17-r2 (revision 2)`, and the same line is logged at WARN.
- [x] **One score deleted before `publish` (by a spy on the publish store, before its first call): the step fails, and the snapshot has no ranking row and no publication.** `@MockkSpyBean` on `WeeklyRankingPublishStore`; `countScores`, the step's first store call, first deletes product 9's score in the step's transaction. The run is `FAILED` with `… has 2 scores for 3 products` in the exit description; `weekly_ranking_item` and `weekly_ranking_publication` are empty.
- [x] **Red check, not committed, recorded in the writing material: with the condition removed from the publication write, the earlier-after-later scene is red.** See Red check.
- [x] **`scripts/final-check --app commerce-batch` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

The ticket's fourth rule, "a snapshot whose ranking rows already exist, equal in count to its scores, is not ranked again", has its own scene: the spy inserts three items for the snapshot in an order the scores would not give (8, 9, 7) before the first call. The run is `COMPLETED`, the items read back as inserted, and the publication holds the snapshot. Before the change it failed on `weekly_ranking_item.PRIMARY`. Issue 255's published-under-a-failed-execution rerun is the scene this path exists for.

### What was built

- **The spec's full `publish` order.** `PublishTasklet` counts the snapshot's scores, then its ranking items. When there are items and they equal the scores, the count check and the ranking are skipped and only the publication is written. Otherwise the check and the ranking run as in 253 (now a private `rank`). An empty snapshot (0 items) takes the ranking path, where 0 meets 0.
- **"Not moved" by read-back.** `WeeklyRankingPublishStore.publish` keeps 253's one conditional statement and then reads the period's row:

  ```sql
  SELECT snapshot_id, revision, published_at FROM weekly_ranking_publication WHERE period_start = ? FOR SHARE
  ```

  It returns a `Publication`. The tasklet asks `publication.isWrittenBy(key, publishedAt)`: the row holds this snapshot at this publish's time. If not, the step still completes, logs a warning and sets `contribution.exitStatus = ExitStatus.COMPLETED.addExitDescription(…)`; `ExitStatus.and` keeps the description through the step's end and its listener.
- **Why `FOR SHARE`.** The step runs at REPEATABLE READ, and its first `COUNT(*)` fixes the read view. A plain `SELECT` would see a row this transaction changed as changed, but a row it left as it was as of that read view. If a publish of the same period committed in between, the plain read could name a stale holder, or find no row and fail the step. The locking read sees the latest row. It waits on nothing, since the upsert already holds the row's exclusive lock, so it adds no lock for same-period runs.

### Red check

A mutation made without committing, run, then reverted:

- **The publication write's three `IF`/`GREATEST` assignments replaced by `incoming.*`: red, 1 of 6** (run when the class held 253's five scenes and the earlier-after-later one). The earlier-after-later scene finds `StoredPublication(periodStart=2026-08-17, snapshotId=2026-08-17-r1, revision=1, publishedAt=2026-08-24T01:02:03.123456)` where it expected revision 2's row. The other scenes stay green: none of them republishes a period that holds a later revision.

### Decisions made while implementing

- **A read-back, not `useAffectedRows`.** Connector/J's found-rows count gives 1 for an insert and for a row left as it was. `useAffectedRows=true` would tell them apart but changes the shared datasource's update counts for every statement; one read on a row already locked is local to this step.
- **"Not moved" is decided by the published-at time, not a read before the write.** A read before the write is the "read first" the spec avoids, and goes stale under the same race. The cost is one limit: a stopped clock that republishes the same snapshot at the same instant reads as moved. A real clock never gives a later publish the same microsecond, and issue 255's rerun scene moves its clock between runs.
- **The spy hooks `countScores`, the step's first store call.** The skip's item count comes after it, so one hook serves both the deletion and the insertion scenes.
- **A test helper returns the publish step's exit description, not its `StepExecution`.** `@SpringBatchTest`'s step-scope listener would take a method returning `StepExecution` for a step context factory.

### Review

A two-axis review ran before the commit.

- **Standards.** No hard finding. Fixed: the new import's alphabetical position, a one-line `jdbcClient.sql(…)` chain broken like the file's others, and `Publication.isOf` renamed `isWrittenBy`.
- **Spec.** No missing requirement in scope and no scope creep. Two points on the read-back: a plain read can see a stale row under concurrent same-period runs (fixed with `FOR SHARE`), and a stopped clock makes a same-snapshot rerun read as moved (kept, recorded in `Publication`'s KDoc and above).
- **Left as they are:** `publish` both writing and reading (the read is what the write's outcome needs), and `(key, publishedAt)` travelling together into `publish` and `isWrittenBy`.

## Issue 255: keep the previous publication when publish fails, and converge on rerun

Recorded 2026-11-19 from [issue 255's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/255#issuecomment-6997509569). The text below is the comment's.

Implemented as `bf39cd42`, tests only, on top of issue 254's `b7318ffa`. `scripts/final-check --app commerce-batch` on `ca97b2cb`, a clean tree, started 2026-11-19T02:06:19Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 8 classes, 60 tests (34 s), 0 failures, 0 errors, 0 skipped**. Only this document differs between `bf39cd42` and `ca97b2cb`. Issue 254 ended at 8 classes and 54 tests, so this ticket adds 6 tests to `WeeklyRankingJobRestartTest`, which now has 12.

### Acceptance criteria

- [x] **Publish fails after the ranking rows are inserted: `FAILED`, no ranking for the new snapshot, the publication still holds the previous one.** Scene `a run failing at the publication write ends FAILED with no new ranking and the previous publication kept`. The spy makes `publish` throw. The scene verifies `rankScores` ran once, then reads an empty `weekly_ranking_item` and only the seeded revision 1 publication.
- [x] **The rerun is the same `JobInstance` in a new `JobExecution`, `COMPLETED`, with one `freeze`, one `score` and two `publish` step executions, and publishes the new snapshot.** Scene `the restart of a run failed at the publication write publishes in the same job instance, without freezing or scoring`. The step counts come from `JobRepository.getStepExecutionCount`. The publication holds revision 2 at the moved clock's time.
- [x] **Published under a failed execution: `FAILED` with the new snapshot already published; with the clock moved, the rerun is `COMPLETED` and the ranking rows and published-at time are unchanged.** Two scenes, one per run. The rerun scene compares the ranking rows (3) and the publication with what the failed run left, and the published-at time stays the first run's `2026-08-24T01:00`.
- [x] **The clean-run comparison, as two test methods, each equal to the checksum of `7\t5\t1\n9\t5\t2\n8\t1\t3\n`.** One method fails before product 9 at chunk size 2 and reruns. The other runs clean. Both pass. See Checksums.
- [x] **Red checks, not committed, each recorded with the scene that failed.** See Red checks.
- [x] **Both checksum values are recorded.** See Checksums.
- [x] **`scripts/final-check --app commerce-batch` passes, with no skipped tests.** The run above.
- [x] **The closing comment is appended to the writing material.** This section.

### What was built

- **No production change.** The issue expected one only for the "already ranked" check of the spec's `publish` order, "if the publication rules ticket has not added it". Issue 254 added it (`e60c9950`). This ticket first wrote the same check itself, then was rebased onto 254 and kept 254's code.
- **The scenes.** Each one seeds revision 1's publication of the period (`PREVIOUS_PUBLICATION`) and runs revision 2 (`2026-08-17-r2`), so a restart shows the publication move rather than appear. The existing score-restart scenes moved to revision 2 with them. Batch's metadata and the ranking tables are kept between a first run and its restart.
- **Failures** come from `@MockkSpyBean` on `WeeklyRankingPublishStore`. No fault switch is in production code.
- **Result checksum.** `WeeklyRankingJobs.resultChecksum` reads one snapshot's ranking rows in rank order. It joins product ID, score and rank by TAB, ends each line with LF and hashes the text with SHA-256 in lowercase hex, the input checksum's form. Production code neither computes nor stores it.

### Checksums

- Expected: `printf '7\t5\t1\n9\t5\t2\n8\t1\t3\n' | shasum -a 256` gives `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2` (`WeeklyRankingInputs.ASSIGNMENT_RESULT_CHECKSUM`), worked out apart from the code.
- The restarted run (failed before product 9 at chunk size 2, then rerun): `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`.
- The clean run: `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`.

The two runs give the same ranking, and it is the assignment's.

### Red checks

These are mutations made without committing, run, then reverted. Each was run twice, on this ticket's own check and again on 254's code after the rebase, with the same result:

- **`publish` step on `ResourcelessTransactionManager()` in place of the application's: red, 1 of 12.**
  - The failure is in `a run failing at the publication write ends FAILED with no new ranking and the previous publication kept`: "Expecting empty but was" the three `2026-08-17-r2` ranking rows (7/5/1, 9/5/2, 8/1/3). Without the transaction, each statement commits on its own, and the ranking outlives the failed publication.
  - The rerun scene stays green. The "already ranked" check finds the leftover rows and skips to the publication write. So the step's transaction is what the failure scene tests, not what the rerun needs.
- **"Already ranked" removed (its condition replaced by `false`): red, 1 of 12.**
  - The failure is in `the restart of a run whose publish committed completes and leaves the ranking and the published-at time as they were`: expected `COMPLETED`, but was `FAILED`, on `Duplicate entry '2026-08-17-r2-1' for key 'weekly_ranking_item.PRIMARY'`.
  - This was also this ticket's first red. The scene was written before the check and failed the same way.

### Decisions made while implementing

- **In the published-under-a-failed-execution scene, the spy commits both writes in `REQUIRES_NEW` transactions, `rankScores` and `publish`, then throws after `publish`.** The issue says "runs the real publish in a `REQUIRES_NEW` transaction". The publish the assignment's section 5 means is the step's whole transaction, check, ranking and publication. If only the publication committed, the ranking would roll back with the step. The rerun would then rank again with no duplicate key, the "unchanged ranking rows" criterion would have nothing to compare, and the "already ranked" red check could never be red. Production makes one commit and the scene makes two, which changes no outcome.
- **That rerun's moved clock also keeps 254's "not moved" reading honest.** The rerun publishes the same snapshot at a new time, so 254's read-back finds the first run's published-at time and reports the publication as not moved. A stopped clock would read it as moved (254's recorded limit).
- **`insertPublication` moved into `WeeklyRankingJobs`**, now that two test classes seed publications (`CODING_STANDARDS.md` 테스트 › 도우미). The rebase also replaced the private copy that 254 had added to `WeeklyRankingPublishTest`.

### Review

A two-axis review ran before the commit, on this ticket's own version of the check.

- **Standards.** One hard finding, fixed: the duplicated `insertPublication` helper. Also fixed: one long KDoc line was rewrapped. The other fix, naming the skip condition, went with the production code the rebase dropped.
- **Spec.** No missing requirement and no scope creep. Moving the scenes to revision 2 was judged necessary for the seeded previous publication. The two `REQUIRES_NEW` wraps were judged faithful, and the helper's KDoc now says why the ranking is wrapped too.
- **Left as they are:**
  - "Result checksum" is not in the glossary. The spec and the design doc's 결과 체크섬 name it, and it lives only in test support.
  - The `2026-08-17` literal repeats across the test classes, as their neighbours do.
  - `WeeklyRankingJobRestartTest` now holds both the score and the publish restarts. The spec places both there.

## Issue 256: read a period's published weekly ranking through GET /api/v1/rankings/weekly

Recorded 2026-11-19 from [issue 256's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/256#issuecomment-6997273889). The text below is the comment's.

Implemented as `48913f1c`, on top of `f89251f9` (spec 246's merge into `week9`). This ticket touches commerce-api only and seeds its own rows, so it does not wait for 253. `scripts/final-check --app commerce-api` ran on `48913f1c`, a clean tree, starting 2026-11-19T01:46:01Z with `BOOTSTRAP_SERVERS` unset. ktlint passed, and **commerce-api ran 115 classes and 1203 tests (6 min 8 s): 0 failures, 0 errors, 0 skipped**. The ticket adds 2 classes and 16 tests. Only this document changed after `48913f1c`.

### Acceptance criteria

- [x] **The assignment's ranking published: product 7 rank 1, product 9 rank 2, product 8 rank 3, with the header fields right.** This is the first scene of `WeeklyRankingApiTest`. It checks `periodStart` 2026-08-17, `periodEnd` 2026-08-24, `PUBLISHED`, the snapshot ID, `inputFrozenAt` 2026-08-24T00:05:00Z, and each item's rank, score and product fields. The products are registered in the test, so their names carry the assignment's numbers.
- [x] **`limit=2` returns ranks 1 and 2; no `limit` means 10.** There are two scenes. The default scene ranks eleven products, so any default above 10 would show the eleventh.
- [x] **Product 7 deleted: ranks 2 and 3 come back as 2 and 3, and `limit=2` returns two items.** There are two scenes.
- [x] **A deleted brand's products are all left out.** A product of another brand is ranked fourth, so the scene still has one item to show.
- [x] **No publication: 200, `NOT_PUBLISHED`, null `snapshotId` and `inputFrozenAt`, no items.** Both fields are present in the JSON as `null`. They are not left out.
- [x] **An empty published snapshot: `PUBLISHED`, its `snapshotId`, no items.**
- [x] **Another period's publication is never returned.** The scene publishes 2026-08-17 and reads 2026-08-24.
- [x] **The items of a ranked but unpublished snapshot of the same period (a later revision) are never returned.** Revision 2 ranks product 8 first and is not published, and the answer is still revision 1's three items.
- [x] **400 for a missing, malformed or non-Monday `periodStart`, and for `limit` 0 and 101.** There are five scenes. Each checks `BAD_REQUEST` and that the message names the cause.
- [x] **A test calling the query service without the controller is refused for a non-Monday.** This is `WeeklyRankingFinderTest`.
- [x] **Red checks, not committed, recorded here.** They are below.
- [x] **`scripts/final-check --app commerce-api` passes, with no skipped tests.** See the run above.
- [x] **The closing comment is appended to this document.** This document is new, since 253 has not created it yet.

### What was built

- **Slice.** `ranking` has no domain package. `application/ranking` holds `WeeklyRankingFinder` (provided), `WeeklyRankingRequest`, `WeeklyRankingInfo` with its item and status, and `WeeklyRankingQueryService` (`@ValidatedApplicationService(readOnly = true)`). `application/ranking/required` holds `WeeklyRankingRepository` and its two row types. `adapter/persistence/ranking` holds `JdbcWeeklyRankingRepository` on `JdbcClient`, with no entity. `adapter/webapi/v1/ranking` holds `WeeklyRankingApi`, its spec and the top-level Responses.
- **Read.** Three reads run in one read-only transaction:
  1. The header: `weekly_ranking_publication` joined to `weekly_ranking_snapshot` on the snapshot ID and the period.
  2. The items: read by the header's snapshot ID and joined to `product` on `deleted_at IS NULL`, with `ORDER BY product_rank LIMIT ?` after the filter.
  3. The display fields: read through the new `ProductFinder.findAllSummaries`, `ProductRepository.findAllByIdIn` (brand in the entity graph) and `ProductInfoAssembler.toSummaryInfos`. The item shows the same `ProductResponse` as the customer product list.
- **Request.** `periodStart` is `@NotNull`, ISO date and Monday-checked (`@AssertTrue`). `limit` defaults to 10 and must be between 1 and 100.
- **Response.** The fields are `periodStart`, `periodEnd` (exclusive), `status`, `snapshotId`, `inputFrozenAt` and `items[{rank, score, product}]`. The revision and the publication time are not sent.
- **Docs.** `CONTEXT-MAP.md` gains the third contract: the three tables and the columns commerce reads. `CONTEXT.md` points to the weekly ranking glossary and defines no term.
- **Tests.** `src/test/resources/sql/weekly-ranking-tables.sql` creates the three tables with the contract's columns. A comment names the batch's `weekly-ranking-schema.sql` as the source. The script runs `BEFORE_TEST_CLASS` and empties the tables. The rows are written by `JdbcTemplate` in the test transaction and rolled back.

### Red checks

These are mutations made without committing, run, then reverted. Each was run alone on the final code.

- **`AND product.deleted_at IS NULL` removed from the items statement.** Three scenes fail: deleted product, deleted product with `limit=2`, and deleted brand. Each is a 500, not a wrong list: the deleted product's item comes back from the items statement, and `findAllSummaries` has no display fields for it, so `getValue` throws. The service makes a mismatch loud rather than dropping the item silently.
- **Items read by the period of the header's snapshot instead of by its ID.** Only the unpublished-revision scene fails: it expected 3 items and got 6.
- **An extra check of mine: `@Valid` removed from `WeeklyRankingFinder.find`.** `WeeklyRankingFinderTest` fails, so the Monday refusal for a caller without the controller rests on the method validation.

### Decisions made while implementing

- **The Monday check is a Bean Validation constraint, not a separate service check.** The spec says "Bean Validation은 `WeeklyRankingRequest`에 두고, 월요일의 검사는 조회 서비스에도 둔다". `CoreException` carries no custom message, and every refusal must name its cause. So the check is an `@AssertTrue` on the Request, which both the controller's `@Valid` and the service's method validation run. A second check written in the service would be unreachable behind the proxy. The service-only test and the third red check pin it.
- **`periodStart` is nullable with `@NotNull`.** With a non-null constructor parameter, a missing value fails the binder with Kotlin's own null check. That is a global `ObjectError` that names no field, and the message came out blank. The service reads it through `checkNotNull` after validation.
- **A query value the binder cannot convert now names the field.** `ApiControllerAdvice.handleMethodArgumentNotValid` used Spring's conversion text, which does not name the field. A binding failure now reads `요청 값 'periodStart'의 값 '2026-08-32'이(가) 잘못되었습니다.` This covers every `@ModelAttribute` endpoint, not only rankings. No existing test pinned the old text.
- **`snapshotId` and `inputFrozenAt` are `@JsonInclude(ALWAYS)`.** The application's default inclusion is `NON_NULL`, which would leave both fields out. The spec's contract has them as `null` when nothing is published.
- **The header joins on the snapshot ID and the period.** The spec's "`period_start`로 잇는다" alone would match every revision of the period. The snapshot ID decides which snapshot. The period condition makes a publication that points at another period's snapshot read as unpublished, not as another week's ranking. This also puts `weekly_ranking_snapshot.period_start` in the contract, so the test-only SQL holds only contract columns.
- **The test's `@Sql` is recorded in `CODING_STANDARDS.md`.** The list of what a test subclass may add is closed. `CREATE TABLE` commits in MySQL, so the tables cannot be made inside the test transaction, and only one class reads them. A dated note (issue 256) and the base class's KDoc now name it.
- **Writing `frozen_at`.** A first helper bound the row through Hibernate's native query with a `LocalDateTime`. Hibernate applied `hibernate.jdbc.time_zone`, so the value was read back nine hours early. The test now writes through `JdbcTemplate` with a UTC `LocalDateTime`, which is the batch's `JdbcUtcTimes` shape. The adapter reads `frozen_at` as a `LocalDateTime` at UTC.

### Review

A two-axis review ran before the commit.

- **Standards.** These were fixed:
  - The class-level `@Sql` (dated note added).
  - Field injection in the finder test, now constructor injection.
  - A single-class helper in `support`, moved into the class and onto `JdbcTemplate`.
  - Empty arrays checked by `length()`, now `asArray().isEmpty()`.
  - A `prepare` prefix on a non-base helper, now `insertAssignmentRanking`.
  - `findAllByIdIn` placed between `findForUpdateByBrandIdOrderById` and its KDoc.
  - Duplicated like counting in the assembler.
  - The advice KDoc's wording.
- **Spec.** No requirement was missing. These were fixed:
  - `findAllSummaries`'s KDoc said "its own transaction", and the read joins the query service's.
  - The test SQL held `period_start` outside the contract, resolved by the header's period condition.
- **Left as they are:**
  - `status` alongside a nullable `snapshotId`. Only the two factories build the Info.
  - The name `WeeklyRankingRequest`, after `ProductListRequest`.
  - `@DateTimeFormat` (spring-context) on an application Request.
  - The Monday check as a constraint, as decided above.
  - The advice change reaching every `@ModelAttribute` endpoint.

### For issue 257

The design doc's 13th section should record these:

- The two-statement read as a difference from the assignment.
- The header's period condition.
- The `deleted_at IS NULL` restated in SQL.
- The uncached read.
- The two copies of the table definitions.

## Issue 257: record the publish step in ADR 0039 and design section 13

Recorded 2026-11-19 from [issue 257's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/257#issuecomment-6997864585). The text below is the comment's.

Recorded as `89099c45`, documents only. `scripts/final-check --app commerce-batch --app commerce-api` ran on `f282e642`, a clean tree, starting 2026-11-19T02:22:13Z with `BOOTSTRAP_SERVERS` unset. ktlint passed (18 s). **commerce-batch: 8 classes, 60 tests (30 s). commerce-api: 115 classes, 1203 tests (5 min 40 s). 0 failures, 0 errors, 0 skipped in both.** The code of `f282e642` is `bf39cd42` (batch) and `48913f1c` (API), and only documents changed after it. The ADR and section 13 were being written in the working tree while the check ran; no file Gradle reads changed.

### Acceptance criteria

- [x] **ADR 0039 exists and the design doc links it.** `docs/adr/0039-what-a-ranking-period-shows-is-decided-by-a-publication-row-that-only-a-later-revision-replaces.md`, linked from the design doc's header and from section 13. It holds three decisions: the publication row in place of a snapshot state, the conditional write in place of a lock or a failed step, and commerce-api reading the three tables by SQL under the contract.
- [x] **Design section 13 lists the spec's limits.** Section 13.6 has all five: serial same-period runs as an operating rule, two copies of the table definitions, an uncached and unmeasured read, a job instance completed before this branch, and the result checksum not stored. It adds four the implementation brought: "not moved" told by the published-at time, no way to take a publication back, the two revisions being seeded rows, and the tables applied by hand outside `local` and `test`.
- [x] **The deviation from the assignment's "one joined query" is recorded with its reason.** Section 13.11, under 과제와 다른 것 and not 명세와 다른 것, as the spec asked.
- [x] **`CONTEXT-MAP.md` names the three tables and the columns commerce reads, and they match the API's statements.** Section 13.4 sets the contract's nine columns beside the two statements of `JdbcWeeklyRankingRepository`. They are the same, and the test-only SQL file holds the same nine. Both glossaries match the code. Nothing needed changing.
- [x] **Both final checks pass; the commit, timings and test counts are in the design doc.** Section 13.9, the run above.
- [x] **No Avoid word of either glossary appears in the new prose.** Checked by a script over the added lines and by the standards review. 게시 포인터 remains only in old lines of 1.1, 12.5 and 12.10, which gained dated notes.
- [x] **The closing comment is appended to the writing material.** This section.

### What was written

- **ADR 0039.** Beside the three decisions it records seven things not chosen (a `PUBLISHED` state, a locking read first, failing an earlier revision, a lock on same-period runs, `useAffectedRows`, a JPA entity in commerce-api, a shared schema module) and its costs. It names three conditions for a second look: taking a publication back or reading its history, same-period runs that can overlap, and a second reader of the tables.
- **Design section 13**, twelve subsections: the job and the `publish` step, the tables, the read, the contract and glossaries, what was not chosen, limits, scenes, red checks, the final check, differences from the spec, differences from the assignment, and what goes to step 4.
- **Dated notes** in sections 1 to 12. The header, the 1.1 status cell and a new 1.4 (what step 3 built) are updated, and every item of 12.5 and 12.10 that this step closes says where.
- **ADR 0038** gains a dated note on its third revisit condition: commerce-api took no entity.

### Differences from spec 252

Section 13.10 has sixteen rows. The ones that change what a reader would assume:

- **The `RANK()` red check.** Swapping the function while keeping `ORDER BY score DESC, product_id ASC` stays green. Ranking by score alone is red, and what refuses it is the primary key `(snapshot_id, product_rank)`, not the unique key.
- **"Not moved" is told by a read-back.** The write is still one statement with no lock first. The `FOR SHARE` read after it takes a shared lock on a row the write already holds.
- **The published-under-a-failed-execution scene commits two writes**, the ranking and the publication, each in `REQUIRES_NEW`.
- **The Monday check is one `@AssertTrue`**, run by the controller's `@Valid` and by the service's method validation. The service has no second check.
- **The header joins on the snapshot ID and the period**, which put `weekly_ranking_snapshot.period_start` in the contract.
- **`ApiControllerAdvice` names the field on a binding failure**, for every `@ModelAttribute` endpoint.

### Review

A two-axis review ran on the uncommitted documents.

- **Standards.** One hard finding, fixed: the 1.1 row for step 3 had its description reworded. Design docs are insert-only, so the old description is back and only the status cell changed. Also fixed:
  - Section 13.3 left out that the third read counts likes, and so that a cache would also have to follow like counts.
  - Two ADR sentences were stronger than their evidence: what the step 4 linking test will catch, and that a clock never repeats a microsecond.
  - Section 13.1 now says its two pieces of reasoning (the assignment order of `revision`, the `FOR SHARE` read) have no red check or concurrent scene behind them.
  - Section 13.3 now names REPEATABLE READ as what the third read relies on.
- **Spec.** No acceptance criterion missing. Three differences were added to 13.10: the glossary entries are a clause longer than the spec's text, `WeeklyRankingPublishTest` has nine scenes where the spec listed six, and the assembler and the product port each gained a method. The quoted 게시 entry now matches the glossary.
- **Left as they are:** section 1.4, the notes in sections 2, 3, 4, 8 and 11, and the limits beyond the spec's five. Steps 1 and 2 recorded the same kinds of thing.

### Left for the spec's closing

The spec's own review, closing comment and PR into `week9` (`docs/agents/spec-closing.md`).

## Spec 252: the closing review

Recorded 2026-11-19 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/252#issuecomment-6999565834) of [spec 252](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/252). The text is the comment's, with its blob URLs.

Step 3 of week 9 is built, and its closing review is done. `scripts/final-check --app commerce-batch --app commerce-api` on `2edfec32`, a clean tree, started 2026-11-19T03:11:56Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 8 classes, 60 tests (28 s); commerce-api 115 classes, 1203 tests (5 min 7 s); 0 failures, 0 errors, 0 skipped in both**. Step 2 ended commerce-batch at 7 classes and 45 tests, so this step adds 1 class and 15 tests there, and 2 classes and 16 tests to commerce-api. The review's one fix is a rename, so the counts match the run on `f282e642` that issue 257 recorded. This branch does not touch commerce-streamer, so it was not run. Only documents change after `2edfec32`.

Tickets 253–257 are closed, each with its own comment. [Design 1.4](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/design/weekly-ranking.md#14-3단계가-만든-것) lists what each one built, [design 13](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/design/weekly-ranking.md#13-순위와-게시-3단계) holds the step, and [ADR 0039](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/adr/0039-what-a-ranking-period-shows-is-decided-by-a-publication-row-that-only-a-later-revision-replaces.md) records the publication row.

### Where the implementation differs

[Design 13.10](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/design/weekly-ranking.md#1310-명세와-다른-것) has seventeen rows: issue 257's sixteen and the rename below. The ones that change what a reader would assume:

| The spec said | What is there | How it was found |
| --- | --- | --- |
| Ranking with `RANK()` turns the assignment's ranking scene red (red check) | Swapping the function while keeping `ORDER BY score DESC, product_id ASC` stays green. Ranking by score alone is red, refused by the primary key `(snapshot_id, product_rank)` | Issue 253 |
| "Not moved" goes to the log and the exit description (how to tell was left open) | The row is read back `FOR SHARE` right after the write, and told by snapshot ID and published-at time. A stopped clock would misreport a republish as moved | Issue 254 |
| The published-under-a-failed-execution spy runs "진짜 게시" in `REQUIRES_NEW` | It commits both `rankScores` and `publish`. With the publication alone, the ranking rolls back, the rerun ranks again, and the "already ranked" red check cannot go red | Issue 255 |
| "월요일의 검사는 조회 서비스에도 둔다" | One `@AssertTrue`, run by the controller's `@Valid` and by the service's method validation. `CoreException` carries no message, and a second check behind the proxy would be unreachable | Issue 256 |
| A non-date `periodStart` is 400 with a message that says why | `ApiControllerAdvice` names the field and the value on any binding failure, for every `@ModelAttribute` endpoint. No test pinned the old Spring text | Issue 256 |
| Bean Validation on `WeeklyRankingRequest` | `WeeklyRankingFindRequest`, after `CODING_STANDARDS.md`'s `<Aggregate><Verb>Request` | This review |

**Now proven:** the assignment's "앞선 게시가 그대로다" (a failed publish leaves the previous publication and no ranking rows) and its seventh step (a restarted run and a clean run give the same result checksum as the canonical one, `745d50bb…`).

**Not proven here:** two runs of one period at once (serial runs are an operating rule), the API's throughput and `EXPLAIN`, and a period actually frozen twice; the two revisions are seeded rows. The linking test where both apps meet on one database is step 4's ([design 13.6](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/design/weekly-ranking.md#136-한계), [13.12](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/design/weekly-ranking.md#1312-4단계로-넘기는-것)).

### The closing review

A two-axis review of `week9...feat/weekly-ranking-publish` at `dd58d7cf` (18 commits, 3 of them merges; 37 files), with one reviewer on the standards and one on this spec. Standards found no breach of a documented rule and 6 judgement calls. Spec found 1 partial requirement, 2 additions and nothing implemented wrongly; all three were already in design 13.10. Each was checked against the code and decided.

#### What changed

- **`2edfec32`: the read's request is `WeeklyRankingFindRequest`.** It was the only one of the 19 application requests with neither a verb nor `List`. `List` would mislead, since the read returns one ranking, so the verb is the port's `find`. Issue 256's review had kept the old name after `ProductListRequest`; design 13.10 now records the reversal.

A dated note in design 13.9 records the final check above.

#### Left as they were

- **`findItems(snapshotId, limit: Int)` rather than `Pageable`/`Slice`.** ADR 0013 ties `Pageable` to a list envelope with a next page; this top-N read has neither, like `claimDueOutboxes(batchSize)`.
- **Two Korean KDoc sentences extended in Korean** (`ApiControllerAdvice`, `BaseApplicationServiceTest`). They keep old sentences true; the new paragraph is English.
- **The three count methods of `WeeklyRankingPublishStore`.** What repeats is `JdbcClient`'s idiom; each keeps its SQL beside its name and KDoc.
- **The test-only table definitions and the local UTC conversion in commerce-api.** The module boundary forces both, and design 13.6 already records the first.
- **`ProductQueryService.findAllSummaries` as a one-line delegation.** It is the product slice's port and owns the read-only transaction the brand read needs.
- **The Monday check, the advice's reach and the restart spy's two commits.** Each is in design 13.10 with a reason the review checked: `CoreException` has no message parameter, the advice mirrors `handleMethodArgumentTypeMismatch`, and a spy that commits only the publication leaves the test green with the skip deleted.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/research/week9-weekly-ranking-publish-writing-material.md#spec-252-the-closing-review).

## Draft PR body

A draft for the PR from `feat/weekly-ranking-publish` into `week9`, in the shape of step 2's, written after the spec's closing review.

Title: `feat(commerce-batch): rank a frozen snapshot's scores and publish them as its period's weekly ranking, and read the publication through GET /api/v1/rankings/weekly`

````markdown
## Summary

Week 9 step 3. The `weeklyRanking` job gets its third step, `publish`. In one transaction it checks that the scores cover every product of the snapshot, ranks them, and writes the period's publication: a single row per period that only a later revision replaces. commerce-api reads what a period's publication points to through `GET /api/v1/rankings/weekly`. A failed publish leaves the previous publication, and a rerun converges on the same ranking. A second real freeze of one period and the cross-app linking test are step 4's.

- **Spec:** issue 252, with tickets 253–257, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/252#issuecomment-6999565834)
- **Design:** [`docs/design/weekly-ranking.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/design/weekly-ranking.md) section 13: 13.1 the step, 13.2 the tables, 13.3 the read, 13.4 the contract, 13.6 the limits, 13.7–13.8 the scenes and red checks, 13.10–13.11 what differs from the spec and the assignment
- **ADR:** [0039](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/adr/0039-what-a-ranking-period-shows-is-decided-by-a-publication-row-that-only-a-later-revision-replaces.md), what a ranking period shows is decided by a publication row that only a later revision replaces
- **Writing material:** [`docs/research/week9-weekly-ranking-publish-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/docs/research/week9-weekly-ranking-publish-writing-material.md), which holds every ticket's closing comment
- **Glossary:** 순위 (rank) and 게시 (publication) in [`apps/commerce-batch/CONTEXT.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/apps/commerce-batch/CONTEXT.md); the three tables and the columns commerce reads in [`CONTEXT-MAP.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-publish/CONTEXT-MAP.md)

```text
--job.name=weeklyRanking periodStart=… snapshotId=… revision=…   # unchanged
  step freeze, step score                  # unchanged; skipped on a restart when COMPLETED
  step publish                             # one tasklet, one transaction, the app's TX manager
    PublishTasklet
      already ranked?                      # items > 0 and items == scores: skip to the publication
      scores == input products?            # else check() fails the step and writes nothing
      rankScores                           # INSERT … SELECT ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC)
      publish                              # INSERT … ON DUPLICATE KEY UPDATE, moved only by a later revision,
                                           # then a FOR SHARE read-back; "not moved" is a WARN + exit description

GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10      # commerce-api, no login
  WeeklyRankingQueryService                # @ValidatedApplicationService(readOnly = true)
    findPublication(periodStart)           # header: publication JOIN snapshot; none → 200 NOT_PUBLISHED
    findItems(snapshotId, limit)           # by the header's snapshot ID, deleted products out before LIMIT
    ProductFinder.findAllSummaries(ids)    # display fields and like counts, in the same transaction
```

## Review Points

#### 1. Should an earlier revision's publish succeed without moving anything?

Publishing revision 1 after revision 2 leaves the row on revision 2, and the step ends `COMPLETED` with a WARN and an exit description saying so. Failing it would leave a `JobInstance` that no rerun can complete, for a ranking that is not wrong. The write takes no lock first: the conditional upsert alone ends any order of two publishes on the later revision. Serial runs of one period are an operating rule, and no scene runs two at once (ADR 0039).

**Would you fail the step, or lock the period, instead?**

#### 2. Is "items == scores" a sound sign that ranking already happened?

A rerun after a publish that committed under a failed execution must not rank again, or the primary key `(snapshot_id, product_rank)` refuses it. The tasklet skips ranking when the snapshot has items and as many as its scores. Ranking is one `INSERT … SELECT` in the same transaction as the publication, so a partial set cannot be committed. The red check that sets the condition to `false` turns that restart `FAILED` (design 13.8).

**Would you rather store a marker, and where would you commit it?**

#### 3. Is reading batch's tables by SQL under a written contract enough?

commerce-api has no entity for the three tables (`ddl-auto: create` would drop them) and no dependency on commerce-batch. `CONTEXT-MAP.md` lists the nine columns it reads, and its tests create them from a test-only SQL file. That file does not follow a change to batch's schema script; the two first meet in step 4's linking test (design 13.6). The read is two statements, not the assignment's one join, so a period whose ranking has no visible item still answers `PUBLISHED` (design 13.11).

**Would you put the tables in a shared schema module now, or wait for step 4's test?**

## Evidence

**Before:** step 2's job: `freeze` and `score`. The scores existed, but nothing ranked them, nothing said which snapshot a period shows, and commerce-api could not read them.

**After:** on real MySQL, `WeeklyRankingPublishTest` (9 scenes), 6 new scenes in `WeeklyRankingJobRestartTest`, `WeeklyRankingApiTest` (15) and `WeeklyRankingFinderTest` (1). Step 1's 25 freeze scenes and step 2's 6 score scenes pass unchanged with `publish` after them.

- The assignment's week ranks product 7 first (5), 9 second (5), 8 third (1). An empty period publishes with no items. Another period's publication is untouched.
- A later revision moves the publication; an earlier one leaves it and says so. Missing scores fail the step and write nothing.
- A failed publish leaves revision 1's publication and no ranking rows (the assignment's "앞선 게시가 그대로다"). The restart is the same `JobInstance`, runs `freeze` and `score` once in total, and publishes revision 2.
- A publish committed under a failed execution: the rerun is `COMPLETED`, and the ranking rows and the published-at time are unchanged though the clock moved.
- A restarted run and a clean run both give the canonical result checksum `745d50bb…`, computed apart from the code (the assignment's seventh step).
- The API returns the published ranking in rank order, honours `limit`, leaves deleted products and brands out before the limit, answers 200 `NOT_PUBLISHED` with null `snapshotId` and `inputFrozenAt`, ignores a ranked but unpublished revision, and refuses a missing, non-date or non-Monday `periodStart` and a `limit` of 0 or 101 with a message that says why.

| Red check (local, never committed) | Result |
| --- | --- |
| Rank by `RANK() OVER (ORDER BY score DESC)` | 3 of 4 scenes: products 7 and 9 tie, and the primary key refuses the second |
| The publication's three assignments unconditional | The earlier-revision scene: the publication becomes revision 1 |
| `publish` step on `ResourcelessTransactionManager` | The failed-publish scene: revision 2's three ranking rows stay |
| "Already ranked" condition set to `false` | The committed-under-failure rerun: `FAILED` on a duplicate primary key |
| Items read without `product.deleted_at IS NULL` | The three deleted-product scenes: 500 |
| Items read by the snapshot's period, not its ID | The unpublished-revision scene: six items, not three |
| `@Valid` removed from `WeeklyRankingFinder.find` | `WeeklyRankingFinderTest` |

`scripts/final-check --app commerce-batch --app commerce-api` on `2edfec32`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-batch | 8 (7 before) | 60 (45 before) | 0 |
| commerce-api | 115 (113 before) | 1203 (1187 before) | 0 |

Not shown here: two runs of one period at once, the API's throughput and `EXPLAIN`, and a period actually frozen twice (the two revisions are seeded rows).

## Merge Danger

**Door:** two-way

No entity and no migration. `weekly_ranking_item` and `weekly_ranking_publication` are created only in `local` and `test`; a revert leaves them unused.

**Blast Radius:** commerce-batch, commerce-api

- **Every `@ModelAttribute` endpoint** now answers a value the binder cannot convert (`page=abc`) with a message naming the field and the value, instead of Spring's conversion text.
- **Outside `local` and `test`**, the schema script's two new tables must be applied by hand before the first run, like steps 1 and 2's.
- **A `JobInstance` completed before this branch cannot be continued** with `publish`; freeze the period again under a new `snapshotId` and revision.
- **Out of scope:** a second real freeze of one period, the linking test from PG to the API, a republished order event adding nothing, caching the read, Flyway.
````
