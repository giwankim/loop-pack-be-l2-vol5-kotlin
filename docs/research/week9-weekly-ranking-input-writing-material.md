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

## Issue 243: a failed freeze leaves nothing, and a freeze blocks no purchase activity

Implemented as `e4546f14`. `scripts/final-check --app commerce-batch` on `e4546f14`, a clean tree, started 2026-11-17T08:39:24Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 15 tests (16 s), 0 failures, 0 errors, 0 skipped**. Issue 239 ended at 5 classes and 12 tests, so this ticket adds 3 tests to `WeeklyRankingFreezeTest`. No production code changed. Only this document changed after `e4546f14`.

### Acceptance criteria

- [x] **A freeze that fails after the copy ends `FAILED`, and neither ranking table has a row for that `snapshotId`.** Scene `a freeze that fails after writing its inputs …`. The spy throws at `digestInputs`. That call is after `copyInputs` and before `insertFrozenSnapshot`, inside the step's transaction.
- [x] **Running again with the same parameters is the same `JobInstance` and ends `COMPLETED` with the full snapshot.** Scene `rerunning a failed freeze …`. The first run is asserted `FAILED`, then the stub is cleared. The second run has the same job instance ID and a new execution ID, 4 inputs, and count 4 with the assignment's checksum.
- [x] **During a freeze, another connection with a lock wait of one second inserts an activity confirmed inside the period being frozen, and the insert succeeds.** Scene `a purchase activity recorded from another connection …`. At the same seam, order 45 (product 7 × 4, `2026-08-20T12:00:00Z`) is inserted and committed on a new `DriverManager` connection with `SET SESSION innodb_lock_wait_timeout = 1`. The scene asserts the insert threw nothing and the row is in `purchase_activity`.
- [x] **That activity is not among the snapshot inputs and is not in the snapshot's count or checksum.** Same scene. Order 45 is absent from the inputs, and the snapshot keeps count 4 and the assignment's checksum. The inputs assertion carries the proof. The count and checksum follow from it, because `digestInputs` reads only `weekly_ranking_snapshot_input`.
- [x] **The late-insert scene fails when the freeze transaction's isolation is changed to REPEATABLE READ (checked once by hand).** I changed `ISOLATION_READ_COMMITTED` to `ISOLATION_REPEATABLE_READ` in `WeeklyRankingJobConfig`, then restored it. Only the late-insert scene failed, on `MySQLTransactionRollbackException: Lock wait timeout exceeded` after the one-second wait. The other six scenes passed. This matches spec 237's 확인한 사실 1: with the `confirmed_at` index, REPEATABLE READ takes S next-key locks on the period's range. I checked it before and after the review refactor.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

A second by-hand check, not asked for: with the step on a `ResourcelessTransactionManager`, both failure scenes failed. The failed run left its four inputs behind, and the rerun then ended `FAILED`. So the two scenes see the rollback, and they do not pass by construction.

### What was built

- **The seam:** `@MockkSpyBean WeeklyRankingSnapshotStore`, acting at `digestInputs(snapshotId)`. Issue 239 named this seam. The store has no `@Transactional`, so there is no CGLIB proxy, and the stub goes on the field directly. `CouponIssueSpies` needs `AopTestUtils` only because its bean has a proxy.
- **Two intent helpers** following `CouponIssueSpies`:
  - `failAfterTheInputsAreWritten(snapshotId)`
  - `afterTheInputsAreWritten(snapshotId) { … }`, which runs the action, then `callOriginal()`
- **`insertActivityFromAnotherConnection`**: a connection of its own from `DriverManager`, with the pool's URL and credentials. A pool connection keeps `innodb_lock_wait_timeout=3` from `jpa.yml` as a session variable, and a changed value would outlive the scene on the next borrower. The insert runs synchronously on the tasklet's thread. If the freeze held a lock on the range, the one-second wait would end it with 1205, not a hang.

### Decisions made while implementing

