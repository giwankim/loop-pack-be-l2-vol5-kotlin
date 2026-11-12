# Week 8 coupon admission failure: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from a closing comment: one per ticket of [spec 231](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/231), then the spec's own.

## Issue 232: slot tokens carry the instance's name, and a release reports what it removed

Implemented as `86dc97ef` and the review fix `204d2fd4`, on top of `3da0ee87`, which adds 발급 수용, 수용을 닫다 and 수용을 열다 to `CONTEXT.md`. `scripts/final-check --app commerce-api` on `204d2fd4`, a clean tree, started 2026-11-12T12:34:51Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 109 classes, 1174 tests (5 min 24 s), 0 failures, 0 errors, 0 skipped**. Step 2 ended at 109 classes and 1164 tests, so this ticket adds 10 tests and no class: 5 in `CouponIssueSlotsTest` and 5 in `CouponIssuePropertiesTest`. It starts no new Spring context. An earlier run on `86dc97ef`, before the review fix, passed with 1172. Only this document changed after `204d2fd4`.

### Acceptance criteria

- [x] **A token starts with the configured instance name, and two acquires give different tokens.** `CouponIssueSlotsTest`: the adapter at instance `commerce-api-1` mints `commerce-api-1:…`, and the context's bean mints `localhost:…`, the `local, test` block's literal. "Every acquire mints a different token" was already there.
- [x] **`release` answers `true` for a held token, `false` for a second release and for an unknown token, and leaves other tokens in the Set.** `CouponIssueSlotsTest`, two scenes. They replace step 2's "releasing twice" and "never acquired" scenes, which now also assert the answer.
- [x] **Removing this instance's tokens leaves a token with another name (put in the Set by the test) in place.** `CouponIssueSlotsTest`: `commerce-api-2:…` survives, and so does `commerce-api-1-canary:…`, a name that only starts with this one's. A third scene removes nothing from an empty Set.
- [x] **After an acquire the key has no expiry.** `CouponIssueSlotsTest` reads `TTL` and gets -1.
- [x] **A blank instance name fails the startup (`CouponIssuePropertiesTest`).** Blank and a name containing `:` fail with the property's message. Two more scenes read the real `application.yml` under `prd`: with `HOSTNAME` it binds that name; without it the start fails (the review fix below).
- [x] **Step 1's and step 2's coupon tests pass; only assertions that rest on the token's shape change.** The final check. No step 1 or step 2 test outside `CouponIssueSlotsTest` changed. `CouponIssueService` ignores `release`'s answer for now.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to this document.** It starts it.

### Decisions made while implementing

- **The third operation is `releaseOwnTokens()`.** It is the port's release, applied to every token carrying this instance's name, so it keeps 자리를 놓다's verb rather than "clear" or "remove". It returns nothing, as the spec's shape has it.
- **The prefix is `<instance-id>:`, colon included.** The `:` ban is what makes a plain prefix safe: without the trailing `:`, instance `commerce-api-1` would also remove `commerce-api-1-canary`'s tokens. A scene pins it.
- **An unexpected script answer throws `IllegalStateException`.** It is neither a `DataAccessException` nor a business rejection, and the next ticket treats any exception from `acquire` as a failed acquire. No scene produces such an answer on real Redis, so the branch is untested.
- **The `local, test` literal is `localhost`**, as `redis.yml` writes its local hosts.
- **The test helpers write the Set straight.** `addCouponIssueSlotToken` stands in for another instance's acquire, and `couponIssueSlotsTimeToLive` reads `TTL`, both in `support/CouponIssueSlotSet.kt` beside `heldCouponIssueSlotTokens`.

### Mutation probes

Uncommitted edits to `RedisCouponIssueSlots`, run against `CouponIssueSlotsTest` and reverted (2026-11-12):

| Mutation | Red scenes |
| --- | --- |
| The prefix without its `:` | 1: the `-canary` token is removed |
| `release` always answers `true` | 2: the two scenes asserting its answer |
| `releaseOwnTokens` without the prefix filter | 2: both scenes keeping another name's token |

### Review

A two-axis review ran on `86dc97ef`.

