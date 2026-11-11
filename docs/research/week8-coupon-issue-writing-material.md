# Week 8 coupon issue: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one ticket of [spec 219](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219)'s closing comments.

## Issue 220: an admin registers a coupon campaign

Implemented as `93448ede`, on top of `27113cba`, which adds the coupon terms to `CONTEXT.md`. `scripts/final-check --app commerce-api` on `93448ede`, a clean tree, started 2026-11-11T06:49:42Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 99 classes, 1092 tests (5 min 3 s), 0 failures, 0 errors, 0 skipped**. Week 7 ended at 95 classes and 1062 tests, so this ticket adds 4 classes and 30 tests. Only this document changed after `93448ede`.

### Acceptance criteria

- [x] **The first commit on the branch adds the coupon terms to `CONTEXT.md`.** `27113cba`.
- [x] **Registering a campaign answers 201 with the campaign, and the row is readable in a new transaction with issued count 0 and issuing not suspended.** `CouponCampaignAdminApiTest` for the 201 and its body, `CouponCampaignRegisterTest` on `BaseCommittingApplicationServiceTest` for the committed row.
- [x] **A blank name, a discount amount below 1원 and a total quota below 1 each answer 400 and store nothing.** `CouponCampaignAdminApiTest`, with blank and whitespace names and with 0 and -1 for both numbers.
- [x] **The entity refuses the same three inputs when built directly.** `CouponCampaignTest`. A negative discount amount can't reach the entity, because `Money` itself refuses it.
- [x] **The campaign extends `BaseEntity`, and the slice's packages follow the existing layout so `HexagonalArchitectureTest` passes with no rule changed.**
- [x] **A registration port exists that tests of later tickets can use to prepare a campaign.** `CouponCampaignRegister`. `prepareCouponCampaign` is left to the first test that needs it, as `CODING_STANDARDS.md` asks.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **A name has at most 100 characters.** The spec names only the blank check. `Brand` and `Product` both cap at 100, and without a cap a long name would fail at the column as a 500 instead of a 400. So this is a fourth refusal the spec did not list.
- **`@SQLRestriction("deleted_at is null")` is on the entity though nothing deletes a campaign in this step.** ADR 0016 makes it the default for a root. ADR 0016 also asks for a repository test that a deleted row is a missing one. This ticket's repository has only `save`, so that test belongs with the first read port, in issue 221.
- **`issuable` is stored and `suspended` is answered.** The spec names both. `CouponCampaignAdminResponse` is the one place that translates them.

### Review

A two-axis review ran on the code before the final check.

- **Spec:** no missing requirement. It raised the 100-character cap, recorded above.
- **Standards:** three findings, all fixed in `93448ede`. The error tests named their first body `error` rather than `body`, the domain test wrote unchecked arguments as literals rather than reading them from a fixture (so `createCouponCampaign` and its contract tests were added), and the entity's KDoc used "event", which `CONTEXT.md` avoids for a campaign.
- **Left as they are:** two judgement calls on `countCampaigns()` appearing in two test classes and on `issuedCount` and `issuable` having no writer yet, since issues 221 and 222 add those. The reviewer's point that an exhausted campaign still reads `issuable = true` is noted: the name comes from the spec, and `CONTEXT.md` has no entry for it.

## Issue 221: a customer is issued a coupon from a coupon campaign

Implemented as `1c221626`. `scripts/final-check --app commerce-api` on `1c221626`, a clean tree, started 2026-11-11T07:25:43Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 104 classes, 1113 tests (4 min 52 s), 0 failures, 0 errors, 0 skipped**. Issue 220 ended at 99 classes and 1092 tests, so this ticket adds 5 classes and 21 tests. Only this document changed after `1c221626`.

The first final check on the same commit failed: 30 failures, all of `ProductRepositoryTest`, whose context could not start with `Too many connections`. The second run, with no change, passed while `Threads_connected` was sampled every 3 s. The sampled peak was 147 of MySQL's default 151. Every distinct test context keeps its own Hikari pool, so the suite sits at the limit. This ticket adds no context: `CouponIssueIntegrationTest` has the same configuration as `ProductCacheTest`. Issue 224's spy on the coupon repository will add one.

