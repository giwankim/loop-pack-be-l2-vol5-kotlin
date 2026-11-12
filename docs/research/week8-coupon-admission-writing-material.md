# Week 8 coupon admission: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one ticket of [spec 226](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/226)'s closing comments.

## Issue 227: processing slots are acquired and released in a Redis Set

Implemented as `551d5b05` and the review fixes `205bf543`, on top of `5a304888`, which adds the four slot terms to `CONTEXT.md`. `scripts/final-check --app commerce-api` on `205bf543`, a clean tree, started 2026-11-12T03:13:28Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 107 classes, 1153 tests (5 min 21 s), 0 failures, 0 errors, 0 skipped**. Step 1 ended at 105 classes and 1143 tests, so this ticket adds 2 classes and 10 tests. It starts no new Spring context. Only this document changed after `205bf543`.

### Acceptance criteria

- [x] **On real Redis, `acquire` returns a token `capacity` times and `null` the next time, and the Set holds exactly `capacity` members.** `CouponIssueSlotsTest` at a capacity of 3, and again through the context's own bean at `application.yml`'s 10.
- [x] **After one `release`, one more `acquire` returns a token.** `CouponIssueSlotsTest`, which also reads the Set: the new token and the two untouched ones.
- [x] **Tokens differ on every call.** `CouponIssueSlotsTest`. The race scene also asserts its three tokens have no duplicates.
- [x] **Releasing the same token twice, and releasing a token that was never acquired, leave the other tokens in the Set.** `CouponIssueSlotsTest`, one scene each.
- [x] **8 threads acquiring at once against a capacity of 3 get exactly 3 tokens.** `CouponIssueSlotsTest` through the base's start gate and `tally`: 8 successes (a full slot is a `null`, not an exception), 0 technical errors, 3 non-null tokens, and those 3 are the Set.
- [x] **A capacity below 1 fails the context at startup.** `CouponIssuePropertiesTest` with `ApplicationContextRunner`: 0 and -1 fail with the property's message, 1 starts.
- [x] **The commands go through `redisTemplateMaster`, and the script is loaded from `resources/redis/`.** By inspection: `RedisCouponIssueSlots` takes the `REDIS_TEMPLATE_MASTER` template, and the script is `ClassPathResource("redis/coupon-issue-slot-acquire.lua")`. No test can tell the master from a replica, because the test Redis is one node.
- [x] **`HexagonalArchitectureTest` passes with its rules unchanged.**
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **The adapter test builds the adapter itself at a capacity of 3.** A `@TestPropertySource` with another `coupon-issue.slot-capacity` would change the context cache key and start one more full context (MySQL, Redis, Kafka, and a Hikari pool against the 300-connection test MySQL). So `CouponIssueSlotsTest` builds `RedisCouponIssueSlots` over the context's master template, and one scene goes through the context's bean to check the wiring and the configured 10. Issue 228's `CouponIssueSlotsIntegrationTest` needs its own context anyway, for the capacity and the spy.
- **The test sits with the required-port tests but on `BaseCommittingApplicationServiceTest`.** `BaseRepositoryTest` has no Redis, and the committing base has the start gate. `CODING_STANDARDS.md` now lists it and `CouponIssuePropertiesTest` among the bases' exceptions.
- **The capacity is checked by `require` in the properties' `init`, not by Bean Validation.** `CODING_STANDARDS.md` keeps Bean Validation on Requests. A failed `require` fails the binding, and so the start.
- **`CouponIssueProperties` lives in `adapter/persistence/coupon`, beside the only adapter that reads it, and `@ConfigurationPropertiesScan` registers it.** `PgProperties` is also registered by its config's `@EnableConfigurationProperties`, which the scan makes redundant.
- **The script's result type is `Long::class.javaObjectType`.** Kotlin's `Long::class.java` is the primitive `long`, and Spring Data maps the script's return type from the boxed `java.lang.Long`.

### Mutation probes

Uncommitted local changes, each restored after its run:

| Change | Tests that failed |
| --- | --- |
| No `require` on the capacity | 2: the 0 and -1 scenes |
| `release` does nothing | 2: one release then acquire, release twice |
| `release` deletes the whole key | 3: the release scenes |
| A fixed token instead of a UUID | all 7 slot scenes |
| `>` instead of `>=` in the script's size check | 3: both capacity scenes and the race |
| `SCARD` then `SADD` as two calls instead of the script | the race scene, in 3 of 3 runs, every time with all 8 threads admitted |