- **Spec:** nothing missing, no scope creep. It noted that the `else -> throw` branch of `acquire` has no test, which the ticket does not ask for.
- **Standards:** no hard violation. Fixed in `204d2fd4`:
  - **A missing `HOSTNAME` started the app.** Spring binds an unresolvable `${HOSTNAME}` as its literal text, which is neither blank nor contains `:`. Every instance without the variable would have shared the name `${HOSTNAME}`, against story 25. A new scene confirmed it red before the fix ("Expecting code to raise a throwable"). The yml now reads `${HOSTNAME:}`, so a missing variable is blank and the existing check fails the start.
  - **`CODING_STANDARDS.md` described `CouponIssuePropertiesTest` as checking only the capacity.** It now names the instance name and the `MockEnvironment` scenes.
- **Left as they are:** the token format is spelled in the adapter and in test literals rather than a token type (the repo keeps no wrapper type without a need, as with names); `release`'s answer and `releaseOwnTokens` have no production caller until issue 233, which the spec orders.

## Issue 233: admission starts closed, closes on a slot failure, and answers 503 while closed

Recorded from [issue 233's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/233#issuecomment-6824619914).

Implemented as `b8e67853`. `scripts/final-check --app commerce-api` on `b8e67853`, a clean tree, started 2026-11-12T13:07:07Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 112 classes, 1182 tests (5 min 6 s), 0 failures, 0 errors, 0 skipped**. Issue 232 ended at 109 classes and 1174 tests, so this ticket adds 3 classes and 8 tests: `CouponIssueAdmissionClosingTest` (4), `CouponIssueAdmissionApiTest` (1) and `CouponIssueAdmissionStartupRunnerTest` (3, no Spring context). The first two start a Spring context each.

### Acceptance criteria

- [x] **With admission open, issuing answers as in step 2 (201, 200, 404, 409s, 429).** The final check: step 1's and step 2's coupon tests pass unchanged, now through the admission.
- [x] **A held token removed from the Set during an issue: that issue answers 201 with its coupon, and the next new user gets `CouponIssueAdmissionClosedException` from the port and 503 `COUPON_ISSUE_ADMISSION_CLOSED` over HTTP.** The port side is in `CouponIssueAdmissionClosingTest`. It also asserts one ERROR line on closing. The HTTP side is in `CouponIssueAdmissionApiTest`. In both, a spy on `CouponIssueSteps.issueUnderLock` removes the issue's own token before the transaction.
- [x] **A refused request while closed leaves no coupon row and no change to the issued count, and sends no `FOR UPDATE`.** Both classes capture the SQL. The HTTP scene also checks that no token reached the Set and that the 503 carries no `Retry-After`.
- [x] **While closed, a user who holds the coupon still answers 200, and `GET /api/v1/coupons` still answers.** The holder is checked in both classes, and the list in `CouponIssueAdmissionApiTest`.
- [x] **A full capacity still answers 429 and does not close admission.** Step 2's `CouponIssueAdmissionTest` scene "after a slot is released the refused user's retry is issued" now runs through the admission: the refused user is issued on retry, so the 429 closed nothing.
- [x] **Opening while an issue is held on a latch is refused and admission stays closed.** `CouponIssueAdmissionClosingTest`: `CouponIssueAdmissionOpenRefusedException`, the next new user is refused as closed, and the held issue still finishes with its coupon and releases its slot.
- [x] **Opening removes this instance's leftover tokens and the next new user answers 201.** `CouponIssueAdmissionClosingTest`: a leftover `localhost:` token is removed, while `other-instance:live` stays.
- [x] **A context started with `admission-open-on-startup=false` refuses new issues with 503 until the opening is called.** This is how `CouponIssueAdmissionApiTest` starts. The runner tests add two cases. With the property absent, the runner calls nothing. With an opening that fails, it logs one ERROR and does not throw.
- [x] **`HexagonalArchitectureTest` passes with no rule changed.** The runner lives in `adapter/startup` and sees only the provided port.
- [x] **`ktlintCheck` and `check` on commerce-api pass.** The run above.
- [x] **The closing comment is appended to `docs/research/week8-coupon-admission-failure-writing-material.md`.**

### Decisions made while implementing

- **The admission runs the issue: `admit { … }`.** It does not expose separate acquire and release calls. The issue counts as running from before its acquire until after its release attempt. That counting has no way to leak, and the service cannot call the port any more.
- **`CouponIssueAdmission` is `@ApplicationService(propagation = NEVER)`.** The stereotype's default `REQUIRED` would have wrapped the issuing transaction in one of its own, and the early read's snapshot problem would come back.
- **The provided port is `CouponIssueAdmissionOpener`, with `open()`.** It is named after its single action, like `OrderEventRelayer`. `CouponIssueAdmissionOpenRefusedException` sits next to it in `provided`, as `OrderEventRecoveryRefusedException` does.
- **No lock: the order of the counter and the flag keeps opening safe.** `admit` counts itself running before it reads the flag. `open()` reads the count before it removes tokens, and sets the flag only after. So a racing issue either is counted, and the opening is refused, or it sees "open" only after the cleanup. A request refused while closed counts as running for an instant, so an opening at that moment is refused. That is the admit-less direction.
- **Opening an open admission does nothing.** It neither checks for running issues nor removes tokens. Only the startup calls it, and the tests' cleanup.
- **Only the change from open to closed logs.** `compareAndSet(true, false)` logs one ERROR with the cause. A failure while already closed, such as a second release failing, logs nothing more here. The 503 handler logs a WARN without the stack.
- **The runner reads the property through `Environment`, with `false` as the code default.** `CouponIssueProperties` lives in the persistence adapter, which the startup adapter must not see. Unlike the 누락 복구 runner, it catches the failure: a Redis outage at startup must leave the app up.
- **Messages:** the 503 says "지금은 쿠폰 발급을 받지 않습니다. 아무것도 발급하지 않았습니다." It promises no later issue (story 8). The refusal to open is English, like its provided neighbour.

### Mutation probes

Uncommitted edits, run against the new classes and reverted (2026-11-12):

| Mutation | Red scenes |
| --- | --- |
| `open()` without the running-issue check and without `releaseOwnTokens()` | 2: the latch scene (no refusal thrown) and the cleanup scene (`localhost:left-over` survives) |
| A token missing at release does not close | 1: `CouponIssueAdmissionApiTest`, where the latecomer gets 201 instead of 503 |
| The runner's default flipped to `true` and its try/catch removed | 2: with the property absent `open()` is called; a failed opening throws |

`CouponIssueAdmissionClosingTest`'s first scene was red before the admission existed: no exception was thrown. The HTTP scene was red before the handler: it answered 500.

### Review

A two-axis review ran on the uncommitted tree before `b8e67853`, and its fixes are in that commit.

- **Spec:**
  - **One gap:** no single scene went from a removed token to a 503 over HTTP. The first HTTP class only started closed. It is now one scene in `CouponIssueAdmissionApiTest`: closed from the start → 503 → `open()` → 201 → a token removed → 201 → the next user 503 → the holder 200 and the list.
  - **Nothing wrong** in how opening and admitting interact when they run at once.
- **Standards (all in tests):**
  - The first HTTP class injected `CouponRepository` only to prepare the holder. It now uses `prepareCoupon` while admission is open.
  - That class gave no reason for `NOT_SUPPORTED`, and stored no response body in a named local. Both are fixed.
  - The two classes' `@MockkSpyBean` (and the HTTP class's `@TestPropertySource`) are not on `CODING_STANDARDS.md`'s list of annotations a subclass may add. They now have a dated issue-233 note. A second note allows calling the issue port to set up a closed admission, since a real issue's release is the only way to close it.
  - The pause and lose-token stubs moved to `support/CouponIssueSpies.kt`. Step 2's `CouponIssueSlotsIntegrationTest` uses the shared `pauseIssuing` too, which reverses issue 229's "no extension function", since both stubs now have the same shape.
