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
- **`issuable` is stored and `suspended` is answered.** The spec names both. `CouponCampaignAdminResponse` is the one place that translates them. *Changed in the spec 219 review (2026-11-11):* an exhausted campaign also read `issuable = true`, so the field became `suspended` and the translation went away.

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

## Issue 222: an admin suspends a coupon campaign's issuing

Implemented as `6cca7409`. `scripts/final-check --app commerce-api` on `6cca7409`, a clean tree, started 2026-11-11T08:00:06Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 104 classes, 1128 tests (5 min 27 s), 0 failures, 0 errors, 0 skipped**. Issue 221 ended at 104 classes and 1113 tests, so this ticket adds no class and 15 tests. Only this document changed after `6cca7409`.

### Acceptance criteria

- [x] **Suspending answers 200 with the campaign marked suspended; suspending again answers 200 and changes nothing.** `CouponCampaignAdminApiTest` for the answer and the identical second body, `CouponCampaignRegisterTest` for the committed `issuable` and issued count after a second suspension, `CouponCampaignTest` for `suspend()` itself.
- [x] **Suspending an unknown campaign answers 404 `COUPON_CAMPAIGN_NOT_FOUND`.** Both the HTTP and the port test.
- [x] **After suspension, a new user's issue answers 409 `COUPON_CAMPAIGN_SUSPENDED`; no coupon row is added and the issued count is unchanged.** `CouponApiTest` for the 409, `CouponIssueIntegrationTest` for the committed rows.
- [x] **After suspension, a holder's repeat request answers 200 with the same coupon ID.** Both classes. `CouponApiTest` compares the whole body with the first answer.
- [x] **A campaign that is both suspended and exhausted answers `COUPON_CAMPAIGN_SUSPENDED` (domain test).** `CouponCampaignTest`.
- [x] **Coupons issued before the suspension are unchanged.** `CouponIssueIntegrationTest` reads the earlier coupon back in a new transaction with its user and issue time.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **`suspend` is a method on `CouponCampaignRegister`, not a port of its own.** That port is already "the admin's writes to a coupon campaign", as `BrandRegister` holds register, update and delete.
- **Suspension reads through `CouponCampaignFinder.findForUpdate`, and a test pins the lock.** `CouponCampaignRegisterTest` captures the SQL and finds one `FOR UPDATE`, on `coupon_campaign`. Without the lock, a suspension saved from a stale read could write back an old issued count over an issue that committed in between.
- **The suspended check is inline in `issue()`, beside the exhausted one,** not an `ensureIssuable()` guard. The two checks are the method's whole rule and their order is the point, so they read best together.
- **`suspendCouponCampaign` joins the `prepare*` helpers of `BaseApplicationServiceTest`,** next to `deleteBrand`, and goes through the port.
- **A user's suspension answers 403 and changes nothing.** The spec doesn't list it. It mirrors registration's 403 test and pins that the new path is under the admin security.

### Review

A two-axis review ran on the code before the final check.

- **Spec:** no missing requirement, no scope creep beyond the 403 test above, nothing implemented wrongly.
- **Standards:** no hard violation. Two KDoc drifts were fixed in `6cca7409`: `CouponIssueService` still said a holder is answered only from an exhausted campaign, and the suspension's KDoc cited ADR 0018 rule 1 as a whole, though the code saves explicitly (catalog design 5.37) rather than by dirty checking as that rule also says.
- **Left as they are:** the inline guard (above), a fourth exception handler in `ApiControllerAdvice` shaped like the three before it, the suspended scene in `CouponApiTest` that mirrors the exhausted one, and the stored `issuable` against the answered `suspended`, which issue 220 already recorded.

## Issue 223: a customer lists their own coupons

Implemented as `a0f06b8d`, on top of issue 222. `scripts/final-check --app commerce-api` on a clean tree whose code is `a0f06b8d`'s (the commit of this document before this paragraph), started 2026-11-11T08:19:10Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 105 classes, 1139 tests (5 min 2 s), 0 failures, 0 errors, 0 skipped**. Issue 222 ended at 104 classes and 1128 tests, so this ticket adds 1 class (`CouponFinderTest`, 3 tests) and 8 tests in `CouponApiTest`. Only this document changed after `a0f06b8d`.

