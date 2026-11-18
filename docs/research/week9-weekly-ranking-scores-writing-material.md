# Week 9 weekly ranking scores: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 246](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246), then the spec's own.

## Issue 247: compute a score for every product of a frozen snapshot

Recorded 2026-11-18 from [issue 247's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/247#issuecomment-6973120270). The text below is the comment's.

Implemented as `855bb577`, on top of `dd663479`, which commits the 점수 entry of `apps/commerce-batch/CONTEXT.md`, and `85d0c137`, which adds the long-index-name rule to `CODING_STANDARDS.md`. `scripts/final-check --app commerce-batch` on `855bb577`, a clean tree, started 2026-11-18T08:13:39Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 6 classes, 39 tests (21 s), 0 failures, 0 errors, 0 skipped**. Spec 237 ended at 5 classes and 33 tests, so this ticket adds 1 class and 6 tests. Only this document changed after `855bb577`.

### Acceptance criteria

- [x] **Running the job for `periodStart=2026-08-17` over the assignment's inputs ends `COMPLETED` with exactly three scores: 7 = 5, 8 = 1, 9 = 5.** `WeeklyRankingScoreTest`, first scene. It reads the whole score table, not only the snapshot's rows, so "exactly" covers the table.
- [x] **A period with no purchase activity ends `COMPLETED` with no score.** Second scene. The `score` step execution is `COMPLETED` with read count 0 and write count 0.
- [x] **The same period run again under another snapshot ID and revision leaves each snapshot with its own three scores.** Fourth scene: `2026-08-17-r1` and `2026-08-17-r2` each hold 5, 1, 5.
- [x] **The snapshot input's secondary index exists under its name with the three columns in order, read from `information_schema.statistics`.** Sixth scene. It lists every non-unique index on the table, so a second index or a reordered column would show.
- [x] **After a completed run the snapshot's state is `FROZEN`.** Fifth scene.
- [x] **`WeeklyRankingFreezeTest` and `BatchRestartProbeTest` pass unchanged in behaviour; tests empty the three ranking tables and `purchase_activity` between scenes.** The freeze test now also empties `weekly_ranking_score`. Its conflict scenes' `stepExecutions.single()` still holds, so a failed freeze runs no `score` step. The probe and its job are untouched.
- [x] **No class in the ranking job refers to `ResourcelessTransactionManager`, and commerce-batch has no `@Entity`.** `grep` over `apps/commerce-batch/src/main` finds `ResourcelessTransactionManager` only in `DemoJobConfig`, which issue 236 removes, and no `@Entity` at all.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **This document is created and holds the closing comment.**

### What was built

- `weeklyRanking` runs `freeze`, then `score`. `score` is `StepBuilder.chunk<Long, ProductScore>(chunkSize).transactionManager(transactionManager)`, the shape the probe verified, with no isolation override, skip or retry.
- **Reader.** `ScoreProductReader` is a `@StepScope` subclass of `JdbcCursorItemReader<Long>`. It reads `SELECT DISTINCT product_id … WHERE snapshot_id = ? ORDER BY product_id`, with the snapshot ID taken from the job parameters. Its state is saved under `scoreProductReader.read.count`.
- **Processor.** `ScoreProcessor` makes one call per product, `WeeklyRankingScoreStore.sumQuantity(snapshotId, productId)`, against `weekly_ranking_snapshot_input` only. The store is a singleton bean, not step-scoped, so the restart ticket can wrap it with `@MockkSpyBean`.
- **Writer.** `ScoreWriter` passes the chunk to `WeeklyRankingScoreStore.saveScores`. That method writes one `INSERT … VALUES (?, ?, ?) AS computed ON DUPLICATE KEY UPDATE score = computed.score` per product. The row alias replaces `VALUES(score)`, which MySQL 8.0.20+ deprecates; the containers run 8.4.
- **Chunk size.** `WeeklyRankingScoreProperties` binds `weekly-ranking.score.chunk-size`. `application.yml` sets it to 100, unmeasured, the way `PgProperties` keeps its values in yml. The class refuses values below 1 at startup.
- **Schema.** `weekly_ranking_score (snapshot_id, product_id, score)` has primary key `(snapshot_id, product_id)`. `IDX_SNAPSHOT_INPUT_ON_SNAPSHOT_ID_AND_PRODUCT_ID_AND_QUANTITY` is declared inside the snapshot input's `CREATE TABLE`.

### Red checks

These are mutations made without committing, run, then reverted:

- **Writer set to `score = weekly_ranking_score.score + computed.score`.** Only the wrong-score scene fails, because product 7 becomes 104.
- **The sum without its `snapshot_id` filter (`WHERE ? IS NOT NULL AND product_id = ?`).** Only the two-snapshot scene fails, because the second snapshot's sums take in the first snapshot's inputs.

The spec's other red checks (`saveState(false)`, `ResourcelessTransactionManager`, and summing from `purchase_activity`) need the failure and restart scenes, so they belong to the next tickets.

### Decisions made while implementing

- **The index name uses the glossary's short table name.** Under `CODING_STANDARDS.md`, the name would be `IDX_WEEKLY_RANKING_SNAPSHOT_INPUT_ON_SNAPSHOT_ID_AND_PRODUCT_ID_AND_QUANTITY`, which is 76 characters, past MySQL's 64. Dropping a column from the name would make it wrong. So `85d0c137` adds a rule to the standard: only when a name passes 64 characters, its table part becomes the glossary's shorter name (스냅샷 입력 → `SNAPSHOT_INPUT`), and the columns stay in full.
- **The reader lives under `step/` as a subclass, not as a builder `@Bean` in the job config.** Batch 6.0.6 gives `JdbcCursorItemReader` a `(DataSource, sql, RowMapper)` constructor, so a step-scoped component can sit beside the processor and writer, where the spec puts them.
- **The reader leaves the driver's fetch size at its default.** Connector/J then loads all of a snapshot's distinct product IDs before the first read. A catalog bounds that list, unlike the freeze's digest, which streams every input with `Int.MIN_VALUE`.
- **No test method returns a `StepExecution`.** `@SpringBatchTest`'s `StepScopeTestExecutionListener` treats any such method in the test class, private helpers included, as a factory for the step context. It calls the method with no arguments, so every scene in the class fails in `prepareTestInstance`. The test looks up the `score` step execution inline instead.
- **Issue 248's wrong-score scene is already in `855bb577`.** `WeeklyRankingScoreTest` stores 99 for product 7 under the snapshot ID before the run and expects 5. Its red check, the additive writer, is recorded above. It went in past this ticket's scope, so 248 is left with whatever its own criteria add beyond the scene and the check.
- **The ranking tests share their helpers in `com.loopers.support.WeeklyRankingJobs`.** These are `jobParameters(…)` and the `JdbcClient` extensions `insertActivity` and `insertAssignmentActivities`. This follows `CODING_STANDARDS.md`'s 도우미 rule now that two classes use them, and the restart test will be a third.

### Review

A two-axis review ran before the commit.

- **Standards.** One hard finding: the index name broke "테이블은 DB의 테이블 이름 그대로다", and the rule above resolves it. A judgement call about helpers duplicated across the two test classes was fixed by moving them to `support`.
- **Spec.** No missing requirement. "Exactly three scores" now reads the whole table, where before it read only one snapshot's rows.
- **Left as they are:**
  - `@Value` without `@param:` on the reader's snapshot ID. That parameter is not a property, since `init` uses it, so the plain annotation is correct.
  - The chunk size's default living only in yml, as `PgProperties`' values do.
  - The `require(chunkSize >= 1)` check, which follows validation in every layer.
  - `StepMonitorListener` on `score`, matching `freeze`.
  - The parameter names spelled as literals in the test helper, as the freeze test already did.

## Issue 248: replace a stored score instead of adding to it

Recorded 2026-11-18 from [issue 248's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/248#issuecomment-6973246933). The text below is the comment's.

Implemented as `8beb48e7`, on top of `855bb577`, which already held the scene and its red check (recorded under issue 247). `scripts/final-check --app commerce-batch` on `8beb48e7`, a clean tree, started 2026-11-18T08:28:10Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 6 classes, 39 tests (20 s), 0 failures, 0 errors, 0 skipped**. That is the same count as issue 247, since this ticket adds an assertion and no scene. Only this document changed after `8beb48e7`.

### Acceptance criteria

- [x] **With a score of 99 stored beforehand for product 7 under the run's snapshot ID, running the job ends `COMPLETED` and product 7's score is 5, with products 8 and 9 at 1 and 5.** This is `WeeklyRankingScoreTest`, `a wrong score stored beforehand under the snapshot ID is replaced by the computed one`. In `855bb577` the scene threw away the `JobExecution` and checked only the scores. `8beb48e7` keeps the execution and asserts `COMPLETED` before the scores, as the sibling scenes do.
- [x] **Red check, not committed: with the writer changed to add to the stored score, this scene fails.** I ran it again on `8beb48e7`'s tree with `ON DUPLICATE KEY UPDATE score = weekly_ranking_score.score + computed.score`. Of the class's 6 tests, 1 failed: this scene, with product 7 at 104 and products 8 and 9 at 1 and 5. I then reverted the writer.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** See the final-check run above.
- [x] **The closing comment is appended to `docs/research/week9-weekly-ranking-scores-writing-material.md`.**

### No production change

The writer already sets `score = computed.score` (`855bb577`), so the scene was green on the code as it stood. The ticket expected that outcome.

### Review

I ran a two-axis review on the diff before committing it. Neither axis found a hard issue. I'm leaving two judgement calls as they are:

- **`after a completed run the snapshot's state is still FROZEN` does not assert the job's status.** Its subject is the snapshot's state, it sits outside this ticket's scene, and the restart ticket (249) will assert `FROZEN` after a failed run as well.
- **This scene reads its rows with `scores(snapshotId)`, while the first scene uses `allScores()`.** The criterion names only the run's snapshot, and the two-snapshot scene already covers rows leaking across snapshots.

## Issue 249: continue a failed score run from the product it stopped at

Recorded 2026-11-18 from [issue 249's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/249#issuecomment-6973810797). The text below is the comment's. Spec 246's closing review (2026-11-18) corrected one sentence in both: under the processor-summing mutation, the two failing scenes are the restart scenes that read the scores, not every restart scene that completes.

Implemented as `fda30058`, a test class only; no production code changed. `scripts/final-check --app commerce-batch` on `fda30058`, a clean tree, started 2026-11-18T08:37:08Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 7 classes, 45 tests (19 s), 0 failures, 0 errors, 0 skipped**. Issue 248 ended at 6 classes and 39 tests, so this ticket adds 1 class and 6 tests. Only this document changed after `fda30058`.

### Acceptance criteria

- [x] **First run failing before product 9: `FAILED`; scores for 7 and 8 only; the `score` step's `ExecutionContext`, read back from the database, holds `read.count` = 2; the snapshot exists and is `FROZEN`.** `WeeklyRankingJobRestartTest`, first scene. The count is read through `jobRepository.getLastStepExecution(…)` under `scoreProductReader.read.count`, the way the probe reads its own.
- [x] **Restart with the same three values: the same `JobInstance`, a new `JobExecution`, `COMPLETED`; scores 5, 1, 5.** Second scene.
- [x] **The restart's `score` step execution reads 1 and writes 1, and across both runs products 7 and 8 were summed once each.** Second scene. Between the runs, `clearMocks(scoreStore, answers = true, recordedCalls = false, verificationMarks = false)` drops the throwing answer but keeps the first run's recorded calls, so `verify(exactly = 1)` counts both runs.
- [x] **The restart did not run `freeze`: one `freeze` step execution, and the snapshot's frozen-at time, count, checksum and input rows equal those after the first run, despite the moved clock and the added purchase.** Third scene, by `jobRepository.getStepExecutionCount(instance, "freeze") == 1`. Between the runs the test's `MovableClock`, the context's primary `Clock`, moves a day, and order 45 buys product 9 × 4 inside the period.
- [x] **Failure inside the first chunk (at product 8): `FAILED`, no score, no advanced read count; absent or 0 recorded. The restart processes all three and ends with 5, 1, 5.** Fourth and fifth scenes. **The key is absent**: the stored `score` step execution exists, but its `ExecutionContext` has no `scoreProductReader.read.count`. The restart's step reads 3 and writes 3.
- [x] **Red checks, not committed, each recorded with the scene that failed.** See below.
- [x] **`BatchRestartProbeTest` and its job are untouched.** `fda30058` touches one file, the new test.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

### Red checks

Each was made without committing, run against `WeeklyRankingJobRestartTest`, then reverted:

- **The reader with `setSaveState(false)`.** Two scenes fail. In the first-run scene, the stored read count is absent where 2 was expected. In the restart scene, the `score` step reads 3 and writes 3 where 1 and 1 were expected. The scores assertion above them still passes with 5, 1, 5. The overwrite writer hides the reprocessing, and the metadata catches it.
- **The `score` step on `ResourcelessTransactionManager`.** None of the five sum-failure scenes fails. A failure in `sumQuantity` always comes before the chunk's write, so there is never a written but uncommitted score for a missing transaction to leak, and Batch stores the metadata through its own repository either way. Spec 246 predicted that "저장된 읽은 수나 chunk 안의 실패 장면이 붉다"; that does not hold. A sixth scene was added for it (below). Under this mutation, it fails: product 9's score, 5, stays though the run failed and the stored read count says 2.
- **The processor summing from `purchase_activity`** (`WHERE ? IS NOT NULL AND product_id = ? AND deleted_at IS NULL`). Both restart scenes that read the scores fail with product 9 = 9: its frozen 5 plus order 45's 4, recorded after the freeze. The restart scene that checks it does not freeze again reads no scores and passes.

### Decisions made while implementing

- **A sixth scene fails after the write, through the same spy bean.** The ticket put the failure on "the method that sums one product's quantity". That seam cannot show story 15, that a chunk's scores and the reader's position commit together, so the `ResourcelessTransactionManager` red check had no scene. The sixth scene stubs `saveScores` for the chunk holding product 9 to call the original and then throw. There is still no fault-injection code in production. On the real step, it leaves 7 and 8, no score for 9, and a read count of 2. Issue 250 should carry this, and the correction to spec 246's prediction, into `docs/design/weekly-ranking.md`.
- **The moved clock is a test-only `MovableClock`.** It is nested in the test class, with a nested `@TestConfiguration` that makes it the primary `Clock`, as `WeeklyRankingFreezeTest`'s `FrozenClockConfig` stops its clock. `@BeforeEach` sets it back. commerce-api's `TestClock` is in another module, and one class needing a movable clock does not earn a shared one in `support`.
- **The no-refreeze scene also asserts the restart `COMPLETED`.** A second `freeze` would not replace the snapshot. `FreezeTasklet`'s conflict check would refuse the existing snapshot ID and fail the run. The equal snapshot and input rows alone therefore cannot tell a skipped freeze from a refused one. The single `freeze` step execution and the completed restart can.
- **The added purchase is of product 9.** Product 9 is the one the restart scores, so a sum over live purchase activity shows in the restart's own write.

### Review

A two-axis review ran before the commit.

- **Spec.** Nothing missing. One defect: the no-refreeze scene's comment claimed a second freeze would change the snapshot, and the scene did not assert the restart's status. Both were fixed as described above. The `saveScores` scene is a deviation from the ticket's seam, recorded above.
- **Standards.** No hard finding. Fixed: "the sum" as a noun in comments, which brushed against the glossary's Avoid list for 점수, became `sumQuantity`. A `StepCounts` helper and data class became the inline `.extracting(StepExecution::getReadCount, StepExecution::getWriteCount)` that `WeeklyRankingScoreTest` uses.
- **Left as they are:**
  - The private `scores()` and `snapshotInputs()` readers, which have the same shape as those in the other two ranking test classes. Each class keeps its own private row readers, as the precedents do.
  - The repeated three-line arrange in the restart scenes, kept explicit per scene.
  - The `snapshotId` parameter on the two failure stubs, as in the freeze test's `failAfterTheInputsAreWritten`.

## Issue 250: the score step in the weekly ranking design, and the final check

Recorded 2026-11-18 from [issue 250's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/250#issuecomment-6974014315). The text below is the comment's.

Implemented as `44a0df4f`, a design document change only. `scripts/final-check --app commerce-batch` on `8563adad`, a clean tree whose code is `fda30058`'s, started 2026-11-18T08:59:42Z with `BOOTSTRAP_SERVERS` unset: ktlint passed (2 s), **commerce-batch 7 classes, 45 tests (26 s), 0 failures, 0 errors, 0 skipped**. Step 1's final check ended at 5 classes and 33 tests, so step 2 adds 2 classes (`WeeklyRankingScoreTest`, `WeeklyRankingJobRestartTest`) and 12 tests. Only documents changed after `8563adad`.

### Acceptance criteria

- [x] **The design doc holds the sections, in the glossary's terms, and passes the doc checks in the pre-commit hook.** [`docs/design/weekly-ranking.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/design/weekly-ranking.md):
  - §1.1 shows step 2's state and spec 246.
  - §1.3 lists what each of 247–250 built.
  - The new §12 covers:
    - the `score` step (12.1) and the score table and index (12.2);
    - why the state stays `FROZEN`, and the restart (12.3);
    - what was not chosen (12.4) and the limits (12.5);
    - the scenes (12.6) and red checks (12.7);
    - the final check (12.8);
    - where the build differs from the spec (12.9) and what is handed to step 3 (12.10).
  - §3, §4, §8 and §11 carry dated notes that close "a finished freeze is not rerun", the index, the new state and the probe's four facts.
  - `check-doc-notes` reports one CHANGED line: the §1.1 status cell, which `--warn-changed` allows. `check-doc-links` passes.
- [x] **`scripts/final-check --app commerce-batch` passes on a clean tree, and the doc records the commit, start time and counts.** See the run above and design §12.8.
- [x] **The count this step added is stated against step 1's 5 classes and 33 tests.** +2 classes, +12 tests.
- [x] **The closing comment is appended to `docs/research/week9-weekly-ranking-scores-writing-material.md`.**
- [x] **The spec is not closed by this ticket.** Spec 246 is closed by following `docs/agents/spec-closing.md`.

### Decisions made while implementing

- **Step 2's design is one new section, §12.** Step 1's sections stay as they were and gain dated notes that point into it. This keeps the doc insert-only under `check-doc-notes`. Step 3 can add §13 the same way.
- **A red check was rerun for spec 246's prediction about the sum count.** The spec predicted that `saveState(false)` would turn red "the restart's read and write counts and the sum count". In the committed restart scene, the read/write-count assertion comes before `verify(exactly = 1)`, so the run stops there.
  - I ran the mutation again without committing, with the `verify` moved first.
  - Product 7 was summed twice ("2 matching calls found, but needs exactly 1").
  - On the six-scene class, three scenes fail, not the two recorded under issue 249. That run had five scenes; the after-write scene also asserts the read count of 2.
  - Both files were reverted. Design §12.7 and §12.9 record this.
- **The index is not shown by `EXPLAIN` to answer both queries from itself alone.** That is the spec's judgement, and design §12.5 lists it as a limit. The test checks only that the index exists, under its name and in column order.

### Review

A two-axis review ran before the commit.

- **Spec.**
  - Fixed: the `saveState(false)` sum-count prediction that was never observed (above).
  - Fixed: §11 now points to the step-3 handoff in §12.10.
  - Fixed: a §12.9 row that cited the ticket split as if it were the spec.
- **Standards.**
  - Fixed: the `saveState(false)` red check counted 2 scenes where 3 fail.
  - Fixed: the scenes and red checks were merged in one section. They are now §12.6 and §12.7, parallel to §9.1 and §9.2.
  - Fixed: the probe re-check was more detailed in the §11 note than in §12.3. The detail moved to §12.3.
  - Fixed: the freeze test's change is now described as also moving its helpers.
- **Left as they are:** the short restatements in §12.5 and §12.10 of points made in §12.2 and §12.3 (the `JobInstance` limit, no `EXPLAIN`, the score count compared at publish). Each one is a pointer to its home section.

## Spec 246: the closing review

Recorded 2026-11-18 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246#issuecomment-6977981294) of [spec 246](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246). The text is the comment's, with its blob URLs.

Step 2 of week 9 is built, and its closing review is done. `scripts/final-check --app commerce-batch` on `b63678a2`, a clean tree, started 2026-11-18T09:45:43Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 7 classes, 45 tests (22 s); 0 failures, 0 errors, 0 skipped**. Step 1 ended commerce-batch at 5 classes and 33 tests, so this step adds 2 classes and 12 tests. The review's two new checks are extra lines in existing tests, so the count did not move. This branch does not touch commerce-streamer, so it was not run. Only documents change after `b63678a2`.

Tickets 247–250 are closed, each with its own comment. [Design 1.3](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/design/weekly-ranking.md#13-2단계가-만든-것) lists what each one built, and [design 12](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/design/weekly-ranking.md#12-점수-2단계) holds the step. No ADR was added.

### Where the implementation differs

| The spec said | What is there | How it was found |
| --- | --- | --- |
| On `ResourcelessTransactionManager`, "저장된 읽은 수나 chunk 안의 실패 장면이 붉다" (red check) | No sum-failure scene goes red: the failure always comes before the chunk's write, and Batch stores the metadata through its own repository either way. A scene failing after `saveScores` writes was added, and it goes red | Issue 249 |
| With the reader's `saveState(false)`, the restart's read and write counts and the sum counts go red (red check) | The committed scenes stop at the read and write counts and never reach the sum counts. The sum counts going red was seen in a separate, uncommitted reorder of the assertions | Issue 250 |
| Failures are made by a spy on "the method that sums one product's quantity" | The after-write scene uses the same spy bean's `saveScores`. The summing seam cannot show story 15 (a chunk's scores and read count commit together). Production code still has no failure switch | Issue 249 |
| A chunk failure stores a read count that is absent or 0; which one is decided while implementing | Absent: the stored `score` step execution has no key in its `ExecutionContext` | Issue 249 |
| A named secondary index (name not chosen) | The standard name came to 76 characters, past MySQL's 64. `CODING_STANDARDS.md` now shortens the table part to its glossary name only in that case | Issue 247 |

All of these are in [design 12.9](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/design/weekly-ranking.md#129-명세와-다른-것).

**Now proven:** "a finished freeze is not rerun", which step 1 left to this branch. A restart after a score failure runs one `freeze` step execution in total, and leaves the snapshot and its inputs as the first run froze them, though the clock moved and a purchase was added between the runs.

**Not proven here:** throughput at the default chunk size of 100, and whether the secondary index answers both queries from the index alone (no `EXPLAIN`). The assignment's "앞선 게시가 그대로다" and its seventh step need ranks and a publish pointer, so they are step 3's ([design 12.5](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/design/weekly-ranking.md#125-한계)).

### The closing review

A two-axis review of `week9...feat/weekly-ranking-scores` at `83920dcd` (12 commits, 16 files), with one reviewer on the standards and one on this spec. Standards found 2 breaches of documented rules and 7 judgement calls. Spec found 2 partial requirements, 4 additions (all already in design 12.9) and 1 wrong sentence in the evidence. Each was checked against the code and decided.

#### What changed

- **`184ae7f9`: the readers the tests share live in `support`.** `scores` and `snapshotInputs`, with their row types, had two copies each, in the score, restart and freeze tests. They are now `JdbcClient` extensions in `WeeklyRankingJobs`, beside `insertActivity`.
- **`5689ea90`: the design no longer calls the score upsert "덮어 쓰기".** The batch glossary lists 덮어쓰기 among the words to avoid for a conflict. The design now says "점수 바꾸기", after the test's *replaced*.
- **`458cd90d`: `ProductScore` has its own file.** The processor, the writer and the job config share it, so it sits beside them like `SnapshotInput` rather than at the end of the store's file.
- **`9a71527c`: story 19 counts product 9 too.** The restart scene now asserts it was summed twice: the first run's call that threw, and the restart's. Read and write counts cannot show a processor called again by a retry. An uncommitted change to "once" turned only that line red (`2 matching calls found, but needs exactly 1 calls`).
- **`d7deb546`: the failing-freeze scene shows `score` did not run.** It asserts the job ran only the `freeze` step; before, only the conflict scenes' `single()` showed it. An uncommitted change expecting both steps turned only that line red (`["freeze"]`).
- **Issue 249's closing comment is corrected.** Under the processor-summing mutation, the two failing scenes are the restart scenes that read the scores, not "both scenes whose restart completes": three restarts complete, and the one that checks there is no second freeze reads no scores. The committed copy matches.

`b63678a2` adds dated notes for these to design 12.

#### Left as they were

- **The score-step lookup, the four-table `emptyTables` list and the `StepExecution` KDoc paragraph, each repeated.** A method returning a `StepExecution` would be taken for a step-context factory by `@SpringBatchTest`. The table list and the warning belong to each class.
- **`MovableClock` beside commerce-api's `TestClock`.** Another module's test classpath cannot reach it, and the restart scenes need a fixed start, not a running clock.
- **The literal parameter names in `WeeklyRankingJobs.jobParameters`.** They are what an operator types, and the tests pin them.
- **The reader's bare `@Value`.** Its `snapshotId` is a plain constructor parameter, not a property, so `@param:` would choose nothing.
- **The four additions:** the after-write scene, the long-index-name rule, the `chunkSize >= 1` check and `WeeklyRankingJobs`. Each is recorded in design 12.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/research/week9-weekly-ranking-scores-writing-material.md#spec-246-the-closing-review).

## Draft PR body

A draft for the PR from `feat/weekly-ranking-scores` into `week9`, in the shape of step 1's, written after the spec's closing review.

Title: `feat(commerce-batch): score every product of a frozen snapshot in a chunk step, and continue a failed score run where it stopped`

````markdown
## Summary

Week 9 step 2. The `weeklyRanking` job gets its second step, `score`. A chunk step reads the products of a frozen snapshot, sums each one's quantity from the snapshot inputs only, and stores the result as its score. A run that fails while scoring continues, with the same three values, from the product it stopped at, without freezing again. Ranks, publishing and the API are step 3's.

- **Spec:** issue 246, with tickets 247–250, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/246#issuecomment-6977981294)
- **Design:** [`docs/design/weekly-ranking.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/design/weekly-ranking.md) section 12: 12.1 the step, 12.2 the table and index, 12.3 state and restart, 12.5 the limits, 12.6–12.7 the scenes and red checks, 12.9 what differs from the spec
- **Writing material:** [`docs/research/week9-weekly-ranking-scores-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/docs/research/week9-weekly-ranking-scores-writing-material.md), which holds every ticket's closing comment
- **Glossary:** 점수 (score) in [`apps/commerce-batch/CONTEXT.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-scores/apps/commerce-batch/CONTEXT.md). No ADR: the score table follows ADR 0038

```text
--job.name=weeklyRanking periodStart=… snapshotId=… revision=…   # unchanged from step 1
  step freeze                              # unchanged; skipped on a restart because it is COMPLETED
  step score                               # chunk(weekly-ranking.score.chunk-size, default 100), the app's TX manager,
                                           # no skip, no retry
    ScoreProductReader                     # JdbcCursorItemReader: SELECT DISTINCT product_id … WHERE snapshot_id = ?
                                           # ORDER BY product_id; saves scoreProductReader.read.count
    ScoreProcessor                         # one SUM(quantity) per product over the snapshot inputs, never purchase_activity
    ScoreWriter                            # INSERT … AS computed ON DUPLICATE KEY UPDATE score = computed.score
                                           # the chunk's scores and read count commit together
```

## Review Points

#### 1. Is Batch metadata enough to say the scores are done?

The snapshot stays `FROZEN`; there is no `SCORED` state. Writing one would sit outside the chunk's commit, leaving a gap where the scores and the state disagree. Step 3's publish will compare the score count with the snapshot's product count instead (design 12.3).

**Would you rather have a state row, and where would you commit it?**

#### 2. Is one query per product the right processor?

It is the assignment's shape (section 4): reader, processor, writer each with one job. A `GROUP BY` reader would remove the per-product query but leave the processor empty. A chunk of 100 is 100 queries; throughput was not measured (design 12.4, 12.5).

**Would you trade the assignment's shape for the `GROUP BY` reader?**

#### 3. Is an index that local databases only get by dropping the table acceptable?

The secondary index is inside `CREATE TABLE IF NOT EXISTS`, which does not touch an existing table, and MySQL has no `CREATE INDEX IF NOT EXISTS`. A local database with step 1's table drops it and starts again. No environment keeps ranking data yet, so ADR 0038's trigger for Flyway, the first `ALTER` on kept data, has not come (design 12.2).

**Is dropping the local table acceptable until then?**

## Evidence

**Before:** step 1's job: `freeze` only, and "a finished freeze is not rerun" left unproven because no later step could fail.

**After:** `WeeklyRankingScoreTest` (6 scenes) and `WeeklyRankingJobRestartTest` (6, chunk size 2) on real MySQL, with step 1's 25 freeze scenes still passing now that `score` runs after `freeze` (the failing-freeze scene also asserts that `score` did not run):

- The assignment's week scores product 7 = 5, 8 = 1, 9 = 5; orders 42 and 43 sum into one score for product 7.
- A wrong score stored beforehand is replaced, not added to. Two snapshots of one period keep their own scores.
- A run failing before product 9 stores `scoreProductReader.read.count` = 2 with scores for 7 and 8. The restart is the same `JobInstance`, reads 1 and writes 1, and sums 7 and 8 once each across both runs.
- The restart does not freeze again: one `freeze` step execution, the same snapshot and inputs, though the clock moved and order 45 was added between the runs.
- A failure after product 9's score is written, before its chunk commits, leaves no score for 9 and a read count of 2.

| Red check (local, never committed) | Result |
| --- | --- |
| Writer adds to the stored score | The wrong-score scene: product 7 is 104 |
| Sum without the `snapshot_id` condition | The two-snapshot scene: the second snapshot's scores include the first's inputs |
| Reader with `setSaveState(false)` | Three restart scenes: no stored read count, and the restart reads 3 and writes 3 |
| `score` step on `ResourcelessTransactionManager` | The after-write scene: product 9's score 5 stays in a failed run |
| Processor sums from `purchase_activity` | The two restart scenes that read scores: product 9 is 9 |

`scripts/final-check --app commerce-batch` on `b63678a2`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-batch | 7 (5 before) | 45 (33 before) | 0 |

Not shown here: throughput at chunk size 100, and that the secondary index answers both queries from the index alone (no `EXPLAIN`).

## Merge Danger

**Door:** two-way

No entity and no migration. `weekly_ranking_score` and the new index are created only in `local` and `test`; a revert leaves them unused.

**Blast Radius:** commerce-batch

- **Outside `local` and `test`**, the schema script's new table and index must be applied by hand before the first run, like step 1's tables.
- **A local database with step 1's tables** must drop `weekly_ranking_snapshot_input` to get the index.
- **A `JobInstance` completed on this branch cannot be continued** once step 3 adds `publish`; freeze the period again under a new `snapshotId` and revision.
- **Out of scope:** ranks, publishing, the API, the assignment's "앞선 게시가 그대로다" and its seventh step, Flyway, throughput.
````