- **No thread for the late insert.** The freeze's transaction is open while the stub runs, so the insert meets any lock the copy took. The lock wait timeout ends the wait. A second thread would add a latch and show nothing more.
- **The insert's outcome is captured, not thrown.** `runCatching` keeps a 1205 out of the tasklet, so the scene fails on the assertion `the late insert threw nothing` and not on a `FAILED` job whose cause sits in the log.
- **`clearMocks` mid-test in the rerun scene.** springmockk resets the spy only after each test, and the rerun needs the real `digestInputs`.

### Review

A two-axis review ran on the code before `e4546f14`.

- **Spec:** nothing missing and no scope creep. Fixed: the rerun scene now asserts the first run's `FAILED` as its precondition. Noted above: the count and checksum assertions in the late-insert scene follow from the inputs one.
- **Standards:** no hard violation. Fixed before the commit:
  - "the copy" in the new test name, stub message and KDoc is now "writing the inputs" (`CONTEXT.md` 고정하다 _Avoid_: 복사하다).
  - The repeated `every { … digestInputs … }` stubs became the two intent helpers.
  - The nullable `Result` with `!!` became a list read with `single()`.
  - Fixed an over-indented `use` block, and moved `javax.sql.DataSource` after the `java.*` imports.
- **Left as they are:** `insertActivityFromAnotherConnection` repeats `insertActivity`'s parameters, but the two share the SQL constant `INSERT_ACTIVITY_SQL`. `DriverManager` and a mid-test `clearMocks` are new to the repo, and both are explained where they are used.

## Issue 242: a conflicting freeze fails and overwrites nothing

Implemented as `842e431b`, with review fixes in `dbcbb0b5`, rebased onto issue 243's `87d7acf3`. `scripts/final-check --app commerce-batch` on `20fe0be2` (the code of `dbcbb0b5`), a clean tree, started 2026-11-17T08:49:44Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 20 tests (38 s), 0 failures, 0 errors, 0 skipped**. Issue 243 ended at 15 tests, so this ticket adds 5 scenes to `WeeklyRankingFreezeTest`. Only this document changed after `dbcbb0b5`.

### Acceptance criteria

- [x] **With a snapshot frozen, a run with the same `snapshotId` and another `periodStart` ends `FAILED`.** `a freeze under an existing snapshot ID for another period …`. A purchase in the refused run's week (2026-08-24) is present, so a wrongly copied input would show under the existing ID.
- [x] **With a snapshot frozen, a run with the same `periodStart` and `revision` and another `snapshotId` ends `FAILED`.** `a freeze of an already frozen period and revision under a new snapshot ID …`.
- [x] **After each, the existing snapshot row and its inputs are unchanged and no row carries the refused run's values.** Each scene compares the whole snapshot table and the existing inputs with what they were before the refused run, and the second also finds no inputs under the refused ID.
- [x] **The failure names the conflict in the step's exit description.** Batch writes the exception's stack trace there, and its first line is `SnapshotConflictException: Freeze of snapshot <id> (period …, revision …) conflicts with snapshot <id> (period …, revision …)`. The class name and the message both set it apart from a key violation or a lock timeout.
- [x] **Inserting a second snapshot row by SQL with the same `(period_start, revision)` is refused by the database.** `DuplicateKeyException` naming `UK_WEEKLY_RANKING_SNAPSHOT_ON_PERIOD_START_AND_REVISION`. The key itself was already in the schema script from issue 239.
- [x] **A new revision of the same period under a new `snapshotId` freezes normally.** Revision 2 completes beside revision 1, with the same checksum, since the input did not move.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

### What was built

- **`FreezeTasklet`**: before reading the clock or copying, it asks the store for conflicting snapshots and throws `SnapshotConflictException` when there are any. Nothing has been written at that point, so the rollback has nothing to undo.
- **`WeeklyRankingSnapshotStore.findConflicting`**: one unlocked read, `WHERE snapshot_id = ? OR (period_start = ? AND revision = ?)`. It returns at most two rows, one per key.
- **`SnapshotKey`**: the snapshot ID, period start and revision as one value. It is both the refused freeze and each conflicting snapshot, and spells one in the message.
- **`SnapshotConflictException`**: one sentence per conflicting snapshot, joined by `; `.

### Decisions made while implementing