> 2026-11-11 `dd888459` raised the test MySQL's `max_connections` to 300 in `MySqlTestContainersConfig`, which commerce-streamer's tests share. The limit opens no connections. `scripts/final-check --app commerce-api` with the change, started 2026-11-11T07:40:15Z, passed with the same counts, 104 classes and 1113 tests (5 min 0 s). That run's tree still had the change uncommitted on top of `519a207c`. Sampled every 3 s, the limit read 300 and the peak was 149 connections. The MySQL container used 529 MiB with nothing connected and 824 MiB at that peak.

### Acceptance criteria

- [x] **The first issue answers 201 with the coupon; one coupon row exists and the campaign's issued count is 1.** `CouponApiTest` for the answer, `CouponIssueIntegrationTest` for the committed rows.
- [x] **The coupon holds the campaign's name and discount amount as they were at issue, and the issue time from the `Clock`.** `CouponIssueIntegrationTest` pins the clock at nanoseconds and reads back microseconds.
- [x] **A repeat request answers 200 with the same coupon ID; still one row, issued count still 1.** Both classes. `CouponApiTest` compares the whole body of the two answers.
- [x] **With a total quota of 1, a second user answers 409 `COUPON_CAMPAIGN_EXHAUSTED` and gets no coupon, and the first user's repeat request still answers 200 with their coupon.** Both classes.
- [x] **An unknown campaign answers 404 `COUPON_CAMPAIGN_NOT_FOUND`.** Both classes.
- [x] **A request without a requester answers 401, and no request field can name another user.** `CouponApiTest`. A `userId` in the query and the body changes nothing, and the other user's own first request still answers 201.
- [x] **A repeat request by a holder sends no `FOR UPDATE`.** `CouponIssueIntegrationTest` through `captureSql`, with the first issue as the control that shows the lock on `coupon_campaign`.
- [x] **The repository refuses a second coupon for the same campaign and user.** `CouponRepositoryTest`.
- [x] **Domain tests cover `issue()` up to the quota and its rejection at the quota.** `CouponCampaignTest`.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The passing run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **`CouponIssueService` is `Propagation.NEVER`, and a test pins it.** A surrounding transaction would be wrong as well as slow: the early read would fix the REPEATABLE READ snapshot before the lock is granted, and the re-check under the lock would miss a coupon the previous holder had just committed. The lock is the issuing transaction's first statement for the same reason.
- **`coupon` has foreign keys to `coupon_campaign` and `users`.** The spec names only the unique key. ADR 0015 has the database refuse a row whose scalar reference points at nothing. `coupon` sorts before both tables, so `scalar-foreign-keys-drop.sql` needs no line.
- **The list index `(user_id, issued_at DESC, id DESC)` is declared now, though issue 223 is its reader.** The spec's Model section declares it with the table.
- **`CouponCampaignFinder.findForUpdate` is a port of its own.** Issue 222's suspension reads the campaign the same way.
- **The coupon's fixture calls the real constructor,** because the constructor truncates `issuedAt` (`CODING_STANDARDS.md`'s fixture rule).
- **`CouponIssueIntegrationTest` carries `@EnableTestPg` though it calls no PG.** That annotation is the only way to a pinnable `TestClock`, and the acceptance criterion needs one. `CODING_STANDARDS.md`'s list of allowed subclass annotations now says so.

### Review

A two-axis review ran on the code before the final check.

- **Spec:** no missing requirement and nothing implemented wrongly. It noted that the `NEVER` sentence on `ApplicationService` named only PG services, which is fixed in `1c221626`.
- **Standards:** five findings, all fixed in `1c221626` or by the rule's wording. `save`'s result was discarded while the entity was still read, a `Long` was asserted with `toInt()` instead of `isEqualToLong`, a single-use request sat in a local, the fixture needed the real constructor and the contract's constructor check, and `@EnableTestPg` was outside the rule's list.
- **Left as they are:** the two-line duplicate between the early read and the re-check, which the spec requires in both places, building the coupon in the steps bean rather than on the campaign, since the ticket fixes what `campaign.issue()` does, the whole-body comparison in `CouponApiTest`, which is the plainest proof of "the same body", and the English word "holder", which the ticket itself uses.