The work started before issue 222 landed and passed the same check then, at 105 classes and 1123 tests on top of issue 221. It was rebased onto issue 222. The only code conflict was the two new helpers side by side in `BaseApplicationServiceTest`. After the rebase, the list gained the suspended-campaign test below.

### Acceptance criteria

- [x] **A user with coupons from several campaigns gets them newest first; coupons with the same issue time come in descending ID order.** `CouponApiTest` sets two coupons to the same `issued_at` in SQL and dates the last-issued coupon earliest, so an order by ID alone fails. Swapping the query for `findAllByUserIdOrderByIdDesc` was tried and the test failed.
- [x] **Paging through the list returns every coupon once, with the slice's has-next flag right on the last page.** `CouponApiTest` pages three coupons by two: the IDs joined across pages equal the issued IDs newest first, and `hasNext` reads `true, false, false`.
- [x] **Another user's coupons never appear, and a user with none gets an empty slice.** `CouponApiTest`. A `userId` in the query changes nothing.
- [x] **A request without a requester answers 401.** `CouponApiTest`.
- [x] **The list still returns a coupon after its campaign is exhausted.** `CouponApiTest`, with a quota of 1 and a second user's 409 as the proof that the campaign is exhausted. A second test does the same after `suspendCouponCampaign` (story 6's "an ended event does not take back what I hold").
- [x] **The index is declared on the entity, and the list query's plan uses it without a sort step.** Issue 221 already declared `IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID (user_id, issued_at DESC, id DESC)`. `CouponFinderTest` explains the statement the port sent, bound to the same values, and asserts the index name and no `Sort`. Narrowing the index to `user_id` alone was tried and the test failed. The two plans, page 1 of size 2 over three coupons:

  ```
  -- the declared index
  -> Limit/Offset: 3/2 row(s)  (cost=0.56 rows=0)
      -> Filter: (c1_0.deleted_at is null)  (cost=0.56 rows=0.6)
          -> Index lookup on c1_0 using IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID (user_id=1)  (cost=0.56 rows=3)

  -- the index narrowed to user_id
  -> Limit/Offset: 3/2 row(s)  (cost=0.56 rows=1)
      -> Sort: c1_0.issued_at DESC, c1_0.id DESC, limit input to 5 row(s) per chunk  (cost=0.56 rows=3)
          -> Filter: (c1_0.deleted_at is null)  (cost=0.56 rows=3)
              -> Index lookup on c1_0 using IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID (user_id=1)  (cost=0.56 rows=3)
  ```

  The narrowed index kept its name in that run, so the index name alone tells the two apart only together with the missing `Sort`.
- [x] **The query does not join the campaign table (seen through the SQL capture).** `CouponFinderTest`: the list is exactly one statement, `... from coupon c1_0 where (c1_0.deleted_at is null) and c1_0.user_id=? order by c1_0.issued_at desc,c1_0.id desc limit ?,?`, with no `coupon_campaign`, no join and no count query.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **The list is a derived Spring Data query, `findAllByUserIdOrderByIssuedAtDescIdDesc(userId, pageable): Slice<Coupon>`.** The filter is always the requester, so nothing needs QueryDSL. The order and the filter are fixed, which is the case `BrandRepository`'s derived `Slice` query covers. A `Slice` reads `size + 1` rows and counts nothing.
- **`CouponFinder.findAll` returns `Slice<Coupon>`, the aggregate,** and the web adapter maps it to `CouponResponse` (ADR 0014). The response already existed for issuing, so the list item and the issue answer are the same shape.
- **`CouponListRequest` copies `LikeListRequest`'s bounds and messages** (page 0 or more, size 1 to 100, defaults 0 and 20). The ticket says the paging parameters match the like and order lists, and catalog design 5.17 keeps a Request per list.
- **`prepareCoupon` is on `BaseApplicationServiceTest` and goes through `CouponIssuer`.** The issuer refuses a surrounding transaction, so only bases that leave the test transaction can call it, and its KDoc says so. Issue 224's concurrency test can use it, and `CouponFinder` for its "another user's list does not show it" check.
- **`explainTree` moved to `com.loopers.support`.** `ProductQueryPerformanceApiTest` had it privately, and the coupon plan test is its second user.
- **The OpenAPI text says an exhausted or suspended campaign's coupons are listed, and a test pins each.** The ticket names only exhaustion. The list reads no campaign, so suspension cannot hide a coupon either, and the test makes that a promise rather than an accident.

