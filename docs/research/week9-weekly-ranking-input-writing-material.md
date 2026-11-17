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

## Issue 239: freeze one ranking period's purchase activity into a snapshot

Implemented as `468b5406`. `scripts/final-check --app commerce-batch` on `468b5406`, a clean tree, started 2026-11-17T08:21:30Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 12 tests (19 s), 0 failures, 0 errors, 0 skipped**. Issue 238 ended at 3 classes and 5 tests, so this ticket adds 2 classes and 7 tests. Only this document changed after `468b5406`.

### Acceptance criteria

- [x] **Checksum, no database: the assignment's four inputs give the expected canonical text and checksum as literal values.** `SnapshotInputDigestTest`, first scene. The canonical text is a literal, and the checksum `d07eff39…4720b` was computed apart from the code with `printf … | shasum -a 256`.
- [x] **Checksum, no database: empty input gives the SHA-256 of the empty string; a time with no fraction and a time with microseconds are both written with six fraction digits and `Z`.** Second and third scenes.
- [x] **Running the job for `periodStart=2026-08-17` over those inputs ends `COMPLETED` and leaves four snapshot inputs, one per order and product.** `WeeklyRankingFreezeTest`, first scene. The activities go in out of key order, and the inputs are read back with their UTC wall times.
- [x] **The snapshot is `FROZEN` with count 4, the same checksum as the database-free test, the given period start and revision, and the test clock's time.** Second scene, with revision 3. The clock stands at `2026-08-24T01:02:03.123456789Z`, and `frozen_at` reads `2026-08-24 01:02:03.123456`, cut to microseconds and not shifted to Seoul time.
- [x] **A period with no purchase activity ends `COMPLETED` with count 0 and the empty-input checksum.** Third scene. One activity from the week before is present and left out.
- [x] **Starting the test context twice against the same database does not fail on the schema script and keeps existing rows.** Fourth scene. It does not start a second context. It calls the context's `ApplicationDataSourceScriptDatabaseInitializer.initializeDatabase()` again after a freeze. That is the bean `spring.sql.init` runs at startup, with the same script. The scene asserts the script ran and the snapshot and its four inputs are still there.
- [x] **No class in the ranking job refers to `ResourcelessTransactionManager`, and commerce-batch has no `@Entity`.** `grep` finds `ResourcelessTransactionManager` only in `DemoJobConfig`, and finds no `@Entity` under `apps/commerce-batch/src`. No ArchUnit rule was added.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

### What was built

- **`WeeklyRankingJobConfig`**: job `weeklyRanking`, one tasklet step `freeze`. The step runs on the application's `PlatformTransactionManager` (JPA's), with `transactionAttribute` set to `ISOLATION_READ_COMMITTED`. There is no incrementer. The config loads only when `spring.batch.job.name=weeklyRanking`, as the template's demo job does.
- **`FreezeTasklet`** (`@StepScope`): takes `periodStart` (`LocalDate`), `snapshotId` (`String`) and `revision` (`Long`) from `jobParameters`. It reads the `Clock` once and cuts it to microseconds. It computes the week from `periodStart.atStartOfDay(UTC)` plus 7 days. Then it copies, digests and stores the snapshot.
- **`WeeklyRankingSnapshotStore`**: all of the step's SQL, through `JdbcClient`.
  - Copy: one `INSERT … SELECT` from `purchase_activity` by the contract's columns, with `deleted_at IS NULL`.
  - Digest: reads the inputs back in `order_id`, `product_id` order through a row callback, with `withFetchSize(Int.MIN_VALUE)`, Connector/J's switch for streaming rows instead of buffering the whole result.
  - Snapshot: the `FROZEN` row.
- **`SnapshotInputDigest`**: pure code. It adds one input at a time into a SHA-256 and counts them. `canonicalLine` writes one line of the canonical text.
- **`JdbcUtcTimes`**: the one place an `Instant` becomes a UTC `LocalDateTime` for JDBC, and back.
- **`ClockConfig`**: a `Clock.systemUTC()` bean, as in commerce-api.
- **`sql/weekly-ranking-schema.sql`**: the spec's two tables, with `CREATE TABLE IF NOT EXISTS`. `spring.sql.init` (`mode: always`, `schema-locations`) runs it only in the `local, test` block.
- **Tests**:
  - `sql/purchase-activity.sql` (test-only): `id`, the four contract columns, `deleted_at` and `IDX_PURCHASE_ACTIVITY_ON_CONFIRMED_AT`, run by `@Sql`.
  - `com.loopers.support.WeeklyRankingInputs`: the assignment's inputs and the two checksums, shared by both test classes.

### Decisions made while implementing

- **The four inputs' confirmation times were chosen here.** The assignment gives none. They are:
  - order 42: `2026-08-17T00:00:00Z`, both products, since one order is confirmed once
  - order 43: `2026-08-19T13:45:30.123456Z`
  - order 44: `2026-08-23T23:59:59.999999Z`

  Together they cover a time with no fraction, a time with microseconds, and the last microsecond of the week.
- **The job is conditional on `spring.batch.job.name`, as in the template.** It exists only when it is the job being run. Its test sets the property with `@TestPropertySource`, as `DemoJobE2ETest` does, and keeps `spring.batch.job.enabled=false` from the base.
- **The seam the later failure scenes will need is `WeeklyRankingSnapshotStore.digestInputs`.** It is the one call between the copy and the snapshot row. Spec 237 lets the implementation name this seam. Ticket 243 can spy the store there.
- **Streaming uses the driver's fetch size, not only a callback.** A `RowCallbackHandler` alone still lets Connector/J buffer the whole result first. Nothing else runs on the connection while the stream is open, and commerce-batch has no entities for Hibernate to flush in between.

### Review

A two-axis review ran on the code before `468b5406`.

- **Spec:** no missing requirement. It raised the schema scene's stand-in for a second context start, recorded above.
- **Standards:** fixed before the commit.
  - The test's `InputRow` used a word the glossary avoids ("입력 행"). It and `SnapshotRow` are now `StoredSnapshotInput` and `StoredSnapshot`.
  - The freeze test imported the checksum constants from the checksum test's companion. Both now read them from `com.loopers.support.WeeklyRankingInputs` (CODING_STANDARDS 도우미). The freeze test builds its inserts from the shared inputs, so they are no longer written out three times. The expected rows stay literal.
  - A one-class clock config sat in `com.loopers.support.test`, the package for base classes. It is now nested in `WeeklyRankingFreezeTest`.
  - `copyInputs` returned a count nobody read. Now it returns nothing.
- **Left as they are:**
  - "copy" in `copyInputs` and the KDoc, though the glossary avoids 복사하다 for the freeze as a whole: ticket 239 itself calls this sub-step "the copy".
  - `@Sql` and `@TestPropertySource` on a base-class subclass: the probe creates its tables in `@BeforeEach` instead, but `@Sql` runs the one test-only file the spec asks for.
  - No `RankingPeriod` type for the week's start and end. Only one caller computes it now. Tickets 240 and 241 (boundary, validator) are where a second caller would earn it.
  - `canonicalLine` public: it is how the canonical text is pinned without a database.
