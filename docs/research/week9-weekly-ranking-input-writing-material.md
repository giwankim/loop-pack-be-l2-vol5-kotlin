# Week 9 weekly ranking input: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237), then the spec's own.

## Issue 238: a failed chunk step restarts from its stored read count on Spring Batch 6

Implemented as `bc44b8fd`, on top of `6b31449c`, which commits the weekly ranking context: `apps/commerce-batch/CONTEXT.md`, the third row and the `purchase_activity` contract in `CONTEXT-MAP.md`, and the three-context tree in `docs/agents/domain.md`. `scripts/final-check --app commerce-batch` on `bc44b8fd`, a clean tree, started about 2026-11-17T07:41Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 3 classes, 5 tests (15 s), 0 failures, 0 errors, 0 skipped**. The template ended at 2 classes and 3 tests, so this ticket adds 1 class and 2 tests. Only this document changed after `bc44b8fd`.

### Acceptance criteria

- [x] **The first run ends `FAILED`; the written table holds 7 and 8; the stored step `ExecutionContext` has `purchaseProductProbe.read.count` = 2.** `BatchRestartProbeTest`, first scene. The count is read back from the database through `JobRepository.getLastStepExecution`, not from the execution the launch returned.
- [x] **The restart, with no metadata removed in between, is the same `JobInstance` and a new `JobExecution`, and ends `COMPLETED`.** Second scene. Nothing runs between the two launches.
- [x] **After the restart the written table holds 7, 8 and 9 once each, and 7 and 8 were not written again.** The written table records the job execution that wrote each row, so the scene asserts the exact rows: 7 and 8 by the first execution, 9 by the restart.
- [x] **No probe class or table exists in main sources or main resources.** The job is a top-level `@TestConfiguration` that only the probe's test imports, and the test creates both tables with `CREATE TABLE IF NOT EXISTS`.
- [x] **The three context files are committed first, unchanged in meaning.** `6b31449c`. One word changed from the working tree: `CONTEXT-MAP.md` said "ADR은 둘 다" right after "맥락이 셋이다", so it now says "모두", as `domain.md` already did ("All contexts").
- [x] **`DemoJobE2ETest` still passes.** In the run above, untouched.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

### What the probe shows on Batch 6.0.6

- `StepBuilder.chunk(2)` with `.transactionManager(…)` builds Batch 6's `ChunkOrientedStep`. The step runs on the application's `PlatformTransactionManager` (JPA's), never `ResourcelessTransactionManager`.
- The processor throws at product 9. The chunk of 7 and 8 has committed with `read.count` 2. The failed chunk rolls back, and the stored count stays 2, not 3.
- A second launch with the same identifying parameters (`minProductId=7`) restarts the failed instance. The cursor reader skips the 2 rows it had read and processes only 9.
- Mutation check: with `.saveState(false)` on the reader, both scenes fail. The first finds no stored count, and the restart writes 7 and 8 a second time.

### Decisions made while implementing

- **The restart is a second `startJob` with the same parameters, not `JobOperator.restart(JobExecution)`.** Spec 237's 확인한 사실 4 names `restart(JobExecution)`. But the ranking job is rerun the way the template runs it: `--job.name` and the same parameters on the command line, which is a start. So the probe proves that path, and `restart(…)` is not exercised.
- **The failure is a switch bean (`ProbeFailure`) the test sets, not a job parameter.** A parameter would change the job instance, and the restart must use the same parameters.
- **The reader's step-scoped parameter is `minProductId`.** The reader selects `product_id >= ?`, so the parameter feeds the query rather than sitting unused.
- **`BaseBatchJobTest` is the job test base (ADR 0012): `@SpringBootTest` with `spring.batch.job.enabled=false`, `@SpringBatchTest`, MySQL and Redis.** It exposes `jobOperatorTestUtils`, `jobRepository` and `jdbcClient`. `JdbcClient` is new to the repo's tests, which elsewhere take a `JdbcTemplate`. The spec has the ranking tables read and written with `JdbcClient`, so the tests use the same client.
- **`emptyTables(vararg tableNames, keepBatchMetadata = false)` deletes rows and leaves the metadata's sequence tables alone.** Batch reads its next IDs from those one-row tables. The ticket asks for the `keepBatchMetadata` option, though no scene passes `true` yet. The probe needs no option, because nothing runs between its two launches.

### Review

A two-axis review ran on the code before the commit.

- **Spec:** no missing requirement. It raised the `restart(JobExecution)` point recorded above.
- **Standards:** fixed before `bc44b8fd`.
  - The test took its job and switch through `@Autowired` fields. Now it takes them through the constructor, as `DemoJobE2ETest` does.
  - The probe tables were emptied in both `setUp` and `tearDown`. Now only `setUp` empties them.
  - A `!!` on the stored step execution is now a `requireNotNull` with a message.
  - The metadata table names are now backtick-quoted like the others.
- **Left as they are:**
  - The unused `keepBatchMetadata`: the ticket asks for it.
  - The `purchaseProductProbe` naming: the ticket names the reader.
  - Cleanup in the subclass rather than the base: the base can't know a scene's own tables.
  - `DemoJobE2ETest` and `CommerceBatchApplicationTest` not moved onto the base: the ticket leaves the demo job and its test untouched (issue 236). The streamer's context test doesn't extend its base either.