### Review

A two-axis review ran on the code before the final check.

- **Spec:** no missing requirement and nothing implemented wrongly. It asked who owns the parent spec's "another user's list does not show it" for a same-user concurrent issue. Issue 224 lists it. The page and size 400 tests were noted as beyond the ticket's words but within "match the like and order lists".
- **Standards:** six findings, all fixed in `a0f06b8d`. Tests called `CouponIssuer` to prepare data instead of a `prepareCoupon` (`CODING_STANDARDS.md` fixture rule). One test read the `couponCampaign` field after preparing three campaigns. One read a coupon ID through `JsonNode` instead of `extractingPath`. The paging test asserted single-response fields through `JsonNode`. A three-body test named its bodies off the naming scheme, so it was split in two. `explainTree` was duplicated.

## Issue 224: the quota and the one-coupon rule hold under contention, and the design is written down

Implemented as `abe63641` (the scenes) and `8e251c66` (`docs/design/coupons.md` and the ADR 0018 note). `scripts/final-check --app commerce-api` on `8e251c66`, a clean tree, started 2026-11-11T08:45:33Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 105 classes, 1143 tests (5 min 16 s), 0 failures, 0 errors, 0 skipped**. Issue 223 ended at 105 classes and 1139 tests, so this ticket adds no class and 4 tests. Only documents changed after `8e251c66`.

The run before it on the same commit had one failure out of 1143, outside the coupon slice: `PointApiTest`'s first test failed in `@BeforeEach` while emptying Redis, with `RedisConnectionFailureException` (Lettuce's `Cannot determine topology` within its 100 ms connect timeout). The class's other 11 tests passed. Nothing was changed, and the next run passed. Coupons don't use Redis. The design doc's 8.3 records both runs.

No production code changed. No scene showed the issuing transaction to be wrong.

### Acceptance criteria

