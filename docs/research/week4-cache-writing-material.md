# Week 4 cache: writing material

Recorded 2026-10-15 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/136#issuecomment-6195333499) of [issue 136](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/136). This is material for the technical writing quest, not the write-up itself. The full distributions and the reasoning are in [catalog design 8.6–8.10](../design/catalog.md) and [ADR 0020](../adr/0020-product-list-cache-keyed-by-a-db-revision-locked-last.md).

Issue 136 landed on `feat/cache` as 2fd679b (test Redis), b9df841 (measurement) and 1524a7c (catalog design 8.6–8.10).

## SQL per request

Asserted with Hibernate statistics in `ProductQueryPerformanceApiTest` on the seed (12,000 products, 126,955 likes) and in `ProductCacheTest`:

- **miss: 3.** The revision read, the page query, then the like-count batch.
- **hit: 1.** The revision read only. The page query and the like count are absent from the hit's captured SQL.
- **before the cache: 2.** The page query and the like count (catalog design 8.4, 8.5).

## p50 latency (MockMvc, ms)

| request | warm-up · samples | DB only, before the cache | miss | hit |
| --- | --- | --- | --- | --- |
| brand 7 `price_asc` page 0 | 50 · 200 | 5.870 (8.5 "after") | 4.237 | 2.408 |
| whole catalog `likes_desc` page 0 | 10 · 50 | 85.210 (8.4) | 93.084 | 2.136 |

- The miss and hit columns come from one run of `./gradlew :apps:commerce-api:test --tests '*ProductQuery*Test'`. The DB-only column comes from the earlier 8.4/8.5 runs, so compare it only with itself.
- Three consecutive full-suite runs gave these p50s (miss / hit):
  - brand 7: 3.792 / 2.407, 3.804 / 2.353, 3.911 / 2.281
  - whole catalog: 88.810 / 2.010, 90.687 / 1.979, 89.370 / 2.061
- A hit skips the 91.8 ms aggregate sort inside the DB (8.4). For brand 7 the gain is small, because after the index the page query takes about 0.1 ms in the DB (8.5).
- One cached seed page of 20 items is 2,268–2,365 bytes, about 2.6 KB by `MEMORY USAGE`. So dead-key memory is at most miss rate × 300 s × 2.6 KB. At 100 misses a second that is about 79 MB.

## Scenes and how they turned out

**[130](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/130), tracer (product update):**
- The same request sent twice reads the DB once (3 statements), then the cache (1 statement), with the same page.
- After an admin price update commits, the re-read shows the new price and the new `price_asc` order. An order placed before the update keeps its amount.
- The upsert is the last statement, right after the product UPDATE.
- Two marks in one transaction send one upsert.
- The key generator and the marker both throw outside a transaction.
- Over HTTP, the hit's JSON equals the miss's JSON.

**[131](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/131), product writes and order confirmation:**
- Each of these shows on the next read:
  - a register, in the whole catalog and under its brand;
  - a delete;
  - a stock update to 0 (sold out);
  - a restock from 0 (available);
  - a confirmation that sells the last unit (sold out).
- A stock update or a confirmation that doesn't flip sold-out sends no upsert, and the next read is a hit.
- In a confirmation, the upsert is last, after the order, product and point-account UPDATEs.
- Rollback: a sold-out confirmation and a product delete that fail after their SQL is sent leave the revision unmoved. The next read is a hit with the old list.

**[132](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/132), brand writes:**
- A rename shows the new brand name on every item in the brand-filtered and whole-catalog reads.
- A deletion removes its products from both reads.
- The upsert is last: after the brand UPDATE for a rename, and after the batched product UPDATEs and the brand UPDATE for a deletion.
- A rename to the same name and a deletion with no products still raise the revision.
- A brand register keeps the cached list a hit.

**[133](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/133), likes:**
- A like shows count +1 and the new `likes_desc` order. An unlike shows count −1.
- A no-op like or unlike still raises the revision, so the next read misses.
- An unlike's `delete from likes` comes before the upsert, which is last.
- A like waits while another transaction holds the revision row, and succeeds once it is released.
- A like that can't get the row within 3 s gets 409 `CONCURRENT_REQUEST` and leaves no like row (`LockFailureApiTest`).

**[134](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/134), Redis and DB failures:**
- With Redis stopped or paused, the list answers from the DB within 2 s (3 statements) and logs one WARN each for the failed get and put.
- Invalidation failure: a product update committed while Redis was stopped logs no cache error and shows the new price during and after the outage. The restored old-revision entry is still in Redis but is never read.
- After a restart, the first read fills the cache (3 statements) and the next is a hit (1 statement).
- An unreadable cached value is a miss with a WARN.
- When the revision read fails, the list answers 500 `INTERNAL_ERROR`, even with the page cached.

**[135](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/135), late refill:**
- An old read loads from the DB and parks before its put.
- A price update from 5,000 to 1,000 commits.
- When released, the old read stores 5,000 under the old revision's key, and a new read returns 1,000.
- Mutation check: with a constant revision in the key, the final read returns 5,000.

## Test Redis `FLUSHALL` timeout

The cause is confirmed and fixed in 2fd679b.

- **Cause:** `redis:8.10` starts with save points `3600 1 300 100 60 10000`, and with save points set, every `FLUSHALL` writes the RDB file synchronously ("DB saved on disk"). I timed 300 rounds of a 3 KB SET followed by `FLUSHALL` on the server, while another container fsynced 64 MB files in a loop. `FLUSHALL` reached 151 ms, over the 100 ms command timeout. With `--save ""` it stayed under 0.4 ms and wrote nothing.
- **Reproduction:** under that load, `ProductQueryPerformanceApiTest` failed before the fix with `QueryTimeoutException: Redis command timed out` from `RedisCleanUp.truncateAll()`. After the fix, under the same load, it passed 18/18, together with `ProductCacheRedisFailureTest` 5/5.
- **Fix:**
  - The shared test container runs with `--save "" --appendonly no`.
  - `StoppableRedis` runs with the same flags plus `--shutdown-on-sigterm save`. Without that flag, a stop/start would lose the old-revision entry that the write-while-stopped scene checks.
  - The 100 ms command timeout in `redis.yml` is unchanged.

## Verification

- `./gradlew :modules:redis:ktlintCheck :apps:commerce-api:ktlintCheck :apps:commerce-api:test --tests '*ProductQuery*Test'`: 35 tests, 0 failures, 0 skips. That is `ProductQueryPerformanceApiTest` 20, `ProductQuerySeedTest` 7, `ProductQuerySeedLoadTest` 2 and `HexagonalArchitectureTest` 6.
- `--tests '*ProductCache*Test'`: 43 tests in 9 classes (`HexagonalArchitectureTest` included), 0 failures, 0 skips.
- `./gradlew cleanTest check` from the root, three consecutive runs on the final code. Each run had commerce-api 573, commerce-batch 3 and supports/jackson 2 tests, with 0 failures, 0 errors and 0 skips. Redis timeouts and cache-error WARNs appeared only in `ProductCacheRedisFailureTest`, which causes them on purpose.
