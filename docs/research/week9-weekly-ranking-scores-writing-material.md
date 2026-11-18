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