- [x] **8 different users issue from a campaign with a total quota of 5 on 8 threads: 5 issued, 3 `COUPON_CAMPAIGN_EXHAUSTED`, 5 coupon rows, issued count 5.** `CouponIssueIntegrationTest`, through `runConcurrently` and `tally`. The expected rejections are `users − quota`, and the rows and the count are compared with the successes as well as with the quota. The recorded run's line: `Quota scene: 8 users, total quota 5: issued 5, exhausted 3, technical errors 0; coupon rows 5, issued count 5`.
- [x] **One user issues on two threads at once: both get the same coupon ID, one coupon row, issued count 1, and another user's list does not show it.** Same class, with the list read through `CouponFinder`. `Same-user scene: 2 threads: coupon IDs [1, 1], newly issued [false, true]; coupon rows 1, issued count 1`.
- [x] **With the coupon repository's `save` made to throw (springmockk spy), the issue fails, the issued count is unchanged and no coupon row exists; issuing again without the spy gives one coupon and issued count 1.** The stub flushes before it throws, so the captured SQL holds one `update coupon_campaign` and no coupon insert: MySQL had the raised count and only the rollback took it back.
- [x] **An issue that waits on a held campaign lock past the timeout answers 409 `CONCURRENT_REQUEST` and issues nothing.** At the port: another user's issue pauses inside its issuing transaction (a spy on `CouponIssueSteps`), and the waiting issue throws `PessimisticLockingFailureException` after 3 s, leaving no coupon of its own. `ApiControllerAdvice` maps that exception to 409 `CONCURRENT_REQUEST` for every writer, as `LockFailureApiTest` pins. No HTTP scene was added for issuing (below).
- [x] **Red check: reading the campaign without `FOR UPDATE` turns the 8-users scene red.** Run twice, never committed: 5 issued and 3 technical errors, then 4 and 4, all MySQL deadlocks (1213), not exhaustions.
- [x] **Red check: dropping the coupon re-check inside the transaction turns the two-threads scene red.** One success and one `Duplicate entry '1-1' for key 'coupon.UK_COUPON_ON_CAMPAIGN_ID_AND_USER_ID'`. Every other scene stayed green.
- [x] **`docs/design/coupons.md` exists and `scripts/check-doc-notes` accepts the ADR 0018 note.** The note sits under rule 3, after the ADR 0020 note.
- [x] **`scripts/final-check` passes, and its commit, timings and test counts are recorded in the design doc.** 8.3.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **Without `FOR UPDATE` the quota did not overrun: it deadlocked.** Each unlocked transaction inserts its coupon first (an `IDENTITY` insert goes out at `save`), and the foreign key `coupon → coupon_campaign` share-locks the campaign row. Then the count's UPDATE at the commit's flush needs the exclusive lock and waits on the others' share locks. The design doc calls it an accident of the foreign key, not a guard: an issue that commits before another inserts still loses an update.
- **The lock scene's holder is a real issue, paused through a spy on the issuing step,** as `CODING_STANDARDS.md` asks when a Service step holds the lock. A side effect showed up in the second red run: the scene stays green without `FOR UPDATE`, because the paused holder already holds the foreign key's share lock and the unlocked waiter blocks on its own commit-time UPDATE. That scene proves the 409 path, not the lock. The two scenes the red checks name do prove the lock.
- **The 409 is proven at the port, not over HTTP.** The ticket asks for port scenes so they stay valid when the next branch puts a processing slot in front of the API. The advice's mapping is one handler for one exception type, already pinned by `LockFailureApiTest`.
- **The scenes log one INFO line each after their assertions,** so this document copies a recorded run's lines (`scripts/scene-lines`).

### Review

A two-axis review ran on the code before the final check.

- **Spec:** no missing requirement or scope creep. It found the rollback scene's text wrong: `save` of the managed campaign sends no SQL, so nothing had reached MySQL when the stub threw. It also found the design doc's connection-limit sentence backwards (`dd888459` came before this ticket). Both were fixed. The 409 being proven at the port was raised and kept (above).
- **Standards:** the rollback stub didn't flush as the mid-failure rule asks, the quota scene compared counts only with literals, the quota KDoc presented the deadlock as the mechanism, the lock holder bypassed the Service step, and the log lines re-read the committed state. All were fixed before `abe63641`.
- **Left as they are:** `customer` as a local name, which `CONTEXT.md` lists under Avoid but the repo already uses 18 times, and `outcomes.total` missing from the same-user scene, which the `Outcomes` equality already implies.

## Spec 219: the closing review

Recorded 2026-11-12 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219#issuecomment-6812046213) of [spec 219](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219). The text is the comment's, with its blob URLs and its headings one level down.

Step 1 of week 8 is built, and its closing review is done. `scripts/final-check --app commerce-api` on `6bbe944e`, a clean tree, started 2026-11-12T01:27:40Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 105 classes, 1143 tests (4 min 58 s), 0 failures, 0 errors, 0 skipped**. Week 7 ended at 95 classes and 1062 tests, so this step adds 10 classes and 81 tests. Only documents changed after `6bbe944e`.

