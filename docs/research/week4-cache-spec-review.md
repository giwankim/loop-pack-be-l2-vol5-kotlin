# Week 4 cache: spec review

Recorded 2026-10-16 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/127#issuecomment-6205495403) of [spec 127](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/127), the product list cache. The tickets' own material is in [week4-cache-writing-material.md](week4-cache-writing-material.md).

Spec review on 2026-10-16: `feat/cache` (HEAD e4db634) against `week4` (71c50e8, the branch's merge-base), with the spec and [ADR 0020](../adr/0020-product-list-cache-keyed-by-a-db-revision-locked-last.md). Tickets [128](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/128)–[136](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/136) are all closed.

## Result: 49 of 49 stories met

The review found 48 met, 1 partial and 0 missing. The partial story was fixed in c6915d3.

- **Story 13** ("empty pages, unknown or deleted brands and last pages cached like any other valid request") worked, since `@Cacheable` has no `unless` and only nulls are refused, but no test showed it. `ProductCacheTest` now reads an unknown brand, a deleted brand and a page past the end twice each. The second read sends 1 statement, the revision read.
  - Mutation check: with `unless = "#result.items.isEmpty()"` on `findAll`, the test fails with `expected: 1L but was: 2L`. An empty miss sends 2 statements, because the like count is skipped for an empty page.

The Implementation and Testing Decisions with no story of their own also hold at HEAD:
- key `v1:{revision}:{brandId|all}:{sort}:{page}:{size}`, TTL 5 minutes with no jitter, no `sync`;
- `immediateWrites()` with the writer exposed as a bean, and no `transactionAware()`;
- `JpaConfig` order `LOWEST_PRECEDENCE - 1`;
- the cache starter only in the version catalog and commerce-api;
- the native `@Modifying @Query` upsert, and the `beforeCommit` flush through the repository port;
- `RedisCleanUp` in the base `@AfterEach`, and the updated SQL counts;
- the ADR 0016 exception in the entity KDoc;
- `CONTEXT.md`, commerce-streamer and commerce-batch untouched.

## Fixed in this review

c6915d3 (tests, CODING_STANDARDS, [ADR 0012](../adr/0012-tests-inherit-setup-from-abstract-base-classes.md)):
- **Stale example:** the query-count example in `CODING_STANDARDS.md` asserted `2L` for `findAll(ProductListRequest())`. A miss now sends 3. Ticket 129 updated the example before ticket 130 added the revision read.
- **Field reads:** `BrandProductCacheTest`'s rename and deletion scenes, and `ProductCacheTest`'s register scene, read the `brand` field after preparing two brands. They now use the returned brands, as the Fixture rule in `CODING_STANDARDS.md` and ADR 0012 require.
- **Shared reads:** each pair or trio of copies became one helper.
  - The committed-revision read (three classes) is now `currentListRevision()` on `BaseCommittingApplicationServiceTest`.
  - The cache-key read through the production key generator (two classes) is now `currentListCacheKey(request)` there too.
  - The reconnect wait with its 30 s bound (two Redis failure classes) is now `RedisTemplate.awaitCommandSucceeds()` in `com.loopers.support`.
  - `ProductCacheTest` names every `ProductSummaryInfo` argument.
- **Ambiguous "three bases":** [CODING_STANDARDS](../../CODING_STANDARDS.md) and the base KDoc said "세 기반 모두" clear Redis. They now name `BaseApplicationServiceTest`, `BaseWebApiAdapterTest` and `BaseCommittingApplicationServiceTest`, and say that `BaseRepositoryTest` doesn't.
- **ADR 0012 dated notes:**
  - the base's `RedisCleanUp` `@AfterEach`;
  - the two new base helpers;
  - the HTTP test's `@MockkSpyBean` (`ProductCacheDatabaseFailureApiTest`).

e4db634 (domain and design docs):
- **Domain flows:** [`docs/domain/catalog.md`](../domain/catalog.md) had the mark step on the brand flows only. Product register, update, stock update and delete, like and unlike, and the deduct paragraph now say when they mark and that the upsert is the last SQL. The like rows also had "관계가 있으면 끝", which was no longer true: a no-op like still marks. The confirmation flow in [`docs/domain/points-orders.md`](../domain/points-orders.md) now has the conditional mark and the lock order order → product → point account → revision.
- **Layer table:** [catalog design](../design/catalog.md) §1's table lists only QueryDSL adapters in `adapter.persistence`. A 2026-10-16 note adds the cache config and error handler there, and their dependency on the application's `CACHE_NAME` and `ProductListInfo`.

## Recorded deviations

- **The key's `sort` part** is the request's spelling, not the `ProductSort` API value. This was already recorded in `ProductListCacheKeyGenerator`'s KDoc and catalog design 8.6. The reason holds: `ProductSort.from` accepts only exact matches, so only API spellings ever store a value.
- **Two tests look inside the cache by key,** against the Testing Decisions' "Redis 키의 철자, Spring Cache의 내부 상태 … 단언하지 않는다". In the invalidation-failure scene, the old entry left in Redis but never read is the point. In the late-refill scene, the late put landing under the old key is the point. Neither shows in a response. Both build the key with the production key generator through `currentListCacheKey`, so no test spells a key. The test KDocs now say this.
- **Stories 16 and 17:** only the product update runs while Redis is stopped (`ProductCacheRedisFailureTest`). The other writes are covered structurally: the only `@Cacheable` and the only Redis use in main code is the customer list, so no write path touches Redis (ADR 0020 rule 4).

## Declined review suggestions

- **`ProductListMarker` (brand, required) vs `ProductListChangeMarker` (product, provided):** both declare `markChanged()`. The two names are deliberate. One is the port the brand declares to break the slice cycle, the other is the product's own port, the same shape as `ProductDeleter` (catalog design 5.16, 2026-10-15 note).
- **The sold-out flip check in `updateStock` and `deduct`:** one compares a single product and the other a list of products. A shared helper would cover two three-line uses of different shapes.

## Verification

- `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:test --tests '*ProductCache*Test' --tests 'com.loopers.config.redis.RedisTimeoutTest'`: 49 tests in 10 classes, 0 failures, 0 skips. That is `ProductCacheTest` 15, `BrandProductCacheTest` 7, `LikeProductCacheTest` 6, `ProductCacheRedisFailureTest` 5, `ProductCacheRollbackTest` 2, `ProductCacheLateRefillTest` 1, `ProductCacheApiTest` 1, `ProductCacheDatabaseFailureApiTest` 1, `RedisTimeoutTest` 5 and `HexagonalArchitectureTest` 6.
- `./gradlew cleanTest check` from the root: commerce-api 574 tests in 61 classes, commerce-batch 3 and supports/jackson 2, with 0 failures, 0 errors and 0 skips. The 574 is the earlier 573 plus the new story 13 test. Redis timeouts and cache-error WARNs appeared only in `ProductCacheRedisFailureTest`, which causes them on purpose.