- **The red came from the exit description, not the status.** Before the check, both conflicts already ended `FAILED`: the copy ran, then the snapshot insert hit the primary or unique key and the step's transaction rolled back. So the keys were already a working backstop, and what the ticket adds is a refusal before any write that tells the operator which snapshot is in the way.
- **The check takes no lock.** Two freezes that race past it are still refused by the keys when the later one inserts its snapshot, and its copied inputs roll back with it. A locking read would add a gap lock on the unique key for a race the keys already settle.
- **An exact match (same ID, period and revision) is also reported as a conflict.** Batch refuses that run first, since its job instance is complete. The check does not make an exception for it.

### Review

A two-axis review ran on `842e431b` (then `9fb8fd46`, before the rebase).

- **Spec:** no missing AC and no scope creep. It confirmed the exit-description assertion against Batch 6.0.6's `AbstractStep` (the stack trace's first line holds the message, within the 2500 characters the metadata keeps).
  - Fixed: story 6 says "another period **or revision**", and only the period case had a scene. `a freeze under an existing snapshot ID for another revision of the same period …` was added.
  - Left as it is: no scene for the primary key refusing a second `snapshot_id` row. It is an ordinary primary key, and the AC names only the unique key.
- **Standards:** no hard violation.
  - Fixed: the ID, period and revision travelled as three parameters beside a type holding the same three. `findConflicting` and the exception take `SnapshotKey`, which moved out of the exception's file into its own.
  - Fixed: the tasklet KDoc said "before anything is copied", a word the glossary avoids for the freeze. It says "written".
  - Left as it is: the schema-key scene writes raw SQL inside a job test class. It is the backstop the job relies on, and it reads the same table.

## Issue 240: period membership, the UTC week boundary, soft-deleted activities and the confirmed_at index

Implemented as `f3245ab7`, rebased onto issues 243 and 242 (`d4c60157`). `scripts/final-check --app commerce-batch --app commerce-streamer` on `b5cbe254` (the code of `f3245ab7`), a clean tree, started 2026-11-17T08:52:51Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 22 tests (19 s), 0 failures, 0 errors, 0 skipped**; **commerce-streamer 18 classes, 68 tests (50 s), 0 failures, 0 errors, 0 skipped**. Issue 242 ended commerce-batch at 5 classes and 20 tests, so this ticket adds 2 tests there and 1 in commerce-streamer. Only this document changed after `f3245ab7`.

### Acceptance criteria

- [x] **An activity confirmed at `2026-08-17T00:00:00Z` is in the snapshot for `periodStart=2026-08-17`.** `WeeklyRankingFreezeTest`, boundary scene. It is the one snapshot input, with count 1 and the checksum `e02703d4…cd754e`, computed apart from the code with `printf '61\t7\t2\t2026-08-17T00:00:00.000000Z\n' | shasum -a 256`.
- [x] **One microsecond before that, and one at `2026-08-24T00:00:00Z`, are not.** Same scene, orders 60 and 62.
- [x] **The scenes fail if a timestamp is bound or read through the JVM's default zone.** The scene first asserts the default zone is Asia/Seoul. The build's test task sets `user.timezone`, but a run without it would otherwise pass without testing anything. It is a hard assertion, not an assumption, because an assumption would skip the scene. Both directions were shown red by changing the main code for one run and then restoring it:
  - binding the period's `Instant`s straight to JDBC: the inputs became order 62 instead of 61, the nine-hour shift
  - reading `confirmed_at` through `getTimestamp(…).toInstant()`: the checksum became `b75aefc5…`
