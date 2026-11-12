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
