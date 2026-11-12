# Week 8 coupon admission failure: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one ticket of [spec 231](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/231)'s closing comments.

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