- [x] **A soft-deleted activity inside the period is not in the snapshot, its count or its checksum.** Soft-delete scene: the assignment's four inputs plus order 45, inserted and then soft-deleted by an `UPDATE`, both inside the week. The inputs, count 4 and the assignment checksum are unchanged. Removing `deleted_at IS NULL` for one run made order 45 appear.
- [x] **The streamer's schema has the named index on `confirmed_at`, read by a test beside the existing named-key checks.** `PurchaseActivity` gains `IDX_PURCHASE_ACTIVITY_ON_CONFIRMED_AT`. `PurchaseActivityRepositoryTest` lists every non-unique index in `information_schema.statistics` and expects exactly that one on `confirmed_at`. It failed before the entity change.
- [x] **The batch's test-only `purchase_activity` table carries the same index.** Already there from issue 239 (`sql/purchase-activity.sql`); unchanged.
- [x] **`ktlintCheck` and `check` on commerce-batch and commerce-streamer pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **No main code changed in commerce-batch.** Ticket 239 already wrote the half-open range, the UTC conversion and `deleted_at IS NULL`. This ticket pins them with scenes, and each scene was shown to fail against a broken copy of the code.
- **The streamer's change is the index line and its import only.** A KDoc sentence about the contract was added and then removed at review, since the spec allows one line in another context.
- **The soft-deleted activity is inserted, then soft-deleted by an `UPDATE`.** The first version gave `insertActivity` a nullable `deletedAt`, bound through `params(List)` because `JdbcClient.params(vararg Any)` does not take a nullable from Kotlin. Issue 243 landed first and shared the four-column insert between `insertActivity` and its other-connection insert. The rebase kept that insert as it is and added `softDeleteActivity`, which is also how a soft delete happens in the streamer.
- **Still no `RankingPeriod` type.** The tasklet is still the only code that computes the week. Issue 241's validator only checks for a Monday, so it may not earn one either.

### Review

A two-axis review ran on the change before it was first committed, before the rebase.

- **Spec:** nothing missing. It flagged the streamer KDoc sentence as a second change in another context; removed.
- **Standards:** no hard violations. Fixed: the boundary scene's name ("Monday midnight UTC" rather than "Monday 00 00 UTC"), and the repository test's class KDoc now mentions the index. A comment now marks the zone assertion as the scene's premise.
- **Left as they are:**
  - an `assumeThat` for the zone premise: it would skip, and the ticket forbids skipped tests
  - the claim that the `listOf` change was unneeded: it was needed to compile. The rebase later removed it anyway (above).
  - the index query repeating the unique-key query's shape: two uses
  - the unasserted quantities in the new scenes: commerce-batch has no fixtures, and the existing scenes write their rows the same way

## Issue 241: bad job parameters are refused before anything is frozen

Implemented as `25c38bd7`, on top of issue 240 (`bbbba3ac`). `scripts/final-check --app commerce-batch` on `25c38bd7`, a clean tree, started 2026-11-17T12:48:40Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 31 tests (18 s), 0 failures, 0 errors, 0 skipped**. Issue 240 ended commerce-batch at 5 classes and 22 tests, so this ticket adds 9. Only this document changed after `25c38bd7`.

The ticket was paused and spec 237 was grilled a second time before it was finished. Its first title and second criterion said a refusal leaves no `JobInstance`. That cannot hold on Batch 6.0.6 with a validator alone (below), so the spec and the ticket were reworded first.

### Acceptance criteria

- [x] **A Tuesday `periodStart`, a blank `snapshotId`, `revision` 0 and each missing parameter raise `InvalidJobParametersException`.** Six scenes in `WeeklyRankingFreezeTest`, one per refusal. Each asserts the message: `periodStart must be a Monday, but 2026-08-18 is a TUESDAY`, `snapshotId must not be blank`, `revision must be at least 1, but was 0`, and `… is missing` for each of the three.
- [x] **After each refusal there is no `JobExecution` and no row in the two ranking tables.** `assertNothingExecutedOrFrozen` in each refusal scene: `BATCH_JOB_EXECUTION`, `weekly_ranking_snapshot` and `weekly_ranking_snapshot_input` are empty. The assignment's four activities are inserted first, so an accepted launch would have frozen them.
- [x] **A `periodStart` given as a `String` raises `InvalidJobParametersException`, and its message shows the expected spelling.** The message is `periodStart must be a LocalDate, but was a String. Give it as periodStart=2026-08-17,java.time.LocalDate`. This scene was red first, against a validator that only named the two types.
- [x] **A completed run launched again with the same three values raises `JobInstanceAlreadyCompleteException`, and the snapshot and its inputs are unchanged.** One scene. It also reads one job execution, the first run's.
- [x] **A `periodStart` in the future is accepted.** The context's clock stands at 2026-08-24 and the scene freezes `periodStart=2026-08-31`: `COMPLETED`, count 0.
- [x] **`ktlintCheck` and `check` on commerce-batch pass, with no skipped tests.** The run above.
- [x] **The closing comment is appended to this document.**

