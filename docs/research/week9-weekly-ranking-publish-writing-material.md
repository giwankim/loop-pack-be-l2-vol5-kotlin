# Week 9 weekly ranking publish: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 252](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/252), then the spec's own.

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
