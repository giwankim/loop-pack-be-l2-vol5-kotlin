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