### What was built

- **`WeeklyRankingJobParametersValidator`**: a `JobParametersValidator` set on the job by `JobBuilder.validator(…)`. It reads each of the three parameters by name, refuses a missing one or one of another type, then checks the Monday, the blank and the revision. It reads no clock.
- **Nine scenes** in `WeeklyRankingFreezeTest`, flat and separately named.

### Decisions made while implementing

- **Batch 6.0.6 creates the job instance before it validates, and that is accepted.** `TaskExecutorJobLauncher.createJobExecution` commits `jobRepository.createJobInstance(…)` in its own transaction and only then calls the job's validator. The comment above that call still says "before creating anything". The first run of the Tuesday scene raised the right exception and left job instance 1 with no execution. Relaunching the same parameters then fails with `IllegalStateException` ("Cannot find any job execution for job instance"), not with the validator's message.
  - It is [Spring Batch issue 5562](https://github.com/spring-projects/spring-batch/issues/5562), fixed by commit `4dcf68f7b` on 2026-10-02. The fix adds a method to `JobRepository`, so it is in 6.1.0-RC1 and not on the 6.0.x branch. It reaches this repository with Boot 4.2, which was at RC2 on 2026-11-17 while Initializr's default was 4.1.2.
  - An instance of this kind exists only for parameters that are refused every time, so it never stands in the way of a valid run.
  - Not chosen: wrapping `JobOperator.start` through a `BeanPostProcessor` so the validator runs first, deleting the instance after a refusal, and moving to Boot 4.2.0-RC2. The first two are code that exists only until the upgrade, and the third goes past Initializr.
  - Spec 237 records this as 확인한 사실 6. Story 2 now says "refused before anything is frozen … leaves no snapshot and no job execution behind".
- **The scenes do not pin the instance.** They assert what `weeklyRanking` owns: the exception, no job execution, no ranking row. They pass on 6.0.6 and will pass on 6.1. Pinning the leftover instance would make the Boot upgrade fail on behaviour this repository neither wants nor controls, and no later branch relies on it.
- **Types are checked strictly.** A parameter's type is part of what identifies a job instance, so a `String` date read leniently would be another instance for the same week, against story 4. The mistake an operator will make is leaving `,java.time.LocalDate` off the command line, so the refusal shows that spelling with the value they gave.
- **The validator is a plain class, not a bean.** It has no collaborators, and the job config creates it where it sets it.
- **Still no `RankingPeriod` type.** The validator asks one question of the date, `dayOfWeek`, and the tasklet remains the only code that computes the week.

### Review

No two-axis review ran on this ticket. The design questions were settled in the second grilling of spec 237 before the code was finished.

## Issue 244: ADR 0038, the weekly ranking design document and the final check

Recorded from [issue 244's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/244#issuecomment-6949821515).

Implemented as `c245c67c` (ADR 0038, the design document and the context map's third row), with spec 237's closing review on top: `a0da8f4e` (code) and `1f2d623f` (design notes and the final check). `scripts/final-check --app commerce-batch --app commerce-streamer` on `a0da8f4e`, a clean tree, started 2026-11-17T13:39:41Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 33 tests (18 s); commerce-streamer 18 classes, 68 tests (49 s); 0 failures, 0 errors, 0 skipped**. An earlier run on `bfc0e186`, before the review, passed with 31 batch tests. Only documents changed after `a0da8f4e`.

### Acceptance criteria

- [x] **ADR 0038 exists, states the four decisions and the options not taken, and names ADR 0013 and ADR 0016 where it differs.** [ADR 0038](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/adr/0038-weekly-ranking-tables-are-not-entities.md): no entities and no soft delete (differs from 0016), a startup script, JDBC through `JdbcClient` with UTC `LocalDateTime`, no ports (differs from 0013). Not chosen: entities with a `ddl-auto` override, Flyway (adopt at the first `ALTER` on kept data), a dependency on the streamer's entity.
- [x] **The design document exists and carries the limits.** [`docs/design/weekly-ranking.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/design/weekly-ranking.md) §8: one statement in one transaction, hand-applied tables outside `local` and `test`, "a finished freeze is not rerun" proven only in the scores branch, a completed `JobInstance` not continuable after steps are added, the orphan `JobInstance` of Batch 6.0.6, no throughput claim. It also has the four-branch table (§1.1), what each ticket built (§1.2), READ COMMITTED and the index with the lock table (§5.2), the time conversion (§5.3), the scenes and red checks (§9).
- [x] **Every name matches the code; deviations from the spec have a dated note.** Design §10 lists them (probe restart by a second `startJob`, the `digestInputs` seam, the orphan `JobInstance`, the schema scene's stand-in for a second context, the context map row, the length refusal). Both reviewers checked the names and counts against the code.
- [x] **`scripts/final-check` passes on commerce-batch and commerce-streamer with no zero-test task and no skipped test, and its output is in the design document.** The run above, design §9.3.
- [x] **The repo's doc checks in `.githooks` pass.** `check-doc-notes` and `check-doc-links` on every commit. The notes check reports three in-place edits made at review (a count and two words), as `--warn-changed` allows.
- [x] **The writing material holds every ticket's closing comment, a header and the PR body draft.** This comment, spec 237's and the draft PR body are appended to [the writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/research/week9-weekly-ranking-input-writing-material.md).

### Decisions made while implementing

- **`CONTEXT-MAP.md` gains the third table row.** Issue 238's commit added the third context's paragraph but not its row in the table.
- **The run command in design §4 types `periodStart` and `revision`.** A parameter without a type is a `String`, which the validator refuses.
- **READ COMMITTED and the index stay out of the ADR**, as the spec planned. The ADR is about where the tables live and how they are reached; the isolation level is the freeze's design.

## Spec 237: the closing review

Recorded 2026-11-17 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237#issuecomment-6949822058) of [spec 237](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237). The text is the comment's, with its blob URLs.

Step 1 of week 9 is built, and its closing review is done. `scripts/final-check --app commerce-batch --app commerce-streamer` on `a0da8f4e`, a clean tree, started 2026-11-17T13:39:41Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-batch 5 classes, 33 tests (18 s); commerce-streamer 18 classes, 68 tests (49 s); 0 failures, 0 errors, 0 skipped**. The template ended commerce-batch at 2 classes and 3 tests, and commerce-streamer at 18 classes and 67 tests, so this step adds 3 classes and 30 tests to the batch and 1 test to the streamer. Only documents changed after `a0da8f4e`.

Tickets 238–244 are closed, each with its own comment. [Design 1.2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/design/weekly-ranking.md#12-1단계가-만든-것) lists what each one built, and [ADR 0038](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/adr/0038-weekly-ranking-tables-are-not-entities.md) holds the decision on the tables.

### Where the implementation differs

| The spec said | What is there | How it was found |
| --- | --- | --- |
| Refuse bad parameters before a `JobInstance` exists (interview) | Refused before a `JobExecution` or a ranking row exists. Batch 6.0.6 leaves an instance with no execution (Spring Batch issue 5562, fixed in 6.1). The spec was edited after a second grilling (확인한 사실 6) | Issue 241 |
| A `data_locks` test (interview) | A late insert from another connection during the freeze succeeds. The Testcontainers user cannot read `data_locks` (확인한 사실 5) | Writing the spec |
| The probe restarts through `JobOperator.restart(JobExecution)` (확인한 사실 4) | A second `startJob` with the same parameters, the path the ranking job's rerun takes | Issue 238 |
| A blank `snapshotId` is refused (story 3) | A `snapshotId` over 100 characters is refused too. Before, it passed the validator and failed the copy on `VARCHAR(100)`, leaving a `FAILED` execution | This review |
| A second context start runs the schema script again (ticket 239) | The scene calls the startup's `ApplicationDataSourceScriptDatabaseInitializer.initializeDatabase()` again after a freeze | Issue 239 |
| The context map's third row | Missing from issue 238's commit; added by issue 244 | Issue 244 |

All of these are in [design 10](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/design/weekly-ranking.md#10-명세와-다른-것).

**Not proven here:** "a finished freeze is not rerun" ([design 8](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/design/weekly-ranking.md#8-한계)). This step's job has no step after `freeze` to fail; the scores branch shows it.

### The closing review

A two-axis review of `week9...feat/weekly-ranking-input` at `c245c67c` (16 commits, 27 files), with one reviewer on the standards and one on this spec. Standards found no hard violation and 5 judgement calls. Spec found no blocking gap, 2 partial requirements, 1 extra and 1 doubtful implementation. Each was checked against the code and decided.

#### What changed

`a0da8f4e`:

- **A `snapshotId` longer than its column is refused like a blank one.** The new scene was red first: the launch threw nothing and the run failed later in the copy.
- **Story 21's primary key has a scene.** Only the `(period_start, revision)` unique key had one. A second row under the same `snapshot_id` is now refused with `weekly_ranking_snapshot.PRIMARY`. It passed at once: the key was already there.
- **`insertFrozenSnapshot` takes the `SnapshotKey`** the tasklet already holds, as `findConflicting` does since issue 242's review.
- **The tasklet's `@Value` expressions use the validator's parameter-name constants.** The tests keep the literal names, which pin the command line.

`1f2d623f`: the design's assignment section numbers now say 과제, "원본" near 스냅샷 입력 became "읽는", and the length refusal, the primary-key scene and the schema scene's stand-in have dated notes.

#### Left as they were

- **The test's own UTC conversion** rather than `JdbcUtcTimes`: the test stays independent of the code it checks.
- **`@ConditionalOnProperty` on three classes:** the template's demo job does the same.
- **`emptyTables` also clears Batch metadata:** the refusal scenes rely on it to assert that no `JobExecution` exists.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/research/week9-weekly-ranking-input-writing-material.md#spec-237-the-closing-review).

## Draft PR body

A draft for the PR from `feat/weekly-ranking-input` into `week9`, in the shape of week 8 step 3's, written after the spec's closing review.

Title: `feat(commerce-batch): freeze one ranking period's purchase activity into a snapshot in the weeklyRanking job, and show restart on Batch 6`

````markdown
## Summary

Week 9 step 1. commerce-batch gets the `weeklyRanking` job and its first step, `freeze`. It copies one ranking period's purchase activity into snapshot inputs and records their count and checksum. A test-only probe shows that a failed chunk step restarts where it stopped on this repo's Spring Batch 6.0.6. Scores, publishing and the API are the next three branches.

- **Spec:** issue 237, with tickets 238–244, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/237#issuecomment-6949822058)
- **Decision:** [ADR 0038](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/adr/0038-weekly-ranking-tables-are-not-entities.md): the ranking's tables are not entities (no `BaseEntity`, a `CREATE TABLE IF NOT EXISTS` script, `JdbcClient`, no ports)
- **Design:** [`docs/design/weekly-ranking.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/design/weekly-ranking.md): 5.2 READ COMMITTED and the index, 5.3 the time conversion, 6 the probe, 8 the limits, 9 the scenes and red checks
- **Writing material:** [`docs/research/week9-weekly-ranking-input-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/docs/research/week9-weekly-ranking-input-writing-material.md), which holds every ticket's closing comment
- **Glossary:** a third context, 주간 랭킹, in [`apps/commerce-batch/CONTEXT.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/weekly-ranking-input/apps/commerce-batch/CONTEXT.md); it meets 구매 활동 only at the `purchase_activity` table (`CONTEXT-MAP.md`)

```text
--job.name=weeklyRanking periodStart=2026-08-17,java.time.LocalDate snapshotId=… revision=1,java.lang.Long
  WeeklyRankingJobParametersValidator      # missing/wrong type, not Monday, blank or > 100 chars, revision < 1:
                                           # InvalidJobParametersException, no JobExecution, no ranking row
  step freeze                              # tasklet, the app's transaction manager, READ COMMITTED, one TX
    findConflicting(key)                   # same snapshotId, or same (periodStart, revision): FAILED, nothing written
    INSERT … SELECT from purchase_activity # [Monday 00:00 UTC, +7 days), deleted_at IS NULL, one statement, no locks
    digestInputs                           # stream back by (order_id, product_id): count + SHA-256 of the canonical text
    INSERT snapshot FROZEN                 # inputs and snapshot commit together, or neither exists
```

The streamer changes by one line: a named index on `purchase_activity.confirmed_at`.

## Review Points

#### 1. Is one `INSERT … SELECT` in one transaction the right freeze?

Every input comes from one consistent read, and nothing depends on the highest ID seen. The cost is one transaction as long as the week's copy (design 8). READ COMMITTED keeps it from blocking the streamer, which waits 3 s for a lock and then stops reading events (design 5.2).

**Would you split the copy, and how would you keep one consistent read if you did?**

#### 2. Are tables without entities, made by a startup script, acceptable?

`ddl-auto: create` would empty an entity's table on every local start, so a restarted job would lose its first run's rows. Outside `local` and `test` the tables are applied by hand, like Batch's metadata. Flyway waits for the first `ALTER` on kept data (ADR 0038).

**Is "hand-applied, like the metadata" enough until then?**

#### 3. Is the orphan `JobInstance` of a refused launch acceptable?

Batch 6.0.6 creates the instance before it validates (Spring Batch issue 5562, fixed in 6.1, which comes with Boot 4.2). A refused launch leaves an instance with no execution; relaunching the same bad values gives `IllegalStateException`. It never blocks a valid run (design 8).

**Would you wrap `JobOperator.start` until the upgrade, or leave it?**

## Evidence

**Before:** commerce-batch is the template: a demo job on `ResourcelessTransactionManager`, no ranking table.

**After:** `WeeklyRankingFreezeTest` (25 scenes), `SnapshotInputDigestTest` (3) and `BatchRestartProbeTest` (2), on real MySQL with the JVM in Asia/Seoul:

- The assignment's four inputs freeze to 4 snapshot inputs, count 4, checksum `d07eff39…4720b`, computed apart from the code with `shasum`.
- Monday 00:00 UTC is in the period; one microsecond earlier and the next Monday are not.
- A freeze failing after its inputs are written leaves no row; the rerun is the same `JobInstance` and completes.
- An activity inserted from another connection mid-freeze, with a 1 s lock wait, succeeds and is not in the snapshot.
- The probe fails at product 9 with `purchaseProductProbe.read.count` = 2 stored, and the restart writes only 9.

| Red check (local, never committed) | Result |
| --- | --- |
| Step isolation REPEATABLE READ | The mid-freeze insert: `Lock wait timeout exceeded` after 1 s |
| Step on `ResourcelessTransactionManager` | The failed freeze leaves its 4 inputs; the rerun ends `FAILED` |
| Period `Instant`s bound straight to JDBC | The boundary scene takes order 62 instead of 61: nine hours off |
| Reader with `.saveState(false)` | No stored read count; the restart writes 7 and 8 again |

`scripts/final-check --app commerce-batch --app commerce-streamer` on `a0da8f4e`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-batch | 5 (2 before) | 33 (3 before) | 0 |
| commerce-streamer | 18 | 68 (67 before) | 0 |

Not shown here: that a finished freeze is not rerun (no step after it can fail yet; the scores branch shows it), and throughput.

## Merge Danger

**Door:** two-way

No entity and no migration. The ranking's two tables are created only in `local` and `test`; a revert leaves them unused. The streamer's new index is created by `ddl-auto` in `local` and `test` and must be applied by hand elsewhere, like the tables.

**Blast Radius:** commerce-batch, plus one index on the streamer's table

- **Outside `local` and `test`**, `sql/weekly-ranking-schema.sql` and the `confirmed_at` index must be applied before the first run.
- **A launch must type its parameters** (`,java.time.LocalDate`, `,java.lang.Long`); untyped ones are refused.
- **The demo job is unchanged** (issue 236 removes it).
- **Out of scope:** scores, publishing, the API, the late-purchase revision, Flyway, throughput.
````