- **Left as they are:**
  - The runner test spells out the property name instead of using the constant, so it checks the name independently.
  - The test's `INSTANCE_ID = "localhost"` repeats `CouponIssueSlotsTest`'s constant.
  - A third class repeats the `ListAppender` setup.
  - The real-outage scenes (stopped or paused Redis, a release failing mid-transaction, Redis down at startup) are issue 234's.

## Issue 234: a real Redis outage closes admission, and the rule is written down

Recorded from [issue 234's closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/234#issuecomment-6825196956).

Implemented as `51c3939f` (the scenes) and `28c3aca1` (ADR 0037 and design section 11). `scripts/final-check --app commerce-api` on `28c3aca1`, a clean tree, started 2026-11-12T13:28:05Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 113 classes, 1186 tests (5 min 59 s), 0 failures, 0 errors, 0 skipped**. Issue 233 ended at 112 classes and 1182 tests, so this ticket adds 1 class and 4 tests: `CouponIssueRedisFailureTest`, which starts one Spring context of its own (the `StoppableRedis` one). No production code changed.

### Acceptance criteria

- [x] **Redis stopped, a new user issues: 503 `COUPON_ISSUE_ADMISSION_CLOSED`, no coupon row, issued count 0, no `FOR UPDATE`.** The first scene. The newcomer's list is empty; the only row and the issued count of 1 are the holder's, prepared before the stop.
- [x] **In the same outage a holder's repeat answers 200 with the same coupon, and the coupon list answers.** The same scene.
- [x] **Redis started again: a new user still gets 503; after the opening, 201.**
- [x] **Redis paused: a new user gets 503 within the command timeout, and admission is still closed after unpausing.** Asserted against a 2 s bound, as the repo's Redis failure tests assert time. The recorded refusal took 123–129 ms (100 ms command timeout plus the early lookup). `RedisTimeoutTest` owns the 100 ms value itself.
- [x] **Redis stopped during the issuing transaction: 201, coupon row 1, issued count 1; after the start the token is still in the Set and the next user gets 503; after the opening the Set is empty and the next user gets 201.** The token survives because `StoppableRedis` saves an RDB on SIGTERM.
- [x] **Both red checks are recorded in the design doc with what failed.** Design 11.8.
- [x] **The scene lines are readable with `scripts/scene-lines CouponIssueRedisFailureTest`.** Each scene logs one INFO line after its assertions.
- [x] **ADR 0037 and the design doc section are written, in Korean, with the limits stated.** Design 11.7 has the five limits from spec 231.
- [x] **`scripts/final-check --app commerce-api` passes on a clean tree, and its commit, timings and test counts are in the design doc.** Design 11.9.
- [x] **The closing comment is appended to this document.**

### Recorded run

From the final check's reports (`scripts/scene-lines --results build/final-check/commerce-api-test-results CouponIssueRedisFailureTest`):

```
Release failed: 201 with coupon rows 1, issued count 1; after the start 1 token left in the Set and the next user 503; after the opening 0 tokens and the next user 201
Stopped Redis: new user 503 COUPON_ISSUE_ADMISSION_CLOSED with 0 FOR UPDATE; holder 200 with the same coupon, list 200; coupon rows 1 (the holder's), issued count 1
Paused Redis: new user 503 in 129 ms (bound 2000 ms), still 503 after unpausing; coupon rows 0, issued count 0
Restarted Redis: 503 during the outage, still 503 after the start with Redis answering, 201 after the opening; coupon rows 1, issued count 1
```

### Red checks

Uncommitted edits to `CouponIssueAdmission`, run against `CouponIssueRedisFailureTest` and reverted (2026-11-12):

| Mutation | Red scenes |
| --- | --- |
| A failed acquire does not close admission (the `close` call removed from `acquire`'s `catch`) | 2: the restarted-Redis scene and the paused scene, both `expected: 503 but was: 201`. The spec expected only the first; the unpause plays the same part as the restart |
| `open()` without its cleanup (`releaseOwnTokens()` removed) | 1: the release-failed scene at the empty Set, `Expecting empty but was: ["localhost:6097fd84-…"]` |

### Decisions made while implementing

- **The class extends `BaseWebApiAdapterTest`, not the week 4 context.** It needs MockMvc and its own spy, so it could not share `ProductCacheRedisFailureTest`'s context, which spec 231 allowed for ("나눠 쓸 수 있으면").
- **Each scene opens admission before it starts, as well as in cleanup.** A new context's first handshake is bound by the 100 ms command timeout, so the startup's opening can fail and leave the first scene closed. That is the "Redis down at startup" behaviour, but it is not the scene under test.
- **The spy that stops Redis lives in `support/CouponIssueSpies.kt`** as `stopRedisDuringTheIssueOf`. The review then folded all three stubs there into one private `beforeTheIssueOf(userId) { … }`.
- **The two scenes of spec 231's seam-2 table that need no outage stay where issue 233 put them.** The vanished token is in `CouponIssueAdmissionApiTest` (HTTP), and the latch-held opening is in `CouponIssueAdmissionClosingTest` (port). Design 11.8 says so.
- **Design 11.2 corrects a reason from issue 233's notes.** `CouponIssueAdmission` is `NEVER` because under `REQUIRED` the issuing transaction would join `admit`'s and commit after the release, so a free slot would no longer mean the database work is over (9.2). The early read sits outside `admit` either way.

### Review

A two-axis review ran on the uncommitted tree before `51c3939f`, and its fixes are in that commit.

- **Spec:** nothing missing. Fixed:
  - every 503 now asserts `COUPON_ISSUE_ADMISSION_CLOSED`;
  - the stopped scene checks that the newcomer's own list is empty;
  - cleanup empties the tables before reopening, so a failed opening leaves no rows behind;
  - design 11.8 now says where the two moved scenes are, and that the pause bound is 2 s rather than the timeout.
- **Standards:** no hard violation. Fixed: the triplicated spy stub, and two dated notes in `CODING_STANDARDS.md` naming this class's base and constructor dependencies.
- **Left as they are:**
  - `open()` runs both before each scene and in cleanup, because the spec asks for cleanup to open again.
  - "안전한 분산 세마포어가 아니다" uses an Avoid-list word as a contrast, as spec 231 itself does.
  - `CONTEXT.md` says opening "사람이 시켜야 한다", while a restart opens unattended. ADR 0037 records that as a cost, and the glossary is left to its owner.
  - Each scene reads the coupon-rows / issued-count pair, as the earlier coupon tests do.
  - Redis down at startup is not run over HTTP (design 11.10).

## Spec 231: the closing review

Recorded 2026-11-12 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/231#issuecomment-6826398433) of [spec 231](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/231). The text is the comment's, with its blob URLs and its headings one level down.

Step 3 of week 8 is built, and its closing review is done. `scripts/final-check --app commerce-api` on `073941d9`, a clean tree, started 2026-11-12T14:20:51Z with `BOOTSTRAP_SERVERS` unset: ktlint passed, **commerce-api 113 classes, 1187 tests (5 min 38 s), 0 failures, 0 errors, 0 skipped**. Step 2 ended at 109 classes and 1164 tests, so this step adds 4 classes and 23 tests. Only documents changed after `073941d9`.

Tickets 232–234 are closed, each with its own comment. [Design 11.1](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/design/coupons.md#111-3단계가-만든-것) lists what each one built, and [ADR 0037](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/adr/0037-coupon-issue-admission-starts-closed-and-never-reopens-by-itself.md) holds the rule.

### Where the implementation differs

| The spec said | What is there | How it was found |
| --- | --- | --- |
| Seven scenes in `CouponIssueRedisFailureTest` | Five scenes in `CouponIssueRedisFailureApiTest` (renamed in this review). The vanished token is in `CouponIssueAdmissionApiTest` over HTTP, and the latch-held `open()` is in `CouponIssueAdmissionClosingTest` at the port. Neither needs an outage | Issues 233 and 234 |
| Redis stopped, a new user issues: no coupon row, issued count 0 | Coupon rows 1 and issued count 1, both the holder's, prepared before the stop. The newcomer's list is empty | Issue 234 |
| Redis paused: 503 within the command timeout | Asserted against a 2 s bound, as the repo's Redis failure tests assert time. The recorded refusals took 125–129 ms. `RedisTimeoutTest` owns the 100 ms itself | Issue 234 |
| The default block's instance name is `${HOSTNAME}` | `${HOSTNAME:}`. Spring binds an unresolved `${HOSTNAME}` as its literal text, so every instance without the variable would have shared one name | Issue 232's review |
| `open()` in four steps | A fifth: opening an open admission does nothing | Issue 233 |
| The first red check turns one scene red | Two: the paused scene too, since the unpause plays the same part as the restart | Issue 234 |

**Not run:**

- **Redis down at startup, over HTTP (story 22).** The runner test shows that a failing opening does not fail the start, and `CouponIssueAdmissionApiTest` shows the answers of an instance that started closed ([design 11.10](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/design/coupons.md#1110-3단계-뒤에-남은-것)).
- **The script answering neither 1 nor 0 (story 9).** Real Redis and the assignment's script cannot produce it, so `acquire()` throwing there holds by reading only.

### The closing review

A two-axis review of `week8...feat/coupon-admission-failure` at `01d07c2a` (10 commits, 29 files), with one reviewer on the standards and one on this spec. Neither found a defect in production behaviour. Standards reported 4 violations, all in tests and documents, and 5 judgement calls. Spec reported 3 partial requirements and 3 doubts. Each was checked against the code and decided one by one.

#### What changed

`073941d9`:

- **Story 6 now has a scene.** Every release-failure scene ended in 201, so a business rejection surviving a failed release held only by reading the `finally`. The new scene stops Redis during the issue of an exhausted campaign and gets 409 `COUPON_CAMPAIGN_EXHAUSTED`, with the next user refused 503. With the release's exception rethrown instead of swallowed, this scene and the 201 scene both answered 500 ([design 11.8](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/design/coupons.md#118-검증)).
- **The Redis failure class is `CouponIssueRedisFailureApiTest`.** It was the only HTTP test not named `…ApiTest`. Issue 234's comment keeps the old name.
- **A test comment claimed evidence that does not exist.** `CouponIssueAdmissionStartupRunnerTest`'s KDoc cited a class by a wrong name and a startup-outage scene that was never run. It now says so.
- **Two limits of `open()` are written down** ([design 11.7](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/design/coupons.md#117-한계와-대가)):
  - It does not exclude another `open()`. Two overlapping openings could remove the token of an issue admitted between them. Only the startup calls it, once.
  - A request about to be refused counts as running for an instant. If it lands at the startup's opening, the opening is refused and the instance stays closed until a restart. This errs toward admitting less.
- **Body variables follow the naming rule** in the two HTTP classes (`error`, `list`, `secondIssueBody`).
- **The test profile's instance name is one constant** in `support/CouponIssueSlotSet.kt`, where three classes each spelled `localhost`.
- **재개 in design 9.6 and 10** got dated notes: this step says 수용을 열다.

#### Left as they were

- **The `for update` filter repeated in three tests.** Three older tests filter the captured SQL the same way.
- **The 503 message in both `ErrorType` and the exception.** It mirrors `CouponIssueCapacityExceededException`.
- **The startup property read through `Environment`, and its name retyped in tests.** The startup adapter must not see the persistence adapter's properties class, and the retyped literal pins the name.
- **`instanceId` beside "instance name" in prose.** The property is `coupon-issue.instance-id`, as the spec named it.

The committed copy is in the [writing material](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/research/week8-coupon-admission-failure-writing-material.md#spec-231-the-closing-review).

## Draft PR body

A draft for the PR from `feat/coupon-admission-failure` into `week8`, in the shape of step 2's, written after the spec's closing review.

Title: `feat(commerce-api): start coupon issue admission closed, close it on a Redis failure with 503, and reopen only on restart`

````markdown
## Summary

Week 8 step 3. A coupon issue admission now stands in front of step 2's processing slots. It starts closed, closes itself when the slots can no longer be trusted, answers 503 while closed, and never reopens by itself: a restart is what opens it. Step 1's quota rules and step 2's capacity are unchanged.

- **Spec:** issue 231, with tickets 232–234, and its [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/231#issuecomment-6826398433)
- **Decision:** [ADR 0037](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/adr/0037-coupon-issue-admission-starts-closed-and-never-reopens-by-itself.md)
- **Design:** [`docs/design/coupons.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/design/coupons.md). Section 11 is this step: 11.2 the admission, 11.3 opening, 11.4 named tokens, 11.7 the limits, 11.8 the scenes and red checks, 11.10 what is left
- **Writing material:** [`docs/research/week8-coupon-admission-failure-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/coupon-admission-failure/docs/research/week8-coupon-admission-failure-writing-material.md), which holds every ticket's closing comment

```diff
 POST /api/v1/coupon-campaigns/{campaignId}/issues
   CouponIssueService.issue                      # Propagation.NEVER
     early read of the requester's coupon        # found: 200, admission not consulted
+    CouponIssueAdmission.admit {                # counts the issue as running, then reads open/closed
+                                                # closed: 503 COUPON_ISSUE_ADMISSION_CLOSED, no Redis call
-    CouponIssueSlots.acquire()                  # threw: 500
+      CouponIssueSlots.acquire()                # threw: close admission, 503, no FOR UPDATE
                                                 # null: 429 COUPON_ISSUE_CAPACITY_EXCEEDED, stays open
       CouponIssueSteps.issueUnderLock           # own TX, step 1 unchanged
-    finally CouponIssueSlots.release(token)     # threw: 500, hiding the transaction's answer
+      finally CouponIssueSlots.release(token)   # threw, or removed nothing: close admission,
+    }                                           # keep the transaction's answer (201, 409, 404)

+ at startup: CouponIssueAdmissionOpener.open()  # refused while an issue runs; SREM this instance's
+                                                # <instance-id>:* tokens; then open. Fails: stay closed, app still up
```

Tokens are now `<instance-id>:<uuid>`, with the name from `coupon-issue.instance-id` (`${HOSTNAME:}`; a blank name fails the start). Every error makes the instance admit less, never more. The one exception is two live instances sharing a name, which the deployment must prevent.

## Review Points

#### 1. Is a restart an acceptable way to open?

Nothing in a running app calls `open()`. One Redis command over 100 ms closes that instance for new issues until it restarts, and a restart opens it with nobody checking (ADR 0037, 대가). An admin endpoint would have to prove that every issue of the process has ended, which a restart gets for free.

**Would you accept a restart as the operator's "explicit resume", or does this need an endpoint?**

#### 2. Is per-instance state enough?

Open or closed lives in each process. Two instances close and open separately, so one keeps admitting while the other is closed, against the same Set (design 11.7). A shared flag in Redis is unusable when Redis is down, and one in the DB adds a read to every issue.

**Where would you keep this state once there are several instances?**

#### 3. Is closing on a single failed command too eager?

There is no retry and no threshold: one timeout closes admission, because a timed-out acquire may have reached Redis and the Set no longer matches the running issues. A circuit breaker was not chosen, since a later `PONG` proves nothing about leftover tokens.

**Is "any doubt closes it" the right reading of the assignment, or would you tolerate a few failures first?**

## Evidence

**Before:** on `week8`, a Redis failure during an issue answers 500, including after the coupon committed, and a leftover token holds a slot for good.

**After:** `CouponIssueRedisFailureApiTest` over HTTP on real MySQL, stopping and pausing the Redis the app uses (from the final check's run, `scripts/scene-lines`):

```text
Stopped Redis: new user 503 COUPON_ISSUE_ADMISSION_CLOSED with 0 FOR UPDATE; holder 200 with the same coupon, list 200; coupon rows 1 (the holder's), issued count 1
Restarted Redis: 503 during the outage, still 503 after the start with Redis answering, 201 after the opening; coupon rows 1, issued count 1
Paused Redis: new user 503 in 125 ms (bound 2000 ms), still 503 after unpausing; coupon rows 0, issued count 0
Release failed: 201 with coupon rows 1, issued count 1; after the start 1 token left in the Set and the next user 503; after the opening 0 tokens and the next user 201
Release failed after a rejection: 409 COUPON_CAMPAIGN_EXHAUSTED, and the next user 503 after the start; coupon rows 1 (the holder's), issued count 1
```

| Red check (local, never committed) | Result |
| --- | --- |
| A failed acquire does not close admission | The restarted and paused scenes: `expected: 503 but was: 201` |
| `open()` without its cleanup | The release-failed scene: the Set still holds `localhost:6097fd84-…` |
| A failed release rethrown instead of swallowed | The two release-failed scenes: `expected: 201` and `expected: 409`, both `but was: 500` |

`scripts/final-check --app commerce-api` on `073941d9`, a clean tree, with ktlint passing and only documents changed since:

| App | Classes | Tests | Failures, errors, skipped |
| --- | --- | --- | --- |
| commerce-api | 113 (109 before) | 1187 (1164 before) | 0 |

Not run: Redis down at startup over HTTP, and the script answering neither 1 nor 0 (design 11.10).

## Merge Danger

**Door:** two-way

No schema change. The new state is in memory, plus the instance name at the front of each token in `coupon-issue:slots`. A revert leaves at most a few named tokens that step 2's code treats as taken slots until the Set is cleared.

**Blast Radius:** coupon issuing

- **`HOSTNAME` is now required outside `local` and `test`.** Without it the app does not start.
- **A Redis outage now stops new issues until a restart**, even after Redis is back. Holders' repeat requests, the coupon list, orders and payments keep answering.
- **Two live instances must not share an instance name.** They would remove each other's tokens and admit past the capacity.
- **Tests:** three more Spring contexts (the closing class, the closed-from-start class, and the stoppable Redis class).
- **Out of scope:** an admin endpoint to open, state shared across instances, TTL or heartbeat reclaim, retries.
````
