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
