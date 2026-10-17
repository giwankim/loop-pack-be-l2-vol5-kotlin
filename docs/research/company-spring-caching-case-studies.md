# Company case studies on caching in Spring applications

Researched 2026-10-11 from first-party company engineering blogs. Bodies and publication dates were checked. These are application-specific historical accounts; their code requires version-specific verification before adoption.

## Strongest Korean company matches

### Olive Young — reducing Redis traffic without serving old promotion versions

**[고성능 캐시 아키텍처 설계 - 로컬 캐시와 Redis로 대규모 증정 행사 관리 최적화](https://oliveyoung.tech/2024-12-10/present-promotion-multi-layer-cache/)** — *Local caching and Redis for large promotions*, 2024-12-10.

- **Problem:** Caching promotion lists in ElastiCache reduced RDS reads, but large payloads and heavy traffic kept Redis network output high.
- **Decision:** Read the latest version from Redis on every request, then look up its payload in local Caffeine; misses load from Redis. Changed promotions produce new versions. This retains a small Redis round trip while avoiding repeated large payload transfers; old local entries become unused and expire.
- **Spring evidence:** `spring-boot-starter-cache`, `@EnableCaching`, `SimpleCacheManager`, `CaffeineCache`, and `@Cacheable(cacheNames = ["todayPresentCondition"], key = "#version")`.
- **Reported evidence:** With the same resources/data, their load test increased TPS by 478% and reduced Redis Network Bytes Out by 99.1%.

### KakaoPay — choosing eventual consistency and local reads

**[분산 시스템에서 로컬 캐시 활용하기](https://tech.kakaopay.com/post/local-caching-in-distributed-systems/)** — *Using local caches in a distributed system*, 2025-01-16.

- **Problem:** A telecom brokerage repeatedly reads product, carrier, and benefit metadata; separate server-local caches can diverge.
- **Decision:** Use local caches for infrequently changing reference data whose temporary inconsistency is acceptable. Redis Pub/Sub broadcasts change events that evict local entries; a one-hour TTL bounds how long entries normally remain. Redis is also used separately for dynamic/shared data. They accept possible Pub/Sub message loss because this domain tolerates it and changes are infrequent; durable messaging is a future option.
- **Spring evidence:** Their programmatic wrapper injects `SimpleCacheManager` and `RedisCacheManager`, calling Spring cache `get`/`put`/`evict`; Pub/Sub uses `StringRedisTemplate` and `RedisMessageListenerContainer`.
- **Scope:** Spring cache APIs without shown `@Cacheable`. Local → Redis → DB multi-level caching is future work, not its implemented metadata read path.

### LINE Plus / LY Corporation — cache stampedes and early refresh

**[req-shield로 캐시의 골칫거리 'Thundering Herd 문제' 쉽게 풀기!](https://techblog.lycorp.co.jp/ko/req-saver-for-thundering-herd-problem-in-cache)** — *Tackling cache stampedes with req-shield*. Metadata: 2024-05-25 KST (`2024-05-24T15:00:00Z`). **[English](https://techblog.lycorp.co.jp/en/req-saver-for-thundering-herd-problem-in-cache)**: 2024-12-17.

- **Problem:** Concurrent cache misses duplicate slow backend reads and cache writes; expiration creates recurring traffic spikes.
- **Decision:** The team's req-shield library uses local or supplied distributed locks for misses, and asynchronously refreshes still-present entries before expiry while returning their current value. This suits shared, non-personalized reads and trades freshness/lock complexity for fewer backend requests and smoother latency.
- **Spring evidence:** Custom Spring AOP modules for MVC, WebFlux, and Kotlin coroutines; `@ReqShieldCacheable`/`@ReqShieldCacheEvict`; comparisons with Spring `@Cacheable(sync = false/true)` and `RedisCacheManager` behavior.
- **Evidence:** A synthetic Boot 2.7.17 MVC benchmark reports higher throughput than either `sync` setting; this is not measured production performance.

### Woowa Brothers / Baemin — whole-cache invalidation can block requests

**[이제 Redis를 멈춰보겠습니다: @CacheEvict 파헤치기](https://techblog.woowahan.com/23138/)** — *Now let's stop Redis: investigating @CacheEvict*, 2025-11-04.

- **Problem:** `@CacheEvict(allEntries = true)` hides expensive Redis key enumeration and deletion. Their environment blocks dangerous Redis commands; changing enumeration from `KEYS` to `SCAN` still produced an API timeout with many cache entries.
- **Decision:** Avoid whole-cache eviction on the normal request path. Configure a `SCAN` batch strategy where needed and perform large cache clearing through separate batch/administrative logic. `SCAN` reduces the danger of a single blocking enumeration command, but still traverses the keyspace and takes time.
- **Spring evidence:** Spring Boot and Spring Data Redis 3.3.7, Lettuce 6.3.2; source tracing through `CacheAspectSupport`, `RedisCacheManager`, `RedisCacheWriter`, and `BatchStrategies`; explicit `@CacheEvict` investigation.
- **Scope:** Operational investigation, not a quantified outage postmortem.

### Toss Bank — consent correctness over cache availability

**[캐시를 적용하기 까지의 험난한 길 (TPS 1만 안정적으로 서비스하기)](https://toss.tech/article/34481)** — *Applying caching to serve 10,000 TPS reliably*, 2025-03-31.

- **Problem:** A rollout caused severe database read load on the terms-consent service. Stale consent could permit personal-data use after withdrawal, so they rejected replica reads with replication delay.
- **Decision:** Redis cache-aside; entity callbacks publish events and `@TransactionalEventListener(AFTER_COMMIT)` evicts after commit, avoiding their illustrated precommit eviction/repopulation race. Eviction failure cannot roll back the committed database change, so Redis errors force the circuit permanently open; reads bypass the cache. They accept database load over incorrect consent.
- **Spring evidence:** `ApplicationEventPublisher`, `@Service`, and Spring transaction events; custom `StrongConsistencyCacheService`, not shown Spring `@Cacheable`/`CacheManager`.
- **Scope:** Eviction failure had not occurred. They report operation up to 20,000 TPS, but do not establish that after-commit eviction solves every concurrent stale-fill race.

## Related English-language cases in Spring Boot applications

### Allegro — cache sizing and simplifying admission logic

**[Estimating the cache efficiency using big data](https://blog.allegro.tech/2017/01/estimating-the-cache-efficiency-using-big-data.html)**, 2017-01-26.

- **Problem:** The listings service could not cache all 50 million offers; it wanted fewer Cassandra reads without distributed-cache complexity.
- **Decision:** Analyze per-instance request popularity and time windows, then use bounded local Caffeine. Initial popularity counters consumed memory and clearing them caused hit-rate dips. A production comparison showed that removing those counters and allowing Caffeine's frequency/recency policy to retain entries simplified the algorithm, reduced memory demand, and removed the dips.
- **Spring evidence:** Explicitly a Spring Boot application. The authors deliberately rejected Spring cache annotations because methods return collections while the cache stores individual items; they use explicit Caffeine gets/puts.
- **Evidence:** Before removing counters: 200,000 cached items/instance, approximately 0.5 GB RAM, and 27% peak hit ratio.

### trivago — an optional Redis dependency caused a restart cascade

**[Proper (Java) application life cycle management in Kubernetes](https://tech.trivago.com/post/2021-06-09-application-lifecycle-management-kubernetes)**, 2021-06-09.

- **Problem:** An optional Redis cache went down. Its availability was included in the same endpoint used for liveness and readiness; all dependent pods restarted repeatedly and the search request path went offline.
- **Decision:** Separate process liveness from traffic readiness and avoid treating an optional external cache failure as a reason to restart healthy application processes.
- **Spring evidence:** Explicit Spring Boot use, built-in liveness/readiness endpoints available since Boot 2.3, and `HealthIndicator` configuration.
- **Scope:** Production incident; no shown Spring Cache annotations/manager.

## Additional verified reading

- Woowa, [Sharing one Spring cache store between single-key and multi-key access](https://techblog.woowahan.com/2601/) (2019-01-04): batch-load only missing entries; handle proxy self-invocation; Caffeine chosen over a then-buggy cache2k release.
- Woowa, [Record serialization failures with Spring Cache and Spring Data Redis](https://techblog.woowahan.com/22767/) (2025-08-19): misses worked but hits failed; custom record-aware type metadata and cache-name versioning considerations.
- Olive Young, [Why gifting was redesigned, Part 2](https://oliveyoung.tech/2025-08-04/gift-renewal-2/) (2025-08-04): custom Spring AOP/RedisTemplate caching replaces repeated annotations; Redis locks selected over early/scheduled refresh within the remaining time/risk budget to protect the backend from stampedes.
