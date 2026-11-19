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