Tickets 220–224 are closed, each with its own comment. [Design 1.2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/design/coupons.md#12-1단계가-만든-것) lists what each one built.

### Where the implementation differs

| The spec said | What is there | How it was found |
| --- | --- | --- |
| `CouponCampaign` stores 발급 가능 여부 (`issuable`) | It stores `suspended`, `CONTEXT.md`'s 발급 중지. An exhausted campaign also read `issuable = true`, and the admin response had to negate it | This review (design 2) |
| A name is not blank | It is also at most 100 characters, like `Brand` and `Product`. Without the cap a long name fails at the column as a 500 | Issue 220 |
| A unique key on `(campaign_id, user_id)` | Also foreign keys from `coupon` to `coupon_campaign` and `users`, as ADR 0015 asks of every scalar reference | Issue 221 |
| The campaign lock joins no transaction that locks another row | It joins no other *exclusive* lock. The coupon insert's foreign key check share-locks the requester's `users` row. Nothing locks `users` exclusively, so the lock order still needs no new position | This review ([design 3.3](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/design/coupons.md#33-잠금), [ADR 0018](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/adr/0018-pessimistic-row-locks-in-one-global-order.md) rule 3's note) |

**Additions the spec didn't ask for:**

- A user's suspension answers 403. The list answers 400 for a bad page or size, and it still lists a coupon whose campaign is exhausted or suspended. Each has a test.
- The test MySQL's `max_connections` is 300 (`dd888459`). The suite peaked at 147 of the default 151 connections, and issue 224's spy added a test context.
- `explainTree` moved to `com.loopers.support` for its second user, and `CODING_STANDARDS.md` now allows `@EnableTestPg` on a scene that needs a pinnable `TestClock`.

**Settled while building:**

- Without `FOR UPDATE` the quota didn't overrun in either red run: it deadlocked (MySQL 1213), because the coupon's foreign key share-locks the campaign row before the count's UPDATE. The design calls that an accident, not a guard ([design 8.2](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/design/coupons.md#82-붉은-확인)).
- The 409 `CONCURRENT_REQUEST` after the 3 s wait is proven at the port, so it stays valid when step 2 puts a processing slot in front of the API. `LockFailureApiTest` already pins the HTTP mapping.

### The closing review

A two-axis review of `week8...feat/coupon-issue` (14 commits, 54 files), with one reviewer on the standards and one on this spec. Neither found a hard violation or a missing requirement. Standards reported 2 soft breaches and 2 smells worth deciding, Spec reported 1 overstatement in the docs and 1 minor naming difference. Each was checked against the code and decided one by one.

#### What changed

`de9a3bdb` and `6bbe944e`:

- **`issuable` → `suspended`** on `CouponCampaign`, so `issue()` reads `if (suspended)` and `CouponCampaignAdminResponse` maps the field without a `!`. Design 2 records the departure from the spec, and issue 220's section of the writing material gets a dated note.
- **The admin response's `id` is now `campaignId`**, matching the customer's `CouponResponse`. This changes the admin API's JSON. Its two assertions and design 5 changed with it.
- **`@PathVariable("…")` is spelled out** in `CouponApi` and in three handlers of `OrderApi`, like every other controller.
- **Design 3.3 and ADR 0018's note** now say "다른 배타 잠금" and name the shared lock on `users`.

#### Left as they were

- **`campaignId` rather than `couponCampaignId` as the parameter name.** The property, the query method and the URL all use the short form already.
- **`MIN_TOTAL_QUOTA = 1L` compared with an `Int`.** It is a `Long` because `@Min` takes one.
- **The holder check written twice**, before the lock and under it. The spec asks for both.
- **The two new `ApiControllerAdvice` handlers** shaped like the existing ones, and the `longValueExact()` mapping in both responses.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/research/week8-coupon-issue-writing-material.md#spec-219-the-closing-review).

## Draft PR body

A draft for the PR from `feat/coupon-issue` into `week8`, in the shape of week 7's PRs, written after the spec's closing review. The PR's copy links these documents with blob URLs and carries the spec's closing comment.

Title: `feat(commerce-api): issue coupons from a coupon campaign up to its quota, one per customer`

````markdown
## Summary

Week 8 step 1. A coupon campaign issues coupons until its quota runs out, and a customer holds at most one per campaign, guarded by the database alone. Redis's processing slots (step 2) and its failure boundary (step 3) come later and sit in front of this.

- **Spec:** issue 219, with tickets 220–224, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/219#issuecomment-6812046213)
- **Decision:** a dated note on [ADR 0018](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/adr/0018-pessimistic-row-locks-in-one-global-order.md) rule 3. No new ADR
- **Design:** [`docs/design/coupons.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/design/coupons.md). 3 is issuing, 6 what wasn't chosen, 8 the scenes, red checks and final check
- **Writing material:** [`docs/research/week8-coupon-issue-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-issue/docs/research/week8-coupon-issue-writing-material.md), which holds every ticket's closing comment

```text
POST /api/v1/coupon-campaigns/{campaignId}/issues
  CouponIssueService.issue                # Propagation.NEVER
    early read of the requester's coupon  # found: 200, nothing locked
    CouponIssueSteps.issue                # own TX
      CouponCampaignFinder.findForUpdate  # first statement; 404, or 409 CONCURRENT_REQUEST after 3 s
      re-read the requester's coupon      # found under the lock: 200
      campaign.issue()                    # 409 SUSPENDED, then 409 EXHAUSTED; else issued count + 1
      save campaign, save coupon          # 201, committed together
GET  /api/v1/coupons                              # own coupons, issuedAt DESC, couponId DESC, Slice
POST /api-admin/v1/coupon-campaigns               # register
POST /api-admin/v1/coupon-campaigns/{campaignId}/suspend  # one way, repeatable, locks the row
```

The early read lets a customer who already holds a coupon skip the campaign row's queue, and step 2 will take a processing slot between the two steps.

## Review Points

#### 1. Is a row lock the right guard for the quota?

Every issue for one campaign queues on its row. A conditional `UPDATE … where issued_count < total_quota` would be cheaper, but it moves the quota rule out of `CouponCampaign` into SQL, and the order of suspended before exhausted with it, the same reason stock isn't done that way (ADR 0018). Step 2 bounds how many requests reach the queue at once.

**In practice, at what contention would you give up the rule's single home for the conditional UPDATE?**

#### 2. Is a re-check under the lock enough for duplicates?

The unique key on `(campaign_id, user_id)` stays, but no code catches its violation: under the lock, the second of a customer's two requests reads the coupon the first committed. The red check shows the key catching it when the re-check is removed, as a 500.

**Would you still write the catch-and-find recovery path as a second line of defence?**

#### 3. Is a deadlock-free red run a fair proof of `FOR UPDATE`?

Without `FOR UPDATE` the quota scene didn't overrun: the coupon's foreign key share-locks the campaign row, and the count's UPDATE deadlocked instead. The scene still went red, but through technical errors rather than a sixth coupon. The design calls the foreign key an accident, since a transaction that commits before another inserts still loses an update.

**Would you want a scene that shows the overrun itself, or is the deadlock evidence enough?**

## Evidence

**Before:** on `week8`, there are no coupons.

**After:** `CouponIssueIntegrationTest`'s scenes on real MySQL, from the final check's run (`scripts/scene-lines`):

```text
Quota scene: 8 users, total quota 5: issued 5, exhausted 3, technical errors 0; coupon rows 5, issued count 5
Same-user scene: 2 threads: coupon IDs [1, 1], newly issued [true, false]; coupon rows 1, issued count 1
```

The rollback scene fails the coupon insert after the count's UPDATE has reached MySQL, and leaves 0 coupons and issued count 0. The lock scene answers `PessimisticLockingFailureException` after 3 s and issues nothing.

| Red check (local, never committed) | Result |
| --- | --- |
| Campaign read without `FOR UPDATE` | Quota scene red twice: 5 issued with 3 deadlocks, then 4 with 4 |
| Re-check under the lock removed | Same-user scene red: `Duplicate entry '1-1' for key 'coupon.UK_COUPON_ON_CAMPAIGN_ID_AND_USER_ID'` |

`scripts/final-check --app commerce-api` on `6bbe944e`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-api | 105 (95 before) | 1143 (1062 before) | 0 |

## Merge Danger

**Door:** two-way

Two new tables, `coupon_campaign` and `coupon`, created from the entities like every other table. A revert leaves them behind, unused.

**Blast Radius:** coupons only

- **New endpoints only.** Orders, payments, the outbox and commerce-streamer are unchanged. `OrderApi` only spells out its `@PathVariable` names.
- **Test infrastructure.** The test MySQL's `max_connections` is 300, shared with commerce-streamer's tests.
- **Out of scope:** using a coupon, applying its discount to an order, admin reads of campaigns, and Redis.
````