The last row is why the check and the add run in one script. With two round trips, every thread read a size of 0 before any of them added.

### Review

A two-axis review ran on `551d5b05` before the final check.

- **Spec:** no missing requirement, no scope creep. The Lua script matches the spec's line for line. It noted that the master template holds only by inspection, and that the -1 scene did not check the message, which `205bf543` adds.
- **Standards:** fixed in `205bf543`: the race scene did not go through `tally` as start gate tests must, the port's KDoc said "frees", which `CONTEXT.md` lists to avoid for 자리를 놓다, and `CODING_STANDARDS.md` did not list the two new classes' bases.
- **Left as they are:** the test spells out the key `coupon-issue:slots` (the adapter keeps it private) and the configured 10 (the spec's value, kept as an independent expected value rather than read back from the properties). The new `application.yml` comment is English among Korean neighbours, as the new-comments rule asks.

## Issue 228: an issue starts only with a free slot, and answers 429 without one

Implemented as `67b8011d` and the review fixes `88337f1d`. `scripts/final-check --app commerce-api` on `88337f1d`, a clean tree, started 2026-11-12T03:49:27Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 108 classes, 1163 tests (5 min 10 s), 0 failures, 0 errors, 0 skipped**. Issue 227 ended at 107 classes and 1153 tests, so this ticket adds 1 class (`CouponIssueAdmissionTest`, 7 tests) and 3 tests in `CouponApiTest`. It starts no new Spring context. Only this document changed after `88337f1d`.

`CouponIssueService` now runs four steps: the early read (a holder is answered and takes no slot), `acquire()` (a `null` throws `CouponIssueCapacityExceededException` before any issuing transaction), `issueUnderLock`, and `release(token)` in a `finally`. Under `Propagation.NEVER` the transaction has committed or rolled back by the time `issueUnderLock` returns or throws, so a released slot always means the database work is over. `ApiControllerAdvice` answers the exception with 429 `COUPON_ISSUE_CAPACITY_EXCEEDED`, with no `Retry-After`. Redis exceptions from the slots are not caught.

### Acceptance criteria

- [x] **With a free slot, the first issue answers 201 as before, and the Set is empty afterwards.** `CouponApiTest`'s first-issue scene now also reads the Set, and `CouponIssueAdmissionTest` checks the same at the port.
- [x] **With every slot taken, a user without a coupon gets `CouponIssueCapacityExceededException` from the port and 429 `COUPON_ISSUE_CAPACITY_EXCEEDED` over HTTP; no coupon row exists for that user and the issued count is unchanged.** `CouponIssueAdmissionTest` and `CouponApiTest`. The slots are filled by the test base's `takeEveryCouponIssueSlot()`, which acquires through the port until it answers `null`.
- [x] **A refused issue sends no `FOR UPDATE`.** The port's refusal scene runs inside `captureSql` and finds no locking read.
- [x] **With every slot taken, a user who already holds the coupon still answers 200 with it.** At the port (same coupon ID, `newlyIssued` false) and over HTTP (200, same body as the 201).
- [x] **With every slot taken, `GET /api/v1/coupons` still answers.** `CouponApiTest`.
- [x] **After an issue rejected as exhausted, as suspended, and as not found, the Set is empty.** `CouponIssueAdmissionTest`, one scene each.
- [x] **After a slot is freed, the refused user's retry answers 201.** `CouponIssueAdmissionTest`: refused, one token released through the base, retried and issued; the Set then holds exactly the other tokens.
- [x] **Step 1's `CouponIssueIntegrationTest` passes unchanged at the default capacity of 10.** The file has no diff; 12 of 12 pass.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.**

### Decisions made while implementing

- **The sequential scenes are a new class, `CouponIssueAdmissionTest`, on the plain committing base.** Step 1's class had to stay unchanged, and issue 229's `CouponIssueSlotsIntegrationTest` needs its own context at a capacity of 3. This class adds no spy and no property, so it shares the base's context.
- **The slots are filled by acquiring until `null`, not `capacity` times.** The scenes hold at any configured capacity and do not read the properties back.
- **The release covers a lock timeout and an unexpected exception through the same `finally`, without a scene of its own.** The ticket's criteria name the three business rejections; the lock-timeout and unexpected-exception paths need a spy and a second context, and issue 229's held-slot scene already brings one.
- **The 429 handler logs without the stack trace.** Full slots are load, not a fault, and a burst answers many of them. The handler's KDoc says so; the neighbouring handlers pass the exception.
- **The Set is read through one test helper, `heldCouponIssueSlotTokens()` in `support/CouponIssueSlotSet.kt`.** Three classes read it now, so the key `coupon-issue:slots` is spelled once in the tests. `CouponIssueSlotsTest` moved to it.

### Mutation probes

Uncommitted local edits to `CouponIssueService`, each restored after its run of `CouponApiTest` and `CouponIssueAdmissionTest`:

| Change | Tests that failed |
| --- | --- |
| No `release` | 6: the first issue's empty Set (HTTP and port), the retry, and the three rejections |
| `release` only after a successful `issueUnderLock`, not in a `finally` | 3: the exhausted, suspended and not-found scenes |
| `acquire` before the early read | 8: both holder scenes, and every scene that expects an empty Set (the extra slot is never released) |
| The refusal thrown after `issueUnderLock` instead of before it | 3: the port refusal (a `FOR UPDATE` went out and a coupon was issued), the HTTP 429, and the retry |

### Review

A two-axis review ran on `67b8011d` before the final check.

- **Spec:** no missing requirement and no wrong implementation. It noted minor scope beyond "contract only" at the HTTP seam (the holder's 200 and the list), which Customer stories 5 and 6 ask for, and the new support helper. It also noted that a `release` that throws after a throwing `issueUnderLock` hides the business exception, which the spec accepts until `feat/coupon-admission-failure`.
- **Standards:** fixed in `88337f1d`: the two test classes injected the slots port only to prepare, and the same prepare helper sat in both, against `CODING_STANDARDS.md`'s fixture rules. `takeEveryCouponIssueSlot()` and `releaseCouponIssueSlot()` now live in `BaseApplicationServiceTest`. Also fixed: the pass-through `heldTokens()` wrappers, and the log choice now has its comment.
- **Left as they are:** `CouponIssueCapacityExceededException` sits in `application/coupon`, not `provided/` as the PG exceptions do, because the ticket and the spec place it there. Its message is written in both the exception and `ErrorType`, as the coupon campaign exceptions already do.

## Issue 229: the capacity holds with issues in flight, and the design is written down

Implemented as `fa02fc20` and the review fixes `f47653a8`. `scripts/final-check --app commerce-api` on `f47653a8`, a clean tree, started 2026-11-12T04:08:58Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 109 classes, 1164 tests (5 min 12 s), 0 failures, 0 errors, 0 skipped**. Issue 228 ended at 108 classes and 1163 tests, so this ticket adds 1 class (`CouponIssueSlotsIntegrationTest`, 1 test). That class starts one more Spring context, for its capacity of 3 and its spy. No production code changed, as planned: the scene passed against issue 228's service on the first run. Only documents changed after `f47653a8`.

### Acceptance criteria

- [x] **Three different users' issues are held inside `issueUnderLock`; the Set holds 3 tokens.** A springmockk spy on `CouponIssueSteps` (stubbed on the proxy's target) pauses each of the three users on their own `Pause` before `callOriginal()`. The slot is taken and no SQL has gone out, so no held issue holds the campaign's lock and the three do not queue behind one another on the same campaign.
- [x] **A fourth user's issue is refused with `CouponIssueCapacityExceededException`; that user has no coupon row and the issued count shows no issue for them.** The fourth user's coupon list is empty and the issued count is 0.
- [x] **One held issue is let through and finishes; the fourth user's retry is issued.**
- [x] **After all held issues finish, the Set is empty, and coupon rows and the issued count both equal the number of issued users.** 4 and 4.
- [x] **Red check, a local edit never committed: with the `release` removed from the service, the fourth user's retry is refused and the Set is not empty at the end.** The scene failed at the retry with `CouponIssueCapacityExceededException`. A second local variant that let the retry fail and carried on ended with 3 tokens in the Set (`Expecting empty but was: [...]`, three UUIDs) and 3 coupon rows. Both edits were reverted.
- [x] **`docs/design/coupons.md` has the step 2 section, the corrected 8절 sentence and the updated 1.1 table, with the scene line from a real run.** Section 9 (9.1–9.9) and the handover to step 3 rewritten as section 10. The scene line in 9.8 also came out the same in the final check's reports.
- [x] **`scripts/final-check` passes, and its commit, timings and test counts are recorded in the design doc.** 9.9.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to `docs/research/week8-coupon-admission-writing-material.md`.**

The scene's line, from the final check's reports (`scripts/scene-lines --results build/final-check/commerce-api-test-results CouponIssueSlotsIntegrationTest`):

```
Capacity scene: capacity 3: 3 issues held in their slots, the next user refused with CouponIssueCapacityExceededException, issued on retry after one finished; slots held at the end 0, coupon rows 4, issued count 4
```

### Decisions made while implementing

- **The pause sits before `callOriginal()`, not after it as in step 1's lock scene.** Pausing after the writes would leave the first held issue holding the campaign's `FOR UPDATE` lock. The other two would then wait on that lock and fail after 3 seconds instead of sitting in their slots. Pausing before the original keeps all three on one campaign without contention. The two stubs differ in that one line, so they stay local, not a shared spy extension.
- **A held issue holds a pooled connection.** The stub is on the target behind `@ApplicationService`'s transactional proxy, so its transaction has begun. The ticket's "between the acquire and the DB transaction" is loose on this point; the design (9.8) and the class KDoc say it exactly.
- **Everything is one test.** The refusal, the retry and the empty Set are the assignment's single §5 scene, and they share the held issues.
- **The design keeps the sections' numbers and adds step 2 as section 9.** The old handover (section 9) is now section 10 and speaks to step 3. The 429 row also goes into 5절's error table, and 7절's queue bullet points at 9.7.

### Review

A two-axis review ran on `fa02fc20` before the final check.

- **Spec:** no missing requirement, and no scope creep in code. It noted that the held issue's transaction is already open, which `f47653a8` now says in the KDoc and in 9.8. It counted the 5절 row and the 7절 bullet as harmless doc additions.
- **Standards:** fixed in `f47653a8`:
  - The capacity's `@TestPropertySource` was missing from `CODING_STANDARDS.md`'s closed list of annotations a subclass may add, which now has a dated note.
  - Three coupon classes each kept private copies of the committed coupon count, issued count and coupon list reads. These are now `committedCouponCount()`, `committedIssuedCount()` and `committedCouponsOf()` in `BaseCommittingApplicationServiceTest`, beside its other `committed*` reads.
  - `heldCustomers` is now `heldUsers` (`CONTEXT.md` avoids Customer).
  - Step 1's `CouponIssueIntegrationTest` KDoc still said the slots would go "in front of the API". It now says they sit inside the port and its scenes pass through them at 10. Its scenes did not change.
- **Left as they are:** the "명령 한도 100 ms" phrase in 9.3 (a Redis timeout, not 수용량). The other `customer` names in `CouponIssueIntegrationTest` are left too: they predate this branch, and no sweep was asked for.

## Spec 226: the closing review

Recorded 2026-11-12 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/226#issuecomment-6815705259) of [spec 226](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/226). The text is the comment's, with its blob URLs and its headings one level down.

Step 2 of week 8 is built, and its closing review is done. `scripts/final-check --app commerce-api` on `3f6e24d9`, a clean tree, started 2026-11-12T05:04:53Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 109 classes, 1164 tests (5 min 1 s), 0 failures, 0 errors, 0 skipped**. Step 1 ended at 105 classes and 1143 tests, so this step adds 4 classes and 21 tests. Only documents changed after `3f6e24d9`.

Tickets 227–229 are closed, each with its own comment. [Design 9.1](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/design/coupons.md#91-2단계가-만든-것) lists what each one built.

### Where the implementation differs

| The spec said | What is there | How it was found |
| --- | --- | --- |
| The held-slots class `CouponIssueSlotsIntegrationTest` also covers the exhausted rejection's empty Set and the holder's repeat request | Those sequential scenes are `CouponIssueAdmissionTest`, on the plain committing base and the default context. The held-slots class keeps the assignment's one §5 scene in its own context at capacity 3 | Issue 228 |
| A held issue is paused after the acquire and before the DB transaction | It is paused before `callOriginal()` but behind the transactional proxy, so its transaction has begun and it holds a pooled connection, though not the campaign's lock | Issue 229 ([design 9.8](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/design/coupons.md#98-검증)) |
| Step 1's four scenes in `CouponIssueIntegrationTest` pass unchanged | Their assertions are unchanged. Issue 229 rewrote the class's KDoc and moved its committed reads to the base, and this review added two assertions on the slot Set (below) | Issue 229 and this review |

**Additions the spec didn't ask for:**

- `CouponApiTest` also covers the holder's 200 and the coupon list while every slot is taken, as Customer stories 5 and 6 ask.
- The 429 is logged without its stack trace: full slots are load, not a fault ([design 9.5](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/design/coupons.md#95-api와-오류)).
- Test bases gained `takeEveryCouponIssueSlot()`, `releaseCouponIssueSlot()` and the `committed*` reads, and `support/CouponIssueSlotSet.kt` reads the Set. `CODING_STANDARDS.md` lists the new classes' bases and the capacity's `@TestPropertySource`.

**Settled while building:**

- With the check and the add as two round trips instead of the script, 8 threads against a capacity of 3 were all admitted, in 3 of 3 runs (issue 227). The capacity is the scene's setting, not a measured value.
- The capacity of 10 is a starting point, not a measurement ([design 9.4](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/design/coupons.md#94-수용량)).
- Spec 226 has no 구현에서 확인할 것 section, so nothing is open under that rule.

### The closing review

A two-axis review of `week8...feat/coupon-admission` at `32ab1f2f` (10 commits, 24 files), with one reviewer on the standards and one on this spec. Neither found a hard violation or a defect in production code. Standards reported 7 judgement calls, Spec 5 findings and one 측정과 증거 gap. Each was checked against the code and decided one by one.

#### What changed

`3f6e24d9`:

- **Story 14 now has scenes.** A release after a lock timeout and after an unexpected exception held only by reading the `finally`. Step 1's rollback and lock scenes already drive both paths through the port with their own spies, so each now also reads the Set: empty after the rollback, and only the paused holder's token after the waiter's lock failure. No context was added. With `release` moved out of the `finally` to after a success, exactly these two failed (`Expecting empty but was` one token, `Expected size: 1 but was: 2`) ([design 9.8](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/design/coupons.md#98-검증)).
- **Capacity 3 is labelled as a setting.** Design 9.3's two-round-trip claim now says it ran at capacity 3, where at production's 10 eight threads would get in anyway. 9.8 says its scene shows behaviour and measures nothing.
- **Slot actions use the port's verb.** `ADMITTED` is now `ACQUIRED`, and a KDoc, a test name and a class comment say acquire where they said admit. "Admission" stays as the step's name.
- **A test KDoc no longer says the flush "frees" the slots** (`CONTEXT.md` avoids it for 자리를 놓다). It says the flush empties the Set.
- **The race test's note on `tally`** moved from the `THREADS` constant to the assertion it explains.

#### Left as they were

- **The key `coupon-issue:slots` spelled in the test support.** The adapter keeps it private, and the tests' positive assertions would fail before an empty-Set check could pass against a drifted key.
- **`CONFIGURED_CAPACITY = 10` as a literal.** It is the expected value, so a change to `application.yml` fails the test and sends the reader to design 9.4.
- **Each class injecting the master template to read the Set.** `CODING_STANDARDS.md` takes assertion dependencies through the constructor.
- **Story 18's master connection holds by inspection.** The test Redis is one node, as issue 227 recorded.

#### A correction

Issue 227's comment calls `CouponIssueSlotsIntegrationTest` "Issue 228's". It is issue 229's, as issue 228's body planned. That comment and its copy are left as posted.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/research/week8-coupon-admission-writing-material.md#spec-226-the-closing-review).

## Draft PR body

A draft for the PR from `feat/coupon-admission` into `week8`, in the shape of step 1's, written after the spec's closing review. The PR's copy links these documents with blob URLs and carries the spec's closing comment.

Title: `feat(commerce-api): start a coupon issue only with a free processing slot in Redis, and answer 429 without one`

````markdown
## Summary

Week 8 step 2. At most `capacity` issuing transactions run at once across every coupon campaign, counted in one Redis Set. Without a free slot the request is answered 429 at once and no DB work starts. Step 1's quota and one-coupon rules are unchanged, and Redis failures (step 3) come later.

- **Spec:** issue 226, with tickets 227–229, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/226#issuecomment-6815705259)
- **Decision:** none new. Design 9.6 says why the key needs no ADR
- **Design:** [`docs/design/coupons.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/design/coupons.md). Section 9 is this step: 9.2 the order, 9.3 the Set and Lua, 9.6 what wasn't chosen, 9.7 the costs, 9.8 the scenes and red checks. Section 10 hands over to step 3
- **Writing material:** [`docs/research/week8-coupon-admission-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission/docs/research/week8-coupon-admission-writing-material.md), which holds every ticket's closing comment

```diff
 POST /api/v1/coupon-campaigns/{campaignId}/issues
   CouponIssueService.issue                    # Propagation.NEVER
     early read of the requester's coupon      # found: 200, no slot taken
+    CouponIssueSlots.acquire()                # Lua: SISMEMBER, SCARD >= capacity, SADD on coupon-issue:slots
+                                              # null: 429 COUPON_ISSUE_CAPACITY_EXCEEDED, no FOR UPDATE
     CouponIssueSteps.issueUnderLock           # own TX, step 1 unchanged
+    finally CouponIssueSlots.release(token)   # SREM of its own UUID token, after commit or rollback
```

The capacity is `coupon-issue.slot-capacity`, 10 in every profile and at least 1 at startup: a quarter of the pool of 40, and a starting point, not a measurement. The slots know nothing of the quota, and a release changes neither the issued count nor a coupon.

## Review Points

#### 1. Is one Set for every campaign the right bound?

The assignment bounds the issuing path as a whole, so the slots protect the connection pool's share. The cost is that one hot campaign fills every slot, one running and the rest waiting on its row lock, and other campaigns' requests get 429 while the DB is idle (design 9.7).

**Would you trade that for a per-campaign bound, given that the sum then grows with the number of campaigns?**

#### 2. Is a Set with no TTL the right call?

A JVM that dies between acquire and release leaves its token behind, holding a slot for good. A TTL would clear it but can't tell a dead execution from a slow one, so it would let in more than the capacity. Step 3's resume rule is to clean up instead (design 9.6, 10).

**In practice, have you relied on expiry for a bound like this, and what did it let through?**

#### 3. How would you size the capacity?

10 was picked from the pool's share and the step 1 scenes' 8 threads, not under load.

**What would you measure to choose it: pool wait time, the 429 rate, or the lock wait on one hot campaign?**

## Evidence

**Before:** on `week8`, nothing bounds how many issues run at once. Every issue for one campaign holds a connection while it queues on the row, and an overflow waits 3 s for a 409 `CONCURRENT_REQUEST`.

**After:** `CouponIssueSlotsIntegrationTest` on real Redis and MySQL at capacity 3, a scene setting, holds three issues inside `issueUnderLock` (from the final check's run, `scripts/scene-lines`):

```text
Capacity scene: capacity 3: 3 issues held in their slots, the next user refused with CouponIssueCapacityExceededException, issued on retry after one finished; slots held at the end 0, coupon rows 4, issued count 4
```

| Red check (local, never committed) | Result |
| --- | --- |
| The check and the add as two round trips instead of the script | 8 threads at capacity 3: all 8 admitted, 3 runs of 3 |
| `release` removed | The fourth user's retry refused, and the Set keeps 3 tokens |
| `release` only after a success, not in the `finally` | The exhausted, suspended and not-found scenes, and the rollback and lock-timeout scenes, leave tokens behind |

`scripts/final-check --app commerce-api` on `3f6e24d9`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-api | 109 (105 before) | 1164 (1143 before) | 0 |

## Merge Danger

**Door:** two-way

No schema change. The only new state is the Redis Set `coupon-issue:slots`, which is empty whenever no issue is running. A revert leaves at most a few stale tokens that nothing reads.

**Blast Radius:** coupon issuing

- **Issuing now needs Redis.** Until step 3, a Redis failure answers 500 instead of issuing. A failed acquire starts no transaction. A failed release after a commit is a 500, but the coupon exists and a retry gets 200 from the early read.
- **Unchanged:** the coupon list, a holder's repeat request, orders, payments, the outbox and commerce-streamer.
- **Tests:** step 1's contention scenes now pass through the slots at 10, and the capacity-3 scene starts one more Spring context.
- **Out of scope:** 503 and closing or resuming the slots, waiting or queueing, per-campaign or per-user limits.
````
