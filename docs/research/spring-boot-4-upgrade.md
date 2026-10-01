# Spring Boot 4 upgrade: research notes

Researched 2026-10-01 against primary sources only, as input to a later decision interview and ADR. This file states facts. It does not recommend anything.

Repo state at the time of writing: branch `refactor/architecture`, `springBootVersion=3.4.4`, `kotlinVersion=2.0.20`, Spring Cloud BOM `2024.0.1`, Gradle wrapper 8.13, JDK toolchain 21 (`gradle.properties:4-11`, `gradle/wrapper/gradle-wrapper.properties:3`, `build.gradle.kts:23-33`). The upstream template's `main` branch pins the same Boot, Kotlin and Gradle versions. Its `gradle.properties` has not changed since the initial commit `6d65ce2` of 2026-08-30 (`gh api repos/loopers-labs/loop-pack-be-l2-vol5-kotlin/contents/gradle.properties`, read 2026-10-01).

Wiki pages were read from clones of the wiki git repositories on 2026-10-01. Jar and POM facts were read from Maven Central artifacts, linked inline.

## Summary

- **3.4.4 is already outside OSS support.** OSS support for the 3.4.x line ended 2025-12-31. OSS support for 3.5.x ended 2026-06-30. The OSS-supported lines today are 4.0.x (until 2026-12-31) and 4.1.x (until 2027-07-31) ([spring.io generations API][gen]).
- **Boot 4.x needs Kotlin 2.2 or later; the repo pins 2.0.20.** Boot 4.0 and 4.1 require at least Kotlin 2.2.x ([4.1 Kotlin docs][kotlin41]; [4.0 migration guide § System Requirements][mg40]). JUnit 6, which the 4.x BOMs manage, also requires Kotlin 2.2 or later ([JUnit 6.0.0 release notes][junit6]). Kotlin 2.0.20 can therefore not move to any 4.x line.
- **Managed Kotlin versions per BOM.** The 3.4.4, 3.4.13 and 3.5.16 BOMs manage Kotlin 1.9.25. The 4.0.8 BOM manages 2.2.21 and the 4.1.1 BOM manages 2.3.21 ([3.4.4][bom344], [3.4.13][bom3413], [3.5.16][bom3516], [4.0.8][bom408], [4.1.1][bom411] BOM POMs). In a Gradle build, Boot's plugin overrides the managed `kotlin.version` with the Kotlin plugin's version ([Gradle plugin docs][reacting41]). On 3.x the repo already runs 2.0.20, above the BOM's 1.9.25.
- **Spring Initializr generates only 4.x projects.** It offers Boot 4.0.8 and 4.1.1 (default 4.1.1), plus snapshots and milestones. For a Kotlin Gradle project it writes Kotlin plugin 2.2.21 (Boot 4.0.8) or 2.3.21 (Boot 4.1.1) and Gradle wrapper 9.7.1 ([metadata][init-meta]; generated `starter.zip`, see §5).
- **Boot 4 needs Gradle 8.14 or 9.x; the repo is on 8.13** ([4.0/4.1 system requirements][sysreq41]). The Kotlin plugin 2.2.21 is fully supported only up to Gradle 8.14. Kotlin plugin 2.3.21 supports Gradle 7.6.3 to 9.3.0 ([Kotlin Gradle compatibility table][kgp]).
- **Jackson 3 is the default JSON stack in Boot 4.** Packages move from `com.fasterxml.jackson` to `tools.jackson`, `Jackson2ObjectMapperBuilderCustomizer` becomes `JsonMapperBuilderCustomizer`, and `@JsonComponent` becomes `@JacksonComponent` ([4.0 migration guide § Upgrading Jackson][mg40]). In this repo, 9 Kotlin files import `com.fasterxml.jackson`: 6 in `src/main` and 3 in `src/test`. Jackson 2 support remains only as the deprecated `spring-boot-jackson2` module.
- **Spring Batch 6 behind `spring-boot-starter-batch` keeps no metadata in the database.** It runs in memory by default. JDBC metadata now needs `spring-boot-starter-batch-jdbc` ([4.0 migration guide § Spring Batch][mg40]). commerce-batch sets `spring.batch.jdbc.initialize-schema` (`apps/commerce-batch/src/main/resources/application.yml:17-18,31-32`). That property now lives only in `spring-boot-batch-jdbc` ([jar metadata][sb-batch-jdbc-jar]).
- **Testcontainers 2 renames every module** (`org.testcontainers:mysql` becomes `org.testcontainers:testcontainers-mysql`) and moves container classes ([Testcontainers 2.0.0 release][tc2]). Both 4.x BOMs manage Testcontainers 2.0.5 ([BOM POMs][bom411]). This affects 5 dependency declarations and 1 import in the repo.
- **Third-party libraries (§4).**
  - **Releases built for Boot 4, Framework 7 or JUnit 6 exist for:** springdoc 3.0.0 (Boot 4.0) and 3.1.0 (Boot 4.1); springmockk 5.x; mockito-kotlin 6.x; Instancio 6.x, which requires JUnit 6; and ArchUnit 1.5.0, through `archunit-junit6`.
  - **No release targets Boot 4 or Testcontainers 2 for:**
    - logback-slack-appender: archived, last release 2021.
    - testcontainers-redis 2.2.4: known `NoClassDefFoundError` in `toString()` on Testcontainers 2.
    - QueryDSL 5.1.0: the 4.x BOMs still manage it, upstream has published nothing on Hibernate 7, and Spring Data JPA 4.1.1 builds against it.
  - **No stable ktlint embeds Kotlin 2.3.**
  - **Unused mocking dependencies.** springmockk, mockito-core and mockito-kotlin are declared but used in 0 source files.
  - **instancio-kotlin** carries Kotlin metadata 2.4, so only Boot 4.1's managed Kotlin 2.3.21 meets the read-one-version-ahead rule.

## 1. Release and support timeline

| Line | GA (GitHub release) | OSS support ends | Commercial support ends | Latest patch on Maven Central (release date) |
| --- | --- | --- | --- | --- |
| 3.4.x | 2024-11-21 ([v3.4.0][rel-340]) | 2025-12-31 | 2026-12-31 | 3.4.13 (2025-12-18, [v3.4.13][rel-3413]) |
| 3.5.x | 2025-05-22 ([v3.5.0][rel-350]) | 2026-06-30 | 2032-06-30 | 3.5.16 (2026-06-25, [v3.5.16][rel-3516]) |
| 4.0.x | 2025-11-20 ([v4.0.0][rel-400]) | 2026-12-31 | 2027-12-31 | 4.0.8 (2026-08-21, [v4.0.8][rel-408]) |
| 4.1.x | 2026-06-10 ([v4.1.0][rel-410]) | 2027-07-31 | 2028-07-31 | 4.1.1 (2026-08-20, [v4.1.1][rel-411]) |
| 4.2.x | not GA; 4.2.0-M2 on 2026-09-24 ([v4.2.0-M2][rel-420m2]) | 2027-12-31 (planned) | 2028-12-31 (planned) | — |

- Support dates come from the spring.io generations API, which backs the support table on spring.io/projects/spring-boot ([gen]). The `initialReleaseDate` values in that API are rounded to month ends (for example, 4.1.x shows 2026-06-30). The GA column therefore uses the GitHub release dates.
- Policy: a major line is supported for at least 3 years and a minor line for at least 12 months. A new minor or major ships every six months, in May and November ([Supported-Versions wiki][sv]).
- The 3.4.4 release itself dates from 2025-03-21 ([v3.4.4][rel-344]). It is 9 patches behind 3.4.13, the last 3.4 patch, and the whole 3.4 line left OSS support on 2025-12-31. **As of 2026-10-01, 3.4.4 is out of OSS support.**
- The Maven Central version list for `spring-boot-dependencies` ends the 3.x lines at 3.4.13 and 3.5.16, and the 4.x lines at 4.0.8, 4.1.1 and 4.2.0-M2 ([maven-metadata.xml][bom-meta]). The Spring releases API reports 4.1.1 as `current` ([releases API][rels]).
- The matching Framework 7.0.x line has OSS support until 2027-07-31 and Spring Batch 6.0.x until 2027-07-31. Spring Kafka 4.0.x runs until 2026-12-31 and 4.1.x until 2027-07-31 (`https://api.spring.io/projects/{spring-framework,spring-batch,spring-kafka}/generations`).

## 2. Platform baselines for Boot 4.0 and 4.1

Versions come from the BOM POMs ([3.4.4][bom344], [3.4.13][bom3413], [3.5.16][bom3516], [4.0.8][bom408], [4.1.1][bom411]) unless another source is cited. "Min" rows come from the system-requirements and Kotlin docs pages.

| Item | 3.4.4 (repo today) | 3.5.16 | 4.0.8 | 4.1.1 |
| --- | --- | --- | --- | --- |
| Java min / max tested | 17 / 24 ([3.4 sysreq][sysreq34]) | 17 / 25 ([3.5 sysreq][sysreq35]) | 17 / 26 ([4.0 sysreq][sysreq40]) | 17 / 26 ([4.1 sysreq][sysreq41]) |
| Kotlin minimum | 1.7.x ([3.4 Kotlin docs][kotlin34]) | 1.7.x ([3.5 Kotlin docs][kotlin35]) | **2.2.x** ([4.0 Kotlin docs][kotlin40]) | **2.2.x** ([4.1 Kotlin docs][kotlin41]) |
| Kotlin (BOM `kotlin.version`) | 1.9.25 | 1.9.25 | 2.2.21 | 2.3.21 |
| kotlinx-coroutines | 1.8.1 | 1.8.1 | 1.10.2 | 1.10.2 |
| Gradle | 7.6.4+ or 8.4+ | 7.6.4+ or 8.4+ | **8.14+ or 9.x** | **8.14+ or 9.x** |
| Spring Framework | 6.2.5 | 6.2.19 | 7.0.9 | 7.0.9 |
| Servlet / Tomcat | 6.0 / 10.1.39 | 6.0 / 10.1.55 | **6.1 / 11.0.24** | **6.1 / 11.0.24** |
| Jakarta Persistence | 3.1.0 | 3.1.0 | **3.2.0** | **3.2.0** |
| Jakarta Validation / Hibernate Validator | 3.0.2 / 8.0.2 | 3.0.2 / 8.0.3 | **3.1.1 / 9.0.1** | **3.1.1 / 9.1.3** |
| Hibernate ORM | 6.6.11 | 6.6.53 | **7.2.24** | **7.4.5** |
| Jackson (`jackson-bom.version`) | 2.18.3 | 2.21.4 | **3.1.5** (`tools.jackson`) | **3.1.5** (`tools.jackson`) |
| Jackson 2 still managed? | n/a (2.x is primary) | n/a | yes, `jackson-2-bom.version` 2.21.5 | yes, 2.21.5 |
| JUnit Jupiter (`junit-bom`) | 5.11.4 | 5.12.2 | **6.0.3** | **6.0.3** |
| Mockito | 5.14.2 (repo pins 5.14.0) | 5.17.0 | 5.20.0 | 5.23.0 |
| Testcontainers | 1.20.6 | 1.21.4 | **2.0.5** | **2.0.5** |
| `com.redis:testcontainers-redis` | 2.2.4 | 2.2.4 | 2.2.4 | 2.2.4 |
| Spring Data BOM | 2024.1.4 | 2025.0.13 | 2025.1.7 | 2026.0.1 |
| Spring Kafka / kafka-clients | 3.3.4 / 3.8.1 | 3.3.16 / 3.9.2 | **4.0.7 / 4.1.2** | **4.1.1 / 4.2.1** |
| Spring Batch | 5.2.2 | 5.2.6 | **6.0.5** | **6.0.5** |
| Spring Security | 6.4.4 | 6.5.11 | 7.0.7 | 7.1.1 |
| Lettuce | 6.4.2 | 6.6.0 | 6.8.2 | 7.5.2 |
| Micrometer / Micrometer Tracing | 1.14.5 / 1.4.4 | 1.15.12 / 1.5.12 | 1.16.7 / 1.6.7 | 1.17.1 / 1.7.1 |
| HikariCP | 5.1.0 | 6.3.3 | 7.0.2 | 7.0.2 |
| MySQL Connector/J | 9.1.0 | 9.7.0 | 9.7.0 | 9.7.0 |
| Logback / SLF4J | 1.5.18 / 2.0.17 | 1.5.34 / 2.0.18 | 1.5.38 / 2.0.18 | 1.5.38 / 2.0.18 |
| QueryDSL (`querydsl.version`) | 5.1.0 | 5.1.0 | 5.1.0 | 5.1.0 |
| AssertJ | 3.26.3 | 3.27.7 | 3.27.7 | 3.27.7 |
| Spring Cloud train | 2024.0 (Moorgate) | 2025.0 (Northfields) | 2025.1 (Oakwood) | 2025.1 per Initializr (see below) |

Notes:

- **Kotlin.** The 4.x Kotlin docs recommend `-Xannotation-default-target=param-property` because Kotlin 2.2 changes annotation use-site defaulting ([4.1 Kotlin docs][kotlin41]; [Kotlin 2.2 what's new][kt22]). The 4.x docs drop the 3.x sentence saying the `kotlin-stdlib-jdk7/jdk8` variants "can also be used" ([3.4][kotlin34] vs [4.1][kotlin41]). The repo declares `kotlin-stdlib-jdk8` (`build.gradle.kts:65`).
- **Framework 7 baselines.** Spring Framework 7.0 raises its minimums to Servlet 6.1, JPA 3.2 (Hibernate 7.1/7.2), Bean Validation 3.1, Kotlin 2.2 and JUnit 6. It keeps a JDK 17 baseline ([Framework 7.0 release notes § Baseline Upgrades][fw70]).
- **Jackson 2 in Spring.** Framework 7.0 deprecates Jackson 2 support. The current plan is to "disable its auto-detection in 7.1 and remove its support entirely in 7.2" ([Framework 7.0 release notes § Jackson 3.x support][fw70]). Boot 4.0 ships Jackson 2 support "in a deprecated form" ([4.0 release notes § Deprecations][rn40]). In 4.1.1, `spring-boot-jackson2`'s `Jackson2ObjectMapperBuilderCustomizer` is `@Deprecated(since="4.0.0", forRemoval=true)` (bytecode of [spring-boot-jackson2-4.1.1.jar][sb-jackson2-jar]).
- **Other BOM data points.** The 4.0.0 BOM managed Kotlin 2.2.21, Jackson 3.0.2, JUnit 6.0.1 and Hibernate 7.1.8.Final ([4.0.0 BOM][bom400]). The 4.2.0-M2 BOM manages Kotlin 2.4.20, Jackson 3.1.6, JUnit 6.1.3, Logback 1.6.3 and Hibernate 7.4.9.Final ([4.2.0-M2 BOM][bom420m2]).
- **Spring Cloud train.** The Spring Cloud wiki maps 2025.1 (Oakwood) to Boot 4.0.x and 2025.0 to 3.5.x ([Spring Cloud Supported-Versions][scsv], snapshot of 2026-09-03). Start.spring.io's `bom-ranges` maps `spring-cloud` 2025.1.3 to "Spring Boot >=4.0.0 and <4.2.0-M1", which covers 4.1 ([actuator/info][init-info]). The latest 2025.1 release is 2025.1.3, published 2026-08-20 ([maven-metadata][sc-meta]).

## 3. Breaking changes that hit this repo

This section walks the [4.0 migration guide][mg40] in its own order, then the [4.1 release notes § Upgrading from Spring Boot 4.0][rn41]. Each item is marked **Applies**, **Applies (behavior)** or **N/A**, with evidence. Occurrence counts come from `grep` over `apps modules supports`, excluding `build/`.

### 3.1 Before you start

| Guide item | Status | Evidence |
| --- | --- | --- |
| Upgrade to the latest 3.5.x first ([mg40 § Upgrade to the Latest 3.5.x Version][mg40]) | Applies | The repo is on 3.4.4 (`gradle.properties:9`). See §5 for 3.5. |
| Explicit versions for unmanaged dependencies such as Spring Cloud ([mg40 § Review Dependencies][mg40]) | Applies | The root build imports `spring-cloud-dependencies:2024.0.1` (`build.gradle.kts:57`). No `spring-cloud-*` artifact is declared and no file imports `org.springframework.cloud` (0 occurrences). The 2024.0.1 BOM only imports `org.springframework.cloud` BOMs ([POM][sc-2024-pom]). |
| Java 17+ ([mg40 § Review System Requirements][mg40]) | OK | Toolchain 21 (`build.gradle.kts:25,31`). |
| **Kotlin 2.2+** ([mg40 § Review System Requirements][mg40]) | **Applies (blocking)** | `kotlinVersion=2.0.20` (`gradle.properties:4`). It drives every Kotlin plugin through `settings.gradle.kts:31-34`. |
| Jakarta EE 11 / Servlet 6.1 / Framework 7 ([mg40][mg40]) | Applies transitively | The repo has no direct `jakarta.servlet` imports (0) and no `javax.*` imports (0). It gets these through the starters. |
| Gradle 8.14+ or 9.x ([4.0 release notes § Gradle 9][rn40]; [sysreq][sysreq41]) | **Applies** | Wrapper is `gradle-8.13` (`gradle/wrapper/gradle-wrapper.properties:3`). |
| Deprecated 3.x APIs removed ([mg40 § Review Deprecations][mg40]) | Needs trial build | Grep found none of the APIs the guide names. See the "Testing" rows in §3.3. |

### 3.2 Modules, starters and package moves

Boot 4 splits auto-configuration into `spring-boot-<technology>` modules with root package `org.springframework.boot.<technology>`, and gives each technology a `-test` companion ([mg40 § Module Dependencies][mg40]). The guide offers `spring-boot-starter-classic` / `spring-boot-starter-test-classic` as an interim step that brings back the whole classpath ([mg40 § Classic Starters, § Migration Strategy][mg40]).

| Change | Status | Evidence in this repo |
| --- | --- | --- |
| `spring-boot-starter-web` is deprecated; use `spring-boot-starter-webmvc` ([mg40 § Deprecated Starters][mg40]) | Applies | `apps/commerce-api/build.gradle.kts:21`, `apps/commerce-streamer/build.gradle.kts:15`. The 4.1.1 `spring-boot-starter-web` POM still exists ([POM][starter-web-411]). |
| Kafka auto-configuration moved to `spring-boot-kafka` / `spring-boot-starter-kafka` ([mg40 § Starters][mg40]) | Applies | `modules/kafka/build.gradle.kts:6` declares bare `org.springframework.kafka:spring-kafka`, with no Boot Kafka starter. `KafkaConfig.kt:5` imports `org.springframework.boot.autoconfigure.kafka.KafkaProperties`. In 4.1.1 the class is `org.springframework.boot.kafka.autoconfigure.KafkaProperties` ([spring-boot-kafka-4.1.1.jar][sb-kafka-jar]). |
| `@EntityScan` moved to `org.springframework.boot.persistence.autoconfigure.EntityScan` ([mg40 § Persistence Modules][mg40]) | Applies | `modules/jpa/src/main/kotlin/com/loopers/config/jpa/JpaConfig.kt:3`. |
| Jackson starter and module coordinates | Applies | Root `build.gradle.kts:69-70` and `supports/jackson/build.gradle.kts:5-6` declare `com.fasterxml.jackson.module:jackson-module-kotlin` and `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`. Initializr 4.x generates `tools.jackson.module:jackson-module-kotlin` instead (§5). In Jackson 3, jsr310 support is built into `jackson-databind` ([Jackson 3.0 § Major changes item 6][jackson30]). |
| Test infrastructure moved into `-test` modules ([mg40 § Test Code][mg40]) | Applies | `@DataJpaTest` (6 files) is now `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` ([jar][sb-dpt-jar]). `@AutoConfigureTestDatabase` (6 files) is now `org.springframework.boot.jdbc.test.autoconfigure` ([jar][sb-jdbct-jar]). `@AutoConfigureMockMvc` (10 files) is now `org.springframework.boot.webmvc.test.autoconfigure` ([jar][sb-wmt-jar]). `@SpringBootTest` (19 files) and `@TestConfiguration` (1) stay in `org.springframework.boot.test.context` ([jar][sb-test-jar]). The 4.1.1 `spring-boot-starter-test` POM brings no slice modules ([POM][starter-test-411]). |
| Security test support needs `spring-boot-starter-security-test` ([mg40 § Test Code note][mg40]) | Applies | `apps/commerce-api/build.gradle.kts:28-29` (`spring-boot-starter-security` and `spring-security-test`, both test-only). The repo uses `SecurityMockMvcRequestPostProcessors.user/csrf` in 5 and 4 files, not `@WithMockUser` (0 occurrences). |
| Batch: in-memory `spring-boot-starter-batch` vs `spring-boot-starter-batch-jdbc` ([mg40 § Spring Batch][mg40]) | **Applies (behavior)** | `apps/commerce-batch/build.gradle.kts:14` uses `spring-boot-starter-batch`, and `application.yml:17-18,31-32` sets `spring.batch.jdbc.initialize-schema`. In 4.1.1 the `spring.batch.jdbc.*` properties are declared only by `spring-boot-batch-jdbc` ([metadata][sb-batch-jdbc-jar]). `spring-boot-batch` declares only `spring.batch.job.enabled` and `spring.batch.job.name` ([metadata][sb-batch-jar]). |
| Tracing auto-configuration is its own module ([mg40 § Modules: Micrometer Tracing Brave][mg40]) | **Applies (behavior)** | `supports/logging/build.gradle.kts:6` adds `micrometer-tracing-bridge-brave` next to the actuator starter. In 4.1.1, `BraveAutoConfiguration` sits in `spring-boot-micrometer-tracing-brave` ([jar][sb-brave-jar]), and `spring-boot-starter-actuator` does not depend on that module ([POM][starter-act-411]). The Slack appender pattern prints `%X{traceId}`/`%X{spanId}` (`supports/logging/src/main/resources/appenders/slack-appender.xml:7`). |
| Prometheus export | No change expected | `PrometheusMetricsExportAutoConfiguration` is in `spring-boot-micrometer-metrics` ([jar][sb-mm-jar]). The actuator starter brings it in through `spring-boot-starter-micrometer-metrics` ([POM][starter-act-411]). |
| Redis, data-jpa, validation, actuator starters | Same names | These starters still exist with the same names ([mg40 § Starters][mg40]). The 4.1.1 `spring-boot-starter-data-redis` POM now also brings `spring-messaging` ([POM][starter-redis-411]; [rn41 § @RedisListener][rn41]). |

### 3.3 Core, web, data, messaging, IO and testing items from the guide

| Guide item | Status | Evidence |
| --- | --- | --- |
| JSpecify nullability may break Kotlin compilation ([mg40 § JSpecify Nullability annotations][mg40]) | Applies, size unknown | Kotlin reports JSpecify nullability mismatches as errors by default ([Kotlin Java interop § JSpecify support][kt-interop]). The repo has no `org.springframework.lang` usage (0). Only a compile can show the affected call sites. |
| Logback default charset is now UTF-8 ([mg40 § Logback Default Charset][mg40]) | N/A in practice | No `charset` setting appears in `supports/logging/src/main/resources/**`. |
| BootstrapRegistry / EnvironmentPostProcessor / PropertyMapper ([mg40][mg40]) | N/A | 0 occurrences. |
| AOP starter rename, Spring Retry management removed, classic loader removed ([mg40 § Upgrading Dependencies and Build Plugins][mg40]) | N/A | No `aop` starter, no `spring-retry` or `@Retryable`, no `loaderImplementation` (0 each). |
| **Jackson 3** ([mg40 § Upgrading Jackson][mg40]) | **Applies** | See §3.4. |
| Actuator: `org.springframework.lang.Nullable` on endpoint parameters; liveness and readiness on by default ([mg40 § Upgrading Actuator][mg40]) | N/A | No custom endpoints. The repo already enables probes (`supports/monitoring/src/main/resources/monitoring.yml:15-32`). |
| `HttpMessageConverters` deprecation; forwarded headers ([mg40 § Upgrading Web Features][mg40]) | N/A | No `HttpMessageConverter` beans and no `forward-headers-strategy` (0). |
| `spring.dao.exceptiontranslation.enabled` renamed ([mg40 § Persistence Modules][mg40]) | N/A | Not set. |
| Hibernate dependency management (`hibernate-jpamodelgen` becomes `hibernate-processor`) ([mg40][mg40]) | N/A | Not used. See §3.5 for Hibernate 7 itself. |
| Kafka Streams customizer removal; retry-topic backoff property ([mg40 § Upgrading Messaging Features][mg40]) | N/A | No Kafka Streams and no retry topics. See §3.7 for Spring Kafka 4. |
| `MockitoTestExecutionListener` removed (`@Mock`/`@Captor` fields) ([mg40 § Mockito Captor and Mock Annotations][mg40]) | N/A | 0 occurrences of `@Mock`, `@Captor` or any Mockito/MockK import. |
| `@SpringBootTest` no longer provides MockMvc ([mg40 § Using MockMVC and @SpringBootTest][mg40]) | Already compliant | Each of the 10 MockMvc test classes carries `@AutoConfigureMockMvc`. |
| `@SpringBootTest` no longer provides TestRestTemplate/WebClient ([mg40][mg40]) | N/A | 0 occurrences. |
| `@PropertyMapping` relocation ([mg40][mg40]) | N/A | 0 occurrences. |
| `@MockBean` / `@SpyBean` removed ([mg40 § @MockBean and @SpyBean Removal][mg40]) | N/A | 0 occurrences. The repo declares `springmockk`, `mockito-core` and `mockito-kotlin` (`build.gradle.kts:77-79`), but no source file imports Mockito, MockK or springmockk. |
| Configuration property renames ([4.0 config changelog][cc40]; [4.1.0 config changelog][cc41]) | None found | Every `spring.*`, `server.*`, `management.*`, `logging.*` key in the repo's YAML files was checked against the "Deprecated" and "Removed" tables of both changelogs. None appears. The key `management.endpoint.health.probes.enabled`, which the repo uses, is the replacement target of the removed `management.health.probes.enabled` ([cc40][cc40]). |
| 4.1: deprecations from 4.0 removed; Derby, layertools, Maven `-DskipTests` AOT, JPA bootstrap modes, Reactor client defaults ([rn41 § Upgrading from Spring Boot 4.0][rn41]) | N/A | None of these features is used. The repo sets no `spring.data.jpa.repositories.bootstrap-mode`. |

### 3.4 Jackson 3

Boot 4 auto-configures a Jackson 3 `JsonMapper`. Defining an `ObjectMapper` bean no longer replaces it ([mg40 § Format-Specific Mappers][mg40]). Jackson 3 renames packages to `tools.jackson`, except `jackson-annotations`, which keeps `com.fasterxml.jackson.annotation` ([mg40][mg40]; [Jackson 3.0 § Major changes 1][jackson30]).

| Repo site | What changes (source) |
| --- | --- |
| `supports/jackson/.../JacksonConfig.kt:7,17`: `Jackson2ObjectMapperBuilderCustomizer` | Becomes `JsonMapperBuilderCustomizer` ([mg40][mg40]). In 4.1.1 it is `org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer` ([jar][sb-jackson-jar]). Framework 7 has no Jackson 3 equivalent of `Jackson2ObjectMapperBuilder` and recommends `JsonMapper.builder()` ([fw70 § Jackson 3.x support][fw70]). |
| `JacksonConfig.kt:24-26`: `JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT`, `IGNORE_UNKNOWN`, `WRITE_BIGDECIMAL_AS_PLAIN` | `AUTO_CLOSE_JSON_CONTENT` is renamed `AUTO_CLOSE_CONTENT` ([Jackson 3.0 § Streaming naming changes][jackson30]). All three constants exist on `tools.jackson.core.StreamWriteFeature` in 3.1.5 ([jackson-core-3.1.5.jar][jcore-jar]). |
| `JacksonConfig.kt:36,40`: `DeserializationFeature.READ_ENUMS_USING_TO_STRING`, `READ_UNKNOWN_ENUM_VALUES_AS_NULL` | In 3.1.5 these are on `tools.jackson.databind.cfg.EnumFeature`, not `DeserializationFeature` ([jackson-databind-3.1.5.jar][jdb-jar]). |
| `JacksonConfig.kt:19`: `findModulesViaServiceLoader(true)` | Boot 4 finds and registers all classpath modules by default (`spring.jackson.find-and-add-modules`, default `true`) ([mg40][mg40]; [metadata][sb-jackson-jar]). |
| `OrderCreateRequestDeserializer.kt:11,20`: `@JsonComponent` | Becomes `@JacksonComponent` (`org.springframework.boot.jackson.JacksonComponent`) ([mg40][mg40]; [jar][sb-jackson-jar]). |
| `OrderCreateRequestDeserializer.kt:21` and `StrictLongDeserializer.kt:22`: `JsonDeserializer<T>` | Renamed `ValueDeserializer` ([Jackson 3.0 § Databind naming changes, databind#3044][jackson30]). |
| `OrderCreateRequestDeserializer.kt:23`: `parser.codec.readTree(parser)` | `ObjectCodec` is removed ([Jackson 3.0 § Streaming, core#413][jackson30]). In 3.1.5, `JsonParser` has no `getCodec` and offers `readValueAsTree()`, and `DeserializationContext.readTree(JsonParser)` exists ([jars][jcore-jar]). |
| `StrictLongDeserializer.kt:24`: `p.currentToken` (Kotlin property syntax for `getCurrentToken()`) | `JsonParser` 3.1.5 has `currentToken()` and no `getCurrentToken()` ([jar][jcore-jar]). |
| `OrderCreateRequestDeserializer.kt:6,40` and `ApiControllerAdvice.kt:3,136`: `JsonMappingException` | Becomes `DatabindException`. Jackson 3 exceptions are unchecked ([Jackson 3.0 § Major changes 9][jackson30]). `InvalidFormatException` and `MismatchedInputException` keep their names under `tools.jackson.databind.exc` ([jar][jdb-jar]). |
| `PointChargeRequestBody.kt:3`: `com.fasterxml.jackson.databind.annotation.JsonDeserialize` | Becomes `tools.jackson.databind.annotation.JsonDeserialize` ([jar][jdb-jar]; databind annotations are renamed with the package per [Jackson 3.0 § Major changes 1][jackson30]). |
| `modules/kafka/.../KafkaConfig.kt:3,56`: injects a Jackson 2 `ObjectMapper` into `ByteArrayJsonMessageConverter` | Boot 4 auto-configures a Jackson 3 `JsonMapper`. A Jackson 2 `ObjectMapper` bean exists only with `spring-boot-jackson2` ([4.1 JSON docs][boot-json41]). See §3.7. |
| Tests: `Order{,Admin,Confirmation}ApiMockMvcTest.kt:3-4` inject `ObjectMapper` and use `JsonNode` | Package moves to `tools.jackson.databind` ([jackson30]). Injecting an `ObjectMapper` relies on the Jackson 3 `JsonMapper` bean (a `tools.jackson.databind.ObjectMapper` subtype, [jar][jdb-jar]). |
| Behavior: Jackson 3 default changes ([Jackson 3.0 § Databind config default changes][jackson30]) | **Applies (behavior)**. `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` now defaults to `true`, which changes JSON property order. `FAIL_ON_TRAILING_TOKENS` defaults to `true`. `WRITE_ENUMS_USING_TO_STRING` defaults to `true`, but no repo enum overrides `toString()` (0). `FAIL_ON_NULL_FOR_PRIMITIVES` and `FAIL_ON_UNKNOWN_PROPERTIES`/`FAIL_ON_EMPTY_BEANS` change defaults too, and the repo already sets these explicitly (`JacksonConfig.kt:29,37,42`). Boot offers `spring.jackson.use-jackson2-defaults=true` to approximate Boot 3 defaults ([mg40 § Jackson 2 Compatibility][mg40]; [4.1 how-to][boot-mvc41]). |

Counts: 9 files import `com.fasterxml.jackson.*`. Six are in `src/main`: `JacksonConfig.kt`, `KafkaConfig.kt`, `StrictLongDeserializer.kt`, `OrderCreateRequestDeserializer.kt`, `ApiControllerAdvice.kt` and `PointChargeRequestBody.kt`. Three are tests. Annotations from `com.fasterxml.jackson.annotation` (`JsonInclude` in `JacksonConfig.kt:3`) keep their package.

### 3.5 Hibernate ORM 7 (7.2 on Boot 4.0.8, 7.4 on Boot 4.1.1)

The repo's mapping surface: 7 `@Entity` classes and 1 `@MappedSuperclass` (`BaseEntity`). It uses `@Embeddable`/`@Embedded`, `@ManyToOne(LAZY)`, `@OneToOne(LAZY)` and `@OneToMany(mappedBy, cascade=[PERSIST])` with JPA `@OrderBy`. It uses `GenerationType.IDENTITY`. Hibernate-specific annotations are `@SQLRestriction` (`Brand.kt:12`, `Product.kt:23`) and `@Check` (`Order.kt:31`, `OrderLineItem.kt:24`). Tests use `SessionFactory`, `Statistics` and `schemaManager` (`HibernateStatistics.kt:4-5`, `OrderApiMockMvcTest.kt:20,362`).

| Hibernate 7.x change | Status | Evidence |
| --- | --- | --- |
| Removed annotations: `@Where`, Hibernate `@OrderBy`/`@Table`/`@Index`/`@ForeignKey`, `@Proxy`, `@LazyToOne` and others ([7.0 guide § Annotations][hib70]) | N/A | The repo uses `@SQLRestriction` (the named replacement for `@Where`) and the JPA `@OrderBy`/`@Index`/`@ForeignKey`. |
| `Session#save/update/saveOrUpdate/delete/load` removed ([7.0 guide § Defer to JPA][hib70]) | N/A | The repo goes through Spring Data JPA and `EntityManager` only. |
| `SchemaManager` is now also a JPA contract. Hibernate's `SchemaManager` extends it, which is a bytecode incompatibility ([7.0 guide § Jakarta Persistence 3.2][hib70]) | Possible test compile impact | `OrderApiMockMvcTest.kt:362` calls `unwrap(SessionFactory::class.java).schemaManager`. |
| Persist and flush with `cascade=PERSIST` to a detached instance now throws `EntityExistsException` ([7.0 guide § Session flush and persist][hib70]) | Needs trial run | `Order.lineItems` uses `cascade = [CascadeType.PERSIST]` (`Order.kt:50`). Whether any path attaches a detached `OrderLineItem` is not visible from a grep. |
| Stricter mapping validation (misplaced annotations, converters on incompatible attributes) ([7.0 guide § Domain Model Validations][hib70]) | Needs trial run | Hibernate validates at boot. |
| `getSingleResult()` now always throws on duplicate rows ([7.3 guide § Result deduplication][hib73]) | Boot 4.1 only | Repo queries use Spring Data or QueryDSL `fetch()`. No direct `getSingleResult`/`fetchOne` (0). |
| MySQL dialect no longer defaults `hibernate.max_fetch_depth=2` ([7.4 guide § MySQL default max fetch depth][hib74]) | Boot 4.1 only, behavior | `jpa.yml` does not set `max_fetch_depth`. |
| Limit with collection fetch now applied in SQL ([7.4 guide § Limits and fetch joins][hib74]) | N/A | The only collection fetch join, `OrderRepositoryImpl.kt:59-65`, has no limit. |
| Schema actions run even with no mapped entities ([7.3][hib73]/[7.4 guide][hib74]) | Low | commerce-batch and commerce-streamer import `jpa.yml` (`ddl-auto: create` in local and test). Neither module has an `import.sql`. |
| Native queries return `java.time` types ([7.0 guide § Temporal Types Returned by Native Queries][hib70]) | N/A | Native queries are used only for `TRUNCATE` and `SET FOREIGN_KEY_CHECKS` (`DatabaseCleanUp.kt:27-31`). |

### 3.6 Spring Batch 6 (commerce-batch)

Sources: the [Spring Batch 6.0 migration guide][batch6], plus class listings of [spring-batch-core-6.0.5][batch-core-jar], [spring-batch-infrastructure-6.0.5][batch-infra-jar] and [spring-batch-test-6.0.5][batch-test-jar].

| Repo site | Change |
| --- | --- |
| `DemoJobConfig.kt:6-7`: `org.springframework.batch.core.Job`, `Step` | Moved to `org.springframework.batch.core.job.Job` and `org.springframework.batch.core.step.Step` ([batch6 § Moved APIs][batch6]). |
| `DemoJobConfig.kt:10`: `org.springframework.batch.core.launch.support.RunIdIncrementer` | Moved to `org.springframework.batch.core.job.parameters.RunIdIncrementer` ([batch6][batch6]; [jar][batch-core-jar]). From v6, an incrementer makes the framework ignore user-supplied parameters with a warning ([batch6 § JobParametersIncrementer changes][batch6]). `DemoJobE2ETest` launches with a `requestDate` parameter. |
| `DemoJobConfig.kt:13`: `org.springframework.batch.support.transaction.ResourcelessTransactionManager` | All `spring-batch-infrastructure` APIs moved under `org.springframework.batch.infrastructure.*` ([batch6][batch6]). The class is now `org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager` ([jar][batch-infra-jar]). |
| `DemoJobConfig.kt:44`: `StepBuilder.tasklet(tasklet, txManager)` | Still present in 6.0.5 next to the new `tasklet(tasklet)`. The transaction manager is now optional ([jar][batch-core-jar]; [batch6 § default batch configuration][batch6]). |
| `DemoTasklet.kt:4,8`: `StepContribution`, `RepeatStatus` | `StepContribution` moved to `org.springframework.batch.core.step`. `RepeatStatus` is now `org.springframework.batch.infrastructure.repeat.RepeatStatus` ([batch6][batch6]; [jar][batch-infra-jar]). |
| `StepMonitorListener.kt:5-6`: `StepExecution`, `StepExecutionListener` | Moved to `org.springframework.batch.core.step` and `org.springframework.batch.core.listener` ([batch6][batch6]). `ExitStatus` stays in `org.springframework.batch.core` ([jar][batch-core-jar]). |
| `JobListener.kt:4`: `JobExecution` | Moved to `org.springframework.batch.core.job` ([batch6][batch6]). |
| `ChunkListener.kt:4-5`: `@AfterChunk` with a `ChunkContext` parameter | `@AfterChunk` is not deprecated in 6.0.5. The interface methods `ChunkListener#afterChunk(ChunkContext)` are ([batch6 § Deprecated methods][batch6]; [jar][batch-core-jar]). |
| `DemoJobE2ETest.kt:12`: `JobLauncherTestUtils` | `@Deprecated(since="6.0", forRemoval=true)` in 6.0.5, with `JobOperatorTestUtils` alongside ([jar bytecode][batch-test-jar]). `JobLauncher` itself is deprecated in favor of `JobOperator` ([batch6][batch6]). |
| `DemoJobE2ETest.kt:11`: `JobParametersBuilder` | Moved to `org.springframework.batch.core.job.parameters` ([batch6][batch6]). |
| Metadata storage | `DefaultBatchConfiguration` is now resourceless ([batch6 § default batch configuration][batch6]). Boot's plain batch starter runs in memory (§3.2). Batch 6 cannot restart failed v5 job instances, and the sequence `BATCH_JOB_SEQ` is renamed `BATCH_JOB_INSTANCE_SEQ` ([batch6 § Historical data access, § Database schema changes][batch6]). The repo creates the schema at startup in local and test only (`initialize-schema: always`); dev, qa and prd use `never`. |
| Observability | Batch 6 stops using Micrometer's global registry and needs an `ObservationRegistry` bean ([batch6 § Observability changes][batch6]). |

Summary: 6 files (5 main and 1 test) import `org.springframework.batch.*`, with 26 import lines. 11 of those imports point at moved classes.

### 3.7 Spring Kafka 4 (modules/kafka, commerce-streamer)

- Spring Kafka 4.0 moves to the Kafka 4.0 client and removes ZooKeeper support ([Spring Kafka 4.0 what's new][kafka40]). The 4.0.8 and 4.1.1 BOMs manage kafka-clients 4.1.2 and 4.2.1 (§2). The repo uses `org.testcontainers:kafka` only as a dependency (`modules/kafka/build.gradle.kts:9,11`). No test class uses it (0 imports).
- All Jackson 2 classes are deprecated and get Jackson 3 counterparts: `JacksonJsonSerializer`/`Deserializer`, the `JacksonJsonMessageConverter` family and so on ([kafka40 § Jackson 3 Support][kafka40]). In 4.1.1, `ByteArrayJsonMessageConverter` and `support.serializer.JsonSerializer` are deprecated, and `ByteArrayJacksonJsonMessageConverter` and `JacksonJsonSerializer` exist ([spring-kafka-4.1.1.jar][kafka-jar]). Repo sites: `KafkaConfig.kt:17,56-57` (`ByteArrayJsonMessageConverter(objectMapper)`) and `modules/kafka/src/main/resources/kafka.yml:16` (`value-serializer: org.springframework.kafka.support.serializer.JsonSerializer`).
- Spring Retry was dropped in favor of Framework 7 retry ([kafka40][kafka40]). The repo has no retry configuration (0).

### 3.8 Testcontainers 2

Testcontainers 2.0.0 removes JUnit 4 support, prefixes every module with `testcontainers-`, and moves container classes to `org.testcontainers.<module>` ([Testcontainers 2.0.0 release][tc2]). The 2.0.5 BOM lists `testcontainers-junit-jupiter`, `testcontainers-mysql` and `testcontainers-kafka` ([BOM][tc-bom]).

| Repo site | Change |
| --- | --- |
| `build.gradle.kts:84`: `org.testcontainers:junit-jupiter` | Becomes `org.testcontainers:testcontainers-junit-jupiter`. |
| `modules/jpa/build.gradle.kts:22,25`: `org.testcontainers:mysql` | Becomes `org.testcontainers:testcontainers-mysql`. |
| `modules/kafka/build.gradle.kts:9,11`: `org.testcontainers:kafka` | Becomes `org.testcontainers:testcontainers-kafka`. |
| `MySqlTestContainersConfig.kt:4`: `org.testcontainers.containers.MySQLContainer` | The old class is still in `testcontainers-mysql-2.0.5.jar` but marked deprecated. The new class is `org.testcontainers.mysql.MySQLContainer` ([jar][tc-mysql-jar]). |
| `modules/redis/build.gradle.kts:8`: `com.redis:testcontainers-redis` | Managed at 2.2.4 by every BOM from 3.4.4 to 4.1.1 (§2). See §4 for compatibility with Testcontainers 2. |

### 3.9 Kotlin 2.0.20 to 2.2/2.3

| Change | Status | Source / evidence |
| --- | --- | --- |
| Boot 4, Framework 7 and JUnit 6 all set Kotlin 2.2 as their minimum | **Blocking** | [kotlin41], [fw70], [junit6]. `assertThrows`/`assertAll` Kotlin helpers from `org.junit.jupiter.api` are imported in 24 test files (34 import lines). |
| Binary compatibility: a compiler reads binaries "from the next language release, but not later ones" (for example, "1.9 can understand most binaries from 2.0, but not 2.1") | Constrains library choice | [Kotlin evolution principles][kt-evo]. By that rule, 2.0.20 cannot be relied on to read 2.2-compiled metadata. |
| Kotlin plugin 2.2.20-2.2.21 is fully supported up to Gradle 8.14. Kotlin plugin 2.3.20-2.3.21 is supported on Gradle 7.6.3-9.3.0 | Constrains the Gradle version | [KGP compatibility table][kgp]. The repo today runs Kotlin plugin 2.0.20 (fully supported to 8.8) on Gradle 8.13. |
| kapt uses the K2 implementation by default from Kotlin 2.1.20 | Applies (QueryDSL uses kapt in 4 build files) | [Kotlin 2.1.20 what's new][kt2120]. Kotlin 2.2.20 deprecates `kapt.use.k2` ([Kotlin 2.2.20 what's new][kt2220]). `kapt("com.querydsl:querydsl-apt::jakarta")` appears in `apps/commerce-api/build.gradle.kts:32`, `apps/commerce-batch/build.gradle.kts:18`, `apps/commerce-streamer/build.gradle.kts:19` and `modules/jpa/build.gradle.kts:18`. |
| Annotation use-site defaulting changes in 2.2, behind the `-Xannotation-default-target` flag | Low | [Kotlin 2.2 what's new][kt22]. Every Bean Validation annotation in the repo already uses an explicit `@field:` target (13 files), and Spring injection annotations use `@param:` (`DemoTasklet.kt:17`, `DemoJobE2ETest.kt:27`). No bare constraint annotation sits on a constructor property (0). |
| `kotlinOptions {}` is an error in 2.2 | N/A | [Kotlin 2.2 what's new][kt22]. The repo uses `compilerOptions` (`build.gradle.kts:29-34`, 0 `kotlinOptions`). |

## 4. Third-party dependency compatibility

Release dates are GitHub release dates or Maven Central upload dates. "mv" is the Kotlin metadata version read with `javap` from the published jar. Kotlin guarantees that a compiler reads older binaries. It only "preferably" reads binaries from the next language release, and not later ones ([Kotlin evolution principles][kt-evo]).

### Overview

| Dependency | Repo pin | Used in source? | First release for Boot 4 / Framework 7 / JUnit 6 | Latest (date) | Flag |
| --- | --- | --- | --- | --- | --- |
| springdoc-openapi-starter-webmvc-ui | 2.7.0 | yes: 9 files import `io.swagger.v3.oas.annotations` | 3.0.0 for Boot 4.0; 3.1.0 for Boot 4.1 | 3.1.1 (2026-09-06) | none |
| springmockk | 4.0.2 | **no** (0 imports) | 5.0.0 | 5.0.1 (2025-11-30) | none (needs Kotlin 2.2) |
| mockito-core (explicit pin) | 5.14.0 | **no** | BOM-managed 5.20.0 / 5.23.0 | 5.24.0 (2026-09-23) | none |
| mockito-kotlin | 5.4.0 | **no** | 6.x | 6.4.0 (2026-09-23) | none |
| instancio-junit | 5.0.2 | yes: 2 fixture files and auto-detected `InstancioExtension` | 6.0.0, which "requires JUnit 6" | 6.1.0 (2026-09-26) | 5.x on JUnit 6 is not stated |
| instancio-kotlin | not declared | — | 6.0.0 (mv 2.4) | 6.1.0 (2026-09-26) | needs a Kotlin ≥ 2.3 compiler |
| archunit-junit5 | 1.5.0 | yes: 1 file | `archunit-junit6` 1.5.0 | 1.5.1 (2026-09-25) | junit5 artifact on JUnit 6 is not stated |
| QueryDSL `com.querydsl` (`:jakarta`, kapt) | 5.1.0 (BOM) | yes: 4 files; kapt in 4 builds | none upstream; still BOM-managed 5.1.0 in 4.x | 5.1.0 (2024-01-29) | **no upstream Hibernate 7 statement** |
| logback-slack-appender | 1.6.1 | yes: `appenders/slack-appender.xml` | none | 1.6.1 (2021-12-31) | **archived, unmaintained** |
| kotlin-logging-jvm | 8.0.4 | yes: 1 file | 8.0.4 as is | 8.0.4 (2026-05-27) | none |
| ktlint Gradle plugin / ktlint | 12.1.2 / 1.0.1 | build only | plugin 13.1.0 (Gradle 9); ktlint 1.7.0 (Kotlin 2.2) | 14.2.0 (2026-03-12) / 1.8.0 (2025-11-14) | **no stable ktlint embeds Kotlin 2.3** |
| com.redis:testcontainers-redis | 2.2.4 (BOM) | yes: `RedisTestContainersConfig.kt` | none built for Testcontainers 2 | 2.2.4 (2025-02-19) | **known `NoClassDefFoundError` on TC 2** |
| io.spring.dependency-management | 1.1.7 | build | 1.1.7 (used by Boot 4.1.1's plugin) | 1.1.7 | none |
| Spring Cloud BOM | 2024.0.1 | BOM import only, 0 artifacts | 2025.1.x | 2025.1.3 (2026-08-20) | unused import |
| jackson-module-kotlin / jackson-datatype-jsr310 | BOM | yes (§3.4) | `tools.jackson.module:jackson-module-kotlin` 3.x; jsr310 merged into databind | 3.2.3 (2026-09-22) | none |
| kotlin-test-junit5 | BOM (Kotlin) | yes (test runtime) | same artifact; no junit6 variant | follows Kotlin | none |

### springdoc-openapi

- **Compatibility matrix.** The v2 FAQ maps Boot 3.4.x to springdoc 2.7.x–2.8.x, Boot 3.5.x to 2.8.x, and Boot 4.x.x to 3.x.x ([springdoc v2 FAQ][springdoc-v2faq]). The v3 FAQ matrix lists only 4.0.x → 3.0.x ([springdoc FAQ][springdoc-faq]).
- **Parent Boot versions, from the parent POMs.** 2.7.0 builds on Boot 3.4.0. 2.8.9 builds on 3.5.0. 2.9.0 and 2.9.1 build on 3.5.16 ([2.9.1 parent POM][springdoc-291-pom]).
- **Boot 4.0.** 3.0.0 (2025-11-21) is the first release ("Upgrade to Spring Boot 4.0.0!", [v3.0.0][springdoc-300]). Its parent is Boot 4.0.0 ([POM][springdoc-300-pom]).
- **Boot 4.1.** 3.1.0 (2026-08-01) moves to Boot 4.1.0 ("Upgrade Spring Boot to version 4.1.0", [v3.1.0][springdoc-310]). Start.spring.io maps springdoc 3.1.0 to "Spring Boot >=4.0.0 and <4.2.0-M1" ([actuator/info `dependency-ranges`][init-info]).
- **Artifact name.** `org.springdoc:springdoc-openapi-starter-webmvc-ui` is unchanged in 3.x ([maven-metadata][springdoc-meta]).
- **Jackson 2.** springdoc 3.x still pulls Jackson 2 (`com.fasterxml`) through `swagger-core-jakarta` ([swagger-core-jakarta 2.2.55 POM][swagger-core-pom]). Jackson 2 therefore stays on the classpath next to Boot's Jackson 3. `spring-cloud-function-web` and `jackson-module-kotlin` are `<optional>` in `springdoc-openapi-starter-common` 3.1.1 ([POM][springdoc-common-pom]).

### springmockk

- **First Framework 7 release.** 5.0.0 (2025-11-30) is "a rewrite, based on the Spring Framework's support for @MockitoBean and @MockitoSpyBean" ([5.0.0][springmockk-500]).
- **README matrix.** "Version 5.x … compatible with Spring Framework 7, Java 17+"; "Version 4.x … compatible with Spring Boot 3.x" ([README § Versions compatibility][springmockk-readme]).
- **Migration.** `@SpykBean` becomes `@MockkSpyBean`, and `classes=` becomes `types=` ([5.0.0][springmockk-500]).
- **Requirements of 5.0.1.** It depends on mockk-jvm 1.14.6, kotlin-stdlib 2.2.21 and spring-test 7.0.1 ([5.0.1 .module][springmockk-501-module]). The jar has mv 2.2.0, so it needs Kotlin 2.2.
- **Repo.** No file imports springmockk or MockK (0). The pin `springMockkVersion=4.0.2` (`gradle.properties:14`) and `build.gradle.kts:77` declare a dependency nothing uses.

### Mockito and mockito-kotlin

- **BOM versions.** Boot 4.0.8 manages Mockito 5.20.0 and 4.1.1 manages 5.23.0 (§2). The repo pins 5.14.0 (`gradle.properties:15`), already below Boot 3.4.4's 5.14.2. Mockito 5.24.0 (2026-09-23) is the latest. There is no Mockito 6 on Central ([maven-metadata][mockito-meta]).
- **mockito-kotlin and Mockito.** mockito-kotlin 6.4.0 (2026-09-23) depends on mockito-core 5.23.0 ([POM][mk-640-pom]). 6.1.0 depends on 5.20.0.
- **mockito-kotlin and Kotlin.** 6.0.0 (2025-07-16) is "Upgrade to Kotlin 2" ([v6.0.0][mk-600]). 6.x jars have mv 2.1.0. The repo's 5.4.0 has mv 1.9.0.
- **Repo.** Neither library is imported anywhere (0); `build.gradle.kts:78-79` declare both.

### Instancio (instancio-junit, instancio-kotlin)

- **6.0.0 (2026-08-29).** The release notes say "Instancio 6.0.0 requires Java 17 as the baseline" and "instancio-junit requires JUnit 6". The same release adds `instancio-kotlin` and `instancio-bom` ([instancio-parent-6.0.0][instancio-600]).
- **JUnit target of 6.x.** The `instancio-junit` 6.1.0 POM declares junit-jupiter-api and junit-platform-commons 6.1.3 ([POM][instancio-junit-610-pom]). Both 4.x BOMs manage JUnit 6.0.3 (§2).
- **JUnit target of 5.x.** 5.x builds against JUnit 5.11.0 (5.0.2) and 5.13.4 (5.6.0, the last 5.x, 2026-05-27) ([5.6.0 parent POM][instancio-560-parent]). No official statement says 5.x runs on JUnit 6.
- **instancio-kotlin metadata, from `javap` on the `KInstancio` classes and the POM's kotlin-stdlib dependency** ([6.1.0 POM][instancio-kotlin-610-pom]):

  | Version | Date | mv | kotlin-stdlib | Class-file major |
  | --- | --- | --- | --- | --- |
  | 6.0.0-RC2 | 2026-03-16 | 2.3.0 | 2.3.10 | 52 |
  | 6.0.0-RC3 | 2026-04-22 | 2.3.0 | 2.3.20 | 52 |
  | 6.0.0-RC4 | 2026-07-07 | 2.4.0 | 2.4.0 | 61 |
  | 6.0.0 | 2026-08-29 | 2.4.0 | 2.4.10 | 61 |
  | 6.0.1 | 2026-09-18 | 2.4.0 | 2.4.20 | 61 |
  | 6.1.0 | 2026-09-26 | 2.4.0 | 2.4.20 | 61 |

- **Which compilers can read it.** By the next-release rule, the GA releases (mv 2.4) need at least a Kotlin 2.3 compiler. Kotlin 2.0.20 (repo), 1.9.25 (3.x BOMs) and 2.2.21 (Boot 4.0.8 BOM) are all below that. Kotlin 2.3.21, managed by the Boot 4.1.1 BOM, is within the rule's "best effort" range. The POM still declares stdlib 2.4.x.

### ArchUnit

- **First JUnit 6 support.** 1.5.0 (2026-08-04) adds "archunit-junit6 supports ArchUnit with JUnit 6" ([v1.5.0][archunit-150]).
- **Engine dependencies.** `archunit-junit6-engine-api` 1.5.0 depends on junit-platform-engine 6.1.2. The junit5 engine depends on 1.14.4 ([POM][archunit-j6-pom]).
- **User guide.** It maps `archunit-junit5` to JUnit 5 and `archunit-junit6` to JUnit 6 ([user guide][archunit-guide]).
- **Package names.** The junit6 API keeps package `com.tngtech.archunit.junit` (`@AnalyzeClasses`, `@ArchTest`), the package `LayeredArchitectureTest.kt:7-8` imports.
- **Java class-file support.** Java 25 arrived in 1.4.1 and Java 27 in 1.5.0 ([v1.4.1][archunit-141]; [v1.5.0][archunit-150]).
- **Latest.** 1.5.1 (2026-09-25).

### QueryDSL, kapt and KSP

- **Boot BOM.** The Boot 4.0.8, 4.1.1 and 4.2.0-M2 BOMs still manage `querydsl.version` 5.1.0 and import `querydsl-bom` ([4.1.1 BOM][bom411]).
- **Upstream `com.querydsl`.** 5.1.0 (2024-01-29) is the latest release ([maven-metadata][querydsl-meta]). The last non-dependabot commit on master is from 2025-02-01 ([commits][querydsl-commits]). Upstream has published no statement on Hibernate 7 or JPA 3.2.
- **Strongest available evidence it works.** Spring Data JPA 4.1.1 builds with `com.querydsl:querydsl-jpa:jakarta` and `querydsl-apt:jakarta` 5.1.0 against Hibernate 7.4.5.Final ([spring-data-jpa 4.1.1 POM][sdjpa-411-pom]; [spring-data-parent 4.1.1 POM][sd-parent-411-pom]).
- **Spring Data's position.**
  - The docs say "Spring Data supports the fork on a best-effort basis" ([Spring Data JPA reference § Querydsl][sdjpa-querydsl]).
  - On the closed issue "Switch to OpenFeign fork of Querydsl", a maintainer wrote on 2025-10-20 that the team treats the fork as a drop-in replacement and is "considering the impact of removing Querydsl support entirely". For now they keep the current arrangement ([spring-data-jpa issue 4041][sdjpa-4041]).
- **OpenFeign fork `io.github.openfeign.querydsl`.**
  - 7.0 (2025-06-09) moved to "JPA 3.2.0 + Hibernate 7.0" ([7.0][ofq-70]).
  - 7.7 (2026-09-23) is the latest. Its root POM sets `hibernate.version` 7.4.10.Final, `jpa.version` 3.2.0, `kotlin.version` 2.4.20 and `ksp.version` 2.3.12 ([7.7 root POM][ofq-77-pom]).
  - The fork has no `jakarta` classifier. Its processor is `querydsl-apt:<v>:jpa`.
  - `querydsl-ksp-codegen` 7.7 has mv 2.4.0 ([7.7][ofq-77]).
- **kapt.**
  - K2 kapt has been the default since Kotlin 2.1.20, with `kapt.use.k2=false` as a temporary way back ([kt2120]). The repo's Kotlin 2.0.20 still runs K1 kapt.
  - The current Kotlin docs still recommend kapt "for all Maven projects and for Gradle projects with processor libraries that haven't yet adopted KSP" ([Kotlin annotation processors][kt-ap]).
  - The kapt page carries no maintenance-mode notice ([kapt docs][kt-kapt]).
  - Whether kapt plus `querydsl-apt:jakarta` 5.1.0 generates identical Q-classes under Kotlin 2.2/2.3 is not documented. See open questions.

### logback-slack-appender (com.github.maricn)

- **Status.** The latest release is 1.6.1, uploaded 2021-12-31 ([maven-metadata][slack-meta]). The GitHub repository is archived and its README says "UNMANTAINED" [sic]. It points to a fork, `cyfrania/logback-slack-appender` ([repo][slack-repo]).
- **Build target.** 1.6.1 compiles against Logback 1.2.7, jackson-databind 2.12.5 and Java 1.7 ([1.6.1 POM][slack-161-pom]).
- **Logback in each BOM.** The 4.0.8 and 4.1.1 BOMs manage Logback 1.5.38, the same 1.5 line as Boot 3.4.4's 1.5.18. The appender runs on that line today. 4.2.0-M2 manages Logback 1.6.3 ([4.1.1 BOM][bom411]).
- **Jackson 2.** The appender uses Jackson 2's `ObjectMapper`, which stays managed through `jackson-2-bom` 2.21.5 in 4.x (§2).
- **The fork.** `com.cyfrania:logback-slack-appender` 1.2 (2024-03-04) "works with Logback 1.3.x … and 1.4.x". It has had no activity since 2024-03 ([cyfrania repo][slack-fork]).
- **Flag: no release targets Boot 4. The project is unmaintained.**

### kotlin-logging (io.github.oshai)

- **Version.** 8.0.4 (2026-05-27) is the latest, and the repo is already on it.
- **Kotlin.** It depends on kotlin-stdlib 2.1.21 and has mv 2.0.0 ([8.0.4 .module][klog-module]).
- **SLF4J.** The README says >= 5.x "can work with both slf4j 1 or 2" ([README][klog-readme]). Boot 4 manages SLF4J 2.0.18 (§2).
- **No change needed.**

### ktlint Gradle plugin and ktlint engine

- **Plugin changelog** ([CHANGELOG][ktlint-gradle-cl]):
  - 13.0.0 removed ktlint < 1.
  - 13.1.0 added "Gradle 9 Support" ([PR 937][ktlint-gradle-937]).
  - 14.0.1 added Gradle 9.1 and Java 25.
  - The latest is 14.2.0 (2026-03-12) ([plugin metadata][ktlint-gradle-meta]).
  - The repo's 12.1.2 predates Gradle 9 support.
- **Engine.** The latest stable ktlint is 1.8.0 (2025-11-14) ([1.8.0][ktlint-180]). The 2.x line moves to the `io.github.ktlint` group, and 2.0.0-ALPHA-4 is dated 2026-08-21 ([CHANGELOG][ktlint-cl]). ktlint-gradle does not support the new coordinates yet ([ktlint-gradle issue 1113][ktlint-gradle-1113]).
- **Embedded Kotlin compiler** (`kotlin-compiler-embeddable` in `ktlint-rule-engine` POMs, e.g. [1.8.0][ktlint-re-180-pom]):

  | ktlint | Embedded Kotlin |
  | --- | --- |
  | 1.0.1 (repo) | 1.9.10 |
  | 1.4.0 | 2.0.21 |
  | 1.5.0 | 2.1.0 |
  | 1.6.0 | 2.1.21 |
  | **1.7.0** (first with Kotlin 2.2) | 2.2.0 |
  | 1.8.0 (latest stable) | 2.2.21 |
  | 2.0.0-ALPHA-4 | 2.4.10 |

- **ktlint 1.7.0.** Its changelog says "With upgrade to Kotlin 2.2.0, Ktlint 1.7.0 supports context parameters" ([CHANGELOG][ktlint-cl]).
- **Flag: no stable ktlint embeds Kotlin 2.3.** The first release to go past 2.2 is 2.0.0-ALPHA-4, which embeds 2.4.10.
- **Inference, not verified by a build.** No official statement says whether ktlint 1.0.1, whose parser is Kotlin 1.9.10, handles Kotlin 2.2/2.3 syntax. The survey infers that newer syntax such as context parameters will not parse and that 1.9-level syntax will. It likewise infers from the changelog that ktlint-gradle 12.1.2 does not run on Gradle 9.

### com.redis:testcontainers-redis

- **Version.** Every BOM from 3.4.4 through 4.1.1 and 4.2.0-M2 manages 2.2.4 (`testcontainers-redis-module.version`). 2.2.4 (2025-02-19) is the latest release ([maven-metadata][tcr-meta]).
- **Build target.** 2.2.4 imports the Spring Boot 3.4.2 BOM and depends on versionless `org.testcontainers:testcontainers` ([2.2.4 POM][tcr-224-pom]). It was built against Testcontainers 1.x.
- **Known break on Testcontainers 2.** `AbstractRedisContainer.toString()` uses `org.testcontainers.shaded.org.apache.commons.lang3.ClassUtils`. The class is present in `testcontainers-1.20.6.jar` and absent from `testcontainers-2.0.5.jar`. This matches the open issue "RedisContainer.toString() causes NoClassDefFoundError" ([issue 17][tcr-17]). The other referenced classes (`GenericContainer`, `Wait`, `DockerImageName`, `MountableFile`) still exist in 2.0.5.
- **Mitigating.** Boot 4.1.1's own build uses `com.redis:testcontainers-redis` together with `testcontainers-junit-jupiter` ([spring-boot-data-redis build.gradle at v4.1.1][boot-redis-build]). The repo never calls `toString()` on the container (`RedisTestContainersConfig.kt:9-12`).
- **Flag: no release targets Testcontainers 2, and one known runtime break.**

### io.spring.dependency-management

- **Same version as today.** `spring-boot-gradle-plugin` depends on dependency-management-plugin 1.1.7 in 3.4.4, 3.5.16, 4.0.8 and 4.1.1 ([4.1.1 plugin POM][sbgp-411-pom]). 1.1.7 is also the latest release, and the repo already uses it (`gradle.properties:10`).
- **Initializr.** The 4.x Kotlin projects it generates use 1.1.7 (§5).

### Spring Cloud

- **Train for 4.x.** spring.io lists 2025.1.x (Oakwood) for Boot 4.0.x, and for 4.1.x starting with 2025.1.2 ([spring.io/projects/spring-cloud][sc-project]). That is consistent with Initializr's range (§2).
- **Repo.** Only the BOM import exists (`build.gradle.kts:57`). No `spring-cloud-*` artifact is declared and nothing is imported (§3.1).

### Jackson Kotlin and JSR-310 modules

- **jsr310 is built in.** Jackson 3 builds `jackson-datatype-jsr310`, `jdk8` and `parameter-names` into `jackson-databind` ([Jackson 3.0][jackson30]). `tools.jackson:jackson-bom` 3.1.5 marks them "Merged into jackson-databind for Jackson 3.0" ([BOM][jackson-bom-315]).
- **New Kotlin coordinate.** `tools.jackson.module:jackson-module-kotlin`. Boot 4 manages 3.1.5, and the latest release is 3.2.3 (2026-09-22).
- **Kotlin ranges in the module README** ([README § Compatibility][jmk-readme]):
  - Jackson 3.1.x and 3.2.x: Kotlin 2.1–2.3.
  - Jackson 3.3.x: Kotlin 2.2–2.4.
  - Jackson 2.21.x: Kotlin 2.1–2.3, plus Kotlin 1.9 compatibility from 2.21.2.
- **Jar metadata.** `jackson-module-kotlin` 3.1.5 has mv 2.1.0 and Java 17 bytecode. 2.21.4, the version managed by the Boot 3.5.16 BOM, has mv 1.9.0 ([2.21.4 jar][jmk-2214-jar]).
- **Boot 4 with the repo's current coordinates.** The `com.fasterxml.jackson` coordinates still resolve under Boot 4, as Jackson 2, through `jackson-2-bom` 2.21.5 (§2). Boot only auto-configures Jackson 2 through the deprecated `spring-boot-jackson2` module (§3.4).

### kotlin-test-junit5 and JUnit 6

- **No JUnit 6 artifact.** Maven Central has no `kotlin-test-junit6` ([org/jetbrains/kotlin][kotlin-group]).
- **Declared JUnit.** `kotlin-test-junit5` 2.0.20 through 2.4.20 still declares junit-jupiter 5.10.1 ([2.4.20 POM][ktj5-2420-pom]). Boot 4's BOM manages JUnit 6.0.3 over that.
- **Kotlin docs.** "Kotlin/JVM supports the latest stable JUnit version, JUnit 6", with a `kotlin("test")` plus JUnit 6.0.3 example ([Kotlin docs: test using JUnit][kt-junit]).
- **Initializr.** It still generates `kotlin-test-junit5` for Boot 4.0.8 and 4.1.1 (§5).
- **Spring Data 2025.1 (Boot 4.0).** It lists JUnit 6, Kotlin 2.2 and Hibernate 7.1/7.2 as minimums ([Spring Data 2025.1 release notes][sd-20251-rn]).

### Inferred or unsettled items (not verified by a build)

The survey left these items open. Each is an inference from primary sources, not the result of a build.

1. **Kotlin 2.0.20 under a Boot 4 BOM.** The survey inferred that the 4.x BOM would pull kotlin-stdlib 2.2.21 / 2.3.21 (metadata 2.2 / 2.3, checked with `javap`), which a 2.0.20 compiler cannot be relied on to read ([kt-evo]). This conflicts with the Boot Gradle plugin docs, which say the plugin sets `kotlin.version` to the Kotlin plugin's version ([reacting41]). In that case the stdlib would follow 2.0.20. The point is moot either way, because Boot 4 documents Kotlin 2.2 as its minimum (§2).
2. **instancio-junit 5.x on JUnit 6.** No official statement either way. 6.0.0 says only that it "requires JUnit 6" ([instancio-600]).
3. **archunit-junit5 1.5.x on a JUnit 6 platform.** Gradle would align its `junit-platform-engine` 1.14.4 to Boot's JUnit 6.0.3. The user guide maps JUnit 6 to `archunit-junit6` ([archunit-guide]).
4. **`com.querydsl` 5.1.0 with Hibernate 7.** No upstream statement. The evidence is Spring Data JPA 4.1.1's own build ([sdjpa-411-pom]).
5. **testcontainers-redis 2.2.4 on Testcontainers 2.** The `toString()` failure is verified; the other runtime paths are untested ([tcr-17]).
6. **kapt.** Whether `kapt.use.k2=false` still works on Kotlin 2.2/2.3 is not verified. Kotlin 2.2.20 deprecates the property ([kt2220]).
7. **ktlint 1.0.1 on Kotlin 2.2/2.3 syntax, and ktlint-gradle 12.1.2 on Gradle 9.** Inferred from the embedded compiler versions and the changelogs ([ktlint-cl]; [ktlint-gradle-cl]).
8. **springdoc 3.1 on Boot 4.1.** Sourced from the release notes and the parent POM only. The v3 FAQ matrix lists only 4.0.x → 3.0.x ([springdoc-310]; [springdoc-faq]).

## 5. Intermediate option: Boot 3.5.x only

What 3.4.4 to 3.5.16 changes, from the [3.5 release notes § Upgrading from Spring Boot 3.4][rn35] and the [3.5.16 BOM][bom3516]:

- **Platform.** Kotlin stays at 1.9.25 in the BOM. The repo keeps 2.0.20 through the plugin override ([reacting][reacting41]). Gradle 8.13 is within 3.5's supported "8.x (8.4 or later)" ([3.5 sysreq][sysreq35]). Java support extends to 25 ([3.5 sysreq][sysreq35]).
- **Dependency moves inside the BOM.** Framework 6.2.5 to 6.2.19. Hibernate 6.6.11 to 6.6.53. Jackson 2.18.3 to 2.21.4. The 3.5.0-3.5.12 BOMs manage Jackson 2.19.x, so Jackson moved within the 3.5 line ([3.5.0 BOM][bom350]; [3.5.12 BOM][bom3512]). JUnit 5.11.4 to 5.12.2. Mockito 5.14.2 to 5.17.0; the repo's explicit `mockitoVersion=5.14.0` would stay below the BOM. Testcontainers 1.20.6 to 1.21.4, still 1.x with no module renames. Spring Batch 5.2.2 to 5.2.6. Spring Kafka 3.3.4 to 3.3.16. kafka-clients 3.8.1 to 3.9.2. HikariCP 5.1.0 to 6.3.3. Lettuce 6.4.2 to 6.6.0.
- **Third-party libraries on 3.5.** springdoc 2.8.9 and later build on Boot 3.5.0, and 2.9.0/2.9.1 on 3.5.16. The repo's 2.7.0 builds on 3.4.0 ([springdoc v2 FAQ][springdoc-v2faq]; [2.9.1 parent POM][springdoc-291-pom]). The Jackson 2.21.4 that the 3.5.16 BOM manages has a `jackson-module-kotlin` with Kotlin metadata 1.9.0, which Kotlin 2.0.20 can read. Its README lists Kotlin 2.1–2.3, plus 1.9 compatibility from 2.21.2 ([jar][jmk-2214-jar]; [README][jmk-readme]). springmockk 4.x targets Boot 3.x ([README][springmockk-readme]). Instancio 5.x and archunit-junit5 stay on JUnit 5 (§4).
- **Spring Cloud.** The train for 3.5 is 2025.0 (Northfields) ([scsv]). Its latest release is 2025.0.3, published 2026-06-11 ([maven-metadata][sc-meta]). OSS support for Spring Cloud 2025.0.x ended 2026-06-30 (`https://api.spring.io/projects/spring-cloud/generations`).
- **3.5 upgrade items, each checked against the repo:**
  - `heapdump` access is now `NONE` by default: N/A, heapdump is not exposed (`monitoring.yml:9-14`).
  - Stricter `.enabled` values: the repo uses only `true`/`false`.
  - Profile-name validation: the repo's profiles are `local`, `test`, `dev`, `qa` and `prd`.
  - `TestRestTemplate` redirects: N/A (0 uses).
  - The `taskExecutor` alias was removed: N/A (0 lookups).
  - Redis URL and database precedence: N/A, `spring.data.redis.url` is not set.
  - Prometheus Pushgateway: N/A.
  - Bean conditions now consider generics: the repo defines `RedisTemplate<*, *>`, `KafkaTemplate<Any, Any>`, `ProducerFactory<Any, Any>` and `ConsumerFactory<Any, Any>` beans. Whether any Boot auto-configuration condition now matches differently needs a trial run.
  - Deprecations from 3.3 were removed: needs a compile.
- **3.5 configuration changelog.** None of the repo's keys appears in "Deprecated in 3.5.0" or "Removed in 3.5.0" ([3.5 config changelog][cc35]).
- **Support window.** OSS support for 3.5.x ended 2026-06-30. Commercial support runs until 2032-06-30 ([gen]). 3.5.16 (2026-06-25) is the last 3.5 version on Maven Central. Moving to 3.5 would therefore not put the repo back inside OSS support.

**Kotlin version per BOM, and what Initializr generates today** (this frames the owner's standing rule: keep `kotlinVersion=2.0.20` and follow the Boot BOM or Initializr):

| Source | Kotlin version |
| --- | --- |
| Boot 3.4.4 BOM / 3.4.13 BOM | 1.9.25 / 1.9.25 ([bom344], [bom3413]) |
| Boot 3.5.0 BOM / 3.5.16 BOM | 1.9.25 / 1.9.25 ([bom350], [bom3516]) |
| Boot 4.0.8 BOM | 2.2.21 ([bom408]) |
| Boot 4.1.1 BOM | 2.3.21 ([bom411]) |
| Boot minimum Kotlin | 3.4/3.5: 1.7.x. 4.0/4.1: 2.2.x ([kotlin34], [kotlin35], [kotlin40], [kotlin41]) |
| Effective Kotlin in this Gradle build | The Kotlin plugin version. Boot's plugin "aligns the Kotlin version used in Spring Boot's dependency management with the version of the plugin" by setting `kotlin.version` ([3.4][reacting34] and [4.1][reacting41] Gradle plugin docs). Today that is 2.0.20. |
| Spring Initializr Boot versions offered | 4.2.0-SNAPSHOT, 4.2.0-M2, 4.1.2-SNAPSHOT, **4.1.1 (default)**, 4.0.9-SNAPSHOT, 4.0.8. No 3.x version is offered ([metadata/client][init-meta]). |
| Initializr output, Kotlin + Gradle (Kotlin DSL), Java 21 | Boot 4.1.1: `kotlin("jvm") version "2.3.21"`. Boot 4.0.8: `kotlin("jvm") version "2.2.21"`. Both use `io.spring.dependency-management` 1.1.7, compiler flags `-Xjsr305=strict` and `-Xannotation-default-target=param-property`, and Gradle wrapper 9.7.1. Generated from `https://start.spring.io/starter.zip?type=gradle-project-kotlin&language=kotlin&bootVersion={4.1.1,4.0.8}&javaVersion=21&dependencies=web,data-jpa,kafka,data-redis,batch,validation,actuator,testcontainers,mysql` on 2026-10-01. |
| Initializr dependency coordinates for 4.x (same output) | `spring-boot-starter-webmvc`, `spring-boot-starter-kafka`, `tools.jackson.module:jackson-module-kotlin`, per-technology `spring-boot-starter-*-test` starters, `kotlin-test-junit5`, and `org.testcontainers:testcontainers-{junit-jupiter,kafka,mysql}`. |

Stated as facts without a conclusion: 2.0.20 already sits above every 3.x BOM's Kotlin version (1.9.25). Every BOM and Initializr template that manages Kotlin 2.x (4.0.8 and 4.1.1) manages 2.2.21 or newer. Boot 4.x documents 2.2.x as its minimum.

## 6. Benefits relevant to this repo

Only items that touch what this repo does: Kotlin, JPA, Kafka, Redis, Batch, MVC and tests.

- **Support window.** 4.1.x has OSS support until 2027-07-31 and 4.0.x until 2026-12-31. 3.4.x and 3.5.x are out of OSS support (§1).
- **JSpecify nullability.** Framework 7 and Boot 4 annotate their APIs with JSpecify ([fw70 § Null Safety][fw70]; [mg40][mg40]). Kotlin treats JSpecify as strict by default, so Spring API nullness becomes real Kotlin types instead of platform types ([kt-interop]). The repo compiles with `-Xjsr305=strict` today (`build.gradle.kts:32`). Framework 7 deprecates its JSR 305-based annotations ([fw70][fw70]).
- **Redis static master/replica auto-configuration** through `spring.data.redis.masterreplica.nodes`, Lettuce only ([rn40 § Redis Static Master/Replica][rn40]). The repo hand-builds `RedisStaticMasterReplicaConfiguration` in `modules/redis/.../RedisConfig.kt:61-77` from its own `datasource.redis.*` properties.
- **Redis observability** through `MicrometerTracing` ([rn40][rn40]). Boot 4.1 adds `@RedisListener` auto-configuration ([rn41][rn41]).
- **API versioning** for Spring MVC through `spring.mvc.apiversion.*` ([rn40 § API Versioning][rn40]; [fw70 § API Versioning][fw70]). Controllers live under `/api/v1/...` today.
- **HTTP Service Clients** auto-configuration ([rn40 § HTTP Service Clients][rn40]). The repo has no outbound HTTP client today (0 `RestTemplate`/`WebClient`).
- **RestTestClient**, `@since 7.0` ([javadoc][rtc-javadoc]). Boot 4 injects it in `@SpringBootTest` and `@AutoConfigureMockMvc` tests ([rn40 § RestTestClient][rn40]). `MockMvcTester` (AssertJ) is `@since 6.2` ([javadoc][mmt-javadoc]), so the repo can already use it on Boot 3.4. The repo uses the Kotlin `MockMvc` DSL (10 files).
- **Framework 7 resilience features**: `@Retryable`, `RetryTemplate`, `@ConcurrencyLimit` ([fw70 § Resilience features][fw70]).
- **JPA 3.2 / Hibernate 7**: `EntityManager`/`EntityManagerFactory` injectable with `@Autowired`, and `StatelessSession` support ([fw70 § JPA 3.2][fw70]). Boot 4.1 adds lazy JDBC connection fetching (`spring.datasource.connection-fetch=lazy`) and `spring.jpa.bootstrap` ([rn41][rn41]). The repo builds its own `HikariDataSource` (`DataSourceConfig.kt`), so the first property applies only to the auto-configured pool.
- **Spring Batch 6**: a new chunk-oriented step implementation, a resourceless default, and Jackson 3 execution-context serialization ([batch6][batch6]). Boot 4.1 adds Kafka listener and template observation conventions ([rn41 § Auto-configured Conventions][rn41]).
- **Kotlin 2.2/2.3 language features.** Kotlin 2.2 makes guard conditions in `when`, non-local `break`/`continue` and multi-dollar interpolation stable, and previews context parameters ([Kotlin 2.2 what's new][kt22]). Kotlin 2.3 makes nested type aliases and data-flow exhaustiveness for `when` stable and adds an unused-return-value checker ([Kotlin 2.3 what's new][kt23]).
- **Gradle 9.** Boot 4 supports it ([rn40 § Gradle 9][rn40]), as do the Kotlin plugin 2.3.x ([kgp]) and Initializr's generated wrapper (§5).
- **instancio-kotlin under Boot 4.1.** The prior finding is verified. Every published instancio-kotlin release has metadata 2.3 (RCs) or 2.4 (6.0.0 GA onward), and the GA POMs declare kotlin-stdlib 2.4.x (§4).
  - **Kotlin.** Boot 4.1.1 manages Kotlin 2.3.21, which can read 2.4 metadata under the "next language release" best-effort rule ([kt-evo]). Boot 4.0.8 manages 2.2.21, which is below it.
  - **JUnit.** `instancio-junit` 6.x "requires JUnit 6". Both 4.x BOMs manage JUnit 6.0.3. The 6.1.0 POM declares JUnit 6.1.3, and Boot's BOM overrides that version (§4).
  - **Net.** Boot 4.1 is the first Boot line whose BOM satisfies both constraints on paper. Only a build can confirm it.

## Cost inventory

| Change | Affected files / count in this repo | Source |
| --- | --- | --- |
| Bump `springBootVersion` (and the Spring Cloud BOM, or remove it) | `gradle.properties:9,11`; `build.gradle.kts:57` | [bom-meta], [scsv], [init-info] |
| Raise `kotlinVersion` to 2.2.x (4.0) or 2.3.x (4.1) | `gradle.properties:4`. It feeds 4 plugin IDs (`settings.gradle.kts:31-34`) | [kotlin41], [bom408], [bom411] |
| Gradle wrapper 8.13 to 8.14+ (Kotlin 2.2) or up to 9.3 (Kotlin 2.3) | `gradle/wrapper/gradle-wrapper.properties:3` | [sysreq41], [kgp] |
| `spring-boot-starter-web` to `spring-boot-starter-webmvc` | 2 build files | [mg40] |
| Add `spring-boot-starter-kafka` (Boot Kafka auto-configuration and `KafkaProperties`) | `modules/kafka/build.gradle.kts`; import in `KafkaConfig.kt:5` | [mg40], [sb-kafka-jar] |
| Per-technology test starters (data-jpa-test, webmvc-test, security-test, batch-test, kafka-test, …) | `build.gradle.kts:75`, `apps/commerce-api/build.gradle.kts:28-29`, `apps/commerce-batch/build.gradle.kts:15`, `modules/kafka/build.gradle.kts:8` | [mg40], Initializr output (§5) |
| Batch JDBC metadata: choose `spring-boot-starter-batch-jdbc` or the in-memory default | `apps/commerce-batch/build.gradle.kts:14`, `application.yml:14-18,26-32` | [mg40], [sb-batch-jdbc-jar] |
| Brave tracing auto-configuration module | `supports/logging/build.gradle.kts:6`; MDC use in `slack-appender.xml:7` | [mg40], [sb-brave-jar], [starter-act-411] |
| Jackson coordinates (`tools.jackson.module:jackson-module-kotlin`; drop jsr310) | `build.gradle.kts:69-70`, `supports/jackson/build.gradle.kts:5-6` | [mg40], [jackson30] |
| Jackson 3 source migration | 6 main files and 3 test files (`com.fasterxml.jackson.*` imports, excluding annotations) | §3.4 |
| Kafka Jackson 3 converter and serializer | `KafkaConfig.kt:17,56-57`, `kafka.yml:16` | [kafka40], [kafka-jar] |
| Boot package moves (`EntityScan`, `DataJpaTest`, `AutoConfigureTestDatabase`, `AutoConfigureMockMvc`, `KafkaProperties`, `JacksonComponent`, `JsonMapperBuilderCustomizer`) | 1 + 6 + 6 + 10 + 1 + 1 + 1 import sites (20 distinct files) | jar listings in §3.2/§3.4 |
| Spring Batch 6 package moves and test utilities | 5 main files and 1 test file (11 of 26 imports moved); `JobLauncherTestUtils` is deprecated for removal | [batch6], [batch-test-jar] |
| Testcontainers 2 module renames and `MySQLContainer` package | 5 dependency lines (`build.gradle.kts:84`, `modules/jpa/build.gradle.kts:22,25`, `modules/kafka/build.gradle.kts:9,11`) and 1 import (`MySqlTestContainersConfig.kt:4`) | [tc2], [tc-bom], [tc-mysql-jar] |
| Mockito/MockK/springmockk declarations: bump (springmockk 5.x, mockito-kotlin 6.x, drop the Mockito pin) or drop | 3 dependency lines and 3 version properties, with 0 usages in source | `build.gradle.kts:77-79`, `gradle.properties:14-16`; §4 |
| springdoc 2.7.0 to 3.0.x (Boot 4.0) or 3.1.x (Boot 4.1) | `gradle.properties:13`; 9 files use swagger annotations | [springdoc-300], [springdoc-310] |
| instancio-junit 5.0.2 to 6.x (JUnit 6) | `gradle.properties:17`, `build.gradle.kts:80`; 2 fixture files and the `META-INF/services` extension | [instancio-600] |
| archunit-junit5 to archunit-junit6 1.5.x | `gradle.properties:18`, `apps/commerce-api/build.gradle.kts:39`; 1 test file | [archunit-150], [archunit-guide] |
| ktlint plugin 12.1.2 / ktlint 1.0.1 to a version whose embedded Kotlin covers the new language version (ktlint ≥ 1.7.0 for Kotlin 2.2; none stable for 2.3); plugin ≥ 13.1.0 for Gradle 9 | `gradle.properties:6-7` | [ktlint-cl], [ktlint-gradle-cl] |
| logback-slack-appender: no maintained release | `supports/logging/build.gradle.kts:8`, `appenders/slack-appender.xml` | [slack-repo] |
| testcontainers-redis on Testcontainers 2: known `toString()` break | `modules/redis/build.gradle.kts:8`, `RedisTestContainersConfig.kt` | [tcr-17] |
| QueryDSL: stay on `com.querydsl` 5.1.0 (BOM-managed, untested upstream on Hibernate 7) or move to the OpenFeign fork (7.x targets Hibernate 7) | 4 kapt lines, `modules/jpa/build.gradle.kts:17`, 4 source files | [sdjpa-411-pom], [ofq-70] |
| Hibernate 7 behavior re-verification | 7 entities and 1 mapped superclass; `OrderApiMockMvcTest.kt:362` | [hib70], [hib73], [hib74] |
| Kotlin 2.2 compile fallout (JSpecify strictness, JUnit 6 Kotlin helpers) | unknown until compiled; 24 test files use JUnit Kotlin helpers | [kt-interop], [junit6] |

## Prototype results

A throwaway build spike answered questions 1–7 and 9–12 below. Questions 8 and 13 are left for the decision. The spike lives on branch `prototype/boot4` in the fork (origin) (tip `bd84e9f`), which was cut from `refactor/architecture` at `2c468a7`. Its findings are in `PROTOTYPE-boot4.md` at the branch root, and each cost category has its own commit.

Results:
- **Build:** on Boot 4.1.1, Kotlin 2.3.21 and Gradle 9.7.1, `./gradlew build --rerun-tasks` passes with ktlintCheck included: 426/426 tests, 0 failures. That count is the 419 originals plus 7 throwaway probes.
- **Size of the change:** 43 files, +152 −149, not counting the probes. No test needed a behaviour change.

The spike found four things this research did not predict:
- **ktlint:** the dependency-management plugin raises ktlint's own Kotlin compiler to Boot's managed version. That is what breaks ktlint, not Kotlin 2.3 syntax.
- **Jackson:** Jackson 2 stays on the compile classpath, because springdoc and the Slack appender depend on it. As a result, 6 of the 9 Jackson files compile against Jackson 2 and fail only at runtime.
- **`-Xjsr305=strict`:** this flag has never applied to any subproject, Boot 3.4.4 included. The root `kotlin {}` block in `build.gradle.kts:29-32` configures only the root project.
- **Request bodies:** a JSON request body with trailing content (`{…}xyz`) now gets 400 instead of 200.

## Open questions

Only a trial build or run can settle these. Primary sources do not.

1. **How large is the JSpecify fallout?** Boot 4 and Framework 7 nullability under Kotlin's strict JSpecify mode decides how many Kotlin call sites stop compiling. No source lists them.
2. **Does kapt plus `querydsl-apt:jakarta` 5.1.0 still generate Q-classes under Kotlin 2.2/2.3 (K2 kapt)?** Do those classes and `querydsl-jpa` 5.1.0 work against Hibernate 7.2/7.4 and JPA 3.2 at runtime? The Boot 4.x BOMs still manage QueryDSL 5.1.0 ([bom411]). Managed is not the same as tested. See §4.
3. **Hibernate 7 boot-time validation.** The 7.0 guide adds stricter mapping checks and changes cascade-persist semantics for detached instances ([hib70]). Whether the 7 entities and the `Order.lineItems` cascade pass is only observable at runtime.
4. **Response JSON order and shape under Jackson 3 defaults.** Examples are alphabetical property order and trailing-token failure. Does any MockMvc assertion or API client depend on them? Is `spring.jackson.use-jackson2-defaults=true` wanted?
5. **Do `traceId`/`spanId` still reach the MDC without adding `spring-boot-micrometer-tracing-brave`?** (§3.2)
6. **Do Boot 3.5's generic-aware bean conditions change which auto-configured beans back off** next to the repo's `RedisTemplate<*, *>`, `KafkaTemplate<Any, Any>` and factory beans? (§5)
7. **Gradle version choice.** Boot 4 accepts 8.14 or 9.x. The Kotlin plugin 2.2.21 is fully supported only to 8.14, and 2.3.21 to 9.3.0. Initializr generates 9.7.1, beyond both "fully supported" maxima. The latest Gradle releases are 9.8.0 (2026-09-24) and 8.14.5 ([services.gradle.org][gradle-versions]). Whether the ktlint Gradle plugin and other build plugins run on the chosen Gradle needs a build (§4).
8. **Does commerce-batch need durable job metadata in dev, qa and prd** (`initialize-schema: never` there)? This decides between the in-memory starter and `spring-boot-starter-batch-jdbc`. It also decides whether the v6 schema migration (`BATCH_JOB_SEQ` renamed `BATCH_JOB_INSTANCE_SEQ`) matters.
9. **Do instancio-junit 5.x and archunit-junit5 run on a JUnit 6.0.3 platform?** No official statement exists. Their successors target JUnit 6 explicitly (§4). Does instancio-junit 6.1.0, built against JUnit 6.1.3, run on Boot's managed 6.0.3?
10. **Does testcontainers-redis 2.2.4 work on Testcontainers 2.0.5** for the repo's start, host and port usage, beyond the known `toString()` break? (§4)
11. **Can ktlint 1.0.1 (embedded Kotlin 1.9.10) or 1.8.0 (2.2.21) parse the codebase once compiled with Kotlin 2.2/2.3?** No stable ktlint embeds Kotlin 2.3 (§4).
12. **Does `logback-slack-appender` 1.6.1 keep working under Boot 4.x?** It already runs on Logback 1.5.x today, and Jackson 2 stays BOM-managed. Boot 4.2.0-M2 moves to Logback 1.6.3 (§4).
13. **Upstream alignment.** The upstream template is on Boot 3.4.4 and Kotlin 2.0.20 with no change since 2026-08-30. Whether upstream intends to move is not recorded in any upstream file read here.

<!-- References -->
[gen]: https://api.spring.io/projects/spring-boot/generations
[rels]: https://api.spring.io/projects/spring-boot/releases
[sv]: https://github.com/spring-projects/spring-boot/wiki/Supported-Versions
[bom-meta]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml
[rel-340]: https://github.com/spring-projects/spring-boot/releases/tag/v3.4.0
[rel-344]: https://github.com/spring-projects/spring-boot/releases/tag/v3.4.4
[rel-3413]: https://github.com/spring-projects/spring-boot/releases/tag/v3.4.13
[rel-350]: https://github.com/spring-projects/spring-boot/releases/tag/v3.5.0
[rel-3516]: https://github.com/spring-projects/spring-boot/releases/tag/v3.5.16
[rel-400]: https://github.com/spring-projects/spring-boot/releases/tag/v4.0.0
[rel-408]: https://github.com/spring-projects/spring-boot/releases/tag/v4.0.8
[rel-410]: https://github.com/spring-projects/spring-boot/releases/tag/v4.1.0
[rel-411]: https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1
[rel-420m2]: https://github.com/spring-projects/spring-boot/releases/tag/v4.2.0-M2
[bom344]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.4.4/spring-boot-dependencies-3.4.4.pom
[bom3413]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.4.13/spring-boot-dependencies-3.4.13.pom
[bom350]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.0/spring-boot-dependencies-3.5.0.pom
[bom3512]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.12/spring-boot-dependencies-3.5.12.pom
[bom3516]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom
[bom408]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.8/spring-boot-dependencies-4.0.8.pom
[bom411]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
[mg40]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
[rn40]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes
[rn41]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes
[rn35]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes
[cc35]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Configuration-Changelog
[cc40]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Configuration-Changelog
[cc41]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1.0-Configuration-Changelog
[sysreq34]: https://docs.spring.io/spring-boot/3.4/system-requirements.html
[sysreq35]: https://docs.spring.io/spring-boot/3.5/system-requirements.html
[sysreq40]: https://docs.spring.io/spring-boot/4.0/system-requirements.html
[sysreq41]: https://docs.spring.io/spring-boot/4.1/system-requirements.html
[kotlin34]: https://docs.spring.io/spring-boot/3.4/reference/features/kotlin.html
[kotlin35]: https://docs.spring.io/spring-boot/3.5/reference/features/kotlin.html
[kotlin40]: https://docs.spring.io/spring-boot/4.0/reference/features/kotlin.html
[kotlin41]: https://docs.spring.io/spring-boot/4.1/reference/features/kotlin.html
[reacting34]: https://docs.spring.io/spring-boot/3.4/gradle-plugin/reacting.html
[reacting41]: https://docs.spring.io/spring-boot/4.1/gradle-plugin/reacting.html
[boot-json41]: https://docs.spring.io/spring-boot/4.1/reference/features/json.html
[boot-mvc41]: https://docs.spring.io/spring-boot/4.1/how-to/spring-mvc.html
[init-meta]: https://start.spring.io/metadata/client
[init-info]: https://start.spring.io/actuator/info
[fw70]: https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes
[batch6]: https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-6.0-Migration-Guide
[hib70]: https://docs.jboss.org/hibernate/orm/7.0/migration-guide/migration-guide.html
[hib73]: https://docs.jboss.org/hibernate/orm/7.3/migration-guide/migration-guide.html
[hib74]: https://docs.jboss.org/hibernate/orm/7.4/migration-guide/migration-guide.html
[jackson30]: https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0
[junit6]: https://docs.junit.org/6.0.0/release-notes/
[tc2]: https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0
[tc-bom]: https://repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/2.0.5/testcontainers-bom-2.0.5.pom
[tc-mysql-jar]: https://repo1.maven.org/maven2/org/testcontainers/testcontainers-mysql/2.0.5/testcontainers-mysql-2.0.5.jar
[kafka40]: https://docs.spring.io/spring-kafka/reference/4.0/whats-new.html
[kafka-jar]: https://repo1.maven.org/maven2/org/springframework/kafka/spring-kafka/4.1.1/spring-kafka-4.1.1.jar
[scsv]: https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions
[sc-meta]: https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/maven-metadata.xml
[sc-2024-pom]: https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/2024.0.1/spring-cloud-dependencies-2024.0.1.pom
[kgp]: https://kotlinlang.org/docs/gradle-configure-project.html
[kt-evo]: https://kotlinlang.org/docs/kotlin-evolution-principles.html
[kt22]: https://kotlinlang.org/docs/whatsnew22.html
[kt23]: https://kotlinlang.org/docs/whatsnew23.html
[kt2120]: https://kotlinlang.org/docs/whatsnew2120.html
[kt2220]: https://kotlinlang.org/docs/whatsnew2220.html
[kt-interop]: https://kotlinlang.org/docs/java-interop.html
[rtc-javadoc]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/web/servlet/client/RestTestClient.html
[mmt-javadoc]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/web/servlet/assertj/MockMvcTester.html
[sb-kafka-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-kafka/4.1.1/spring-boot-kafka-4.1.1.jar
[sb-jackson-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-jackson/4.1.1/spring-boot-jackson-4.1.1.jar
[sb-jackson2-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-jackson2/4.1.1/spring-boot-jackson2-4.1.1.jar
[sb-dpt-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-data-jpa-test/4.1.1/spring-boot-data-jpa-test-4.1.1.jar
[sb-jdbct-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-jdbc-test/4.1.1/spring-boot-jdbc-test-4.1.1.jar
[sb-wmt-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-webmvc-test/4.1.1/spring-boot-webmvc-test-4.1.1.jar
[sb-test-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-test/4.1.1/spring-boot-test-4.1.1.jar
[sb-batch-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-batch/4.1.1/spring-boot-batch-4.1.1.jar
[sb-batch-jdbc-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-batch-jdbc/4.1.1/spring-boot-batch-jdbc-4.1.1.jar
[sb-brave-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-micrometer-tracing-brave/4.1.1/spring-boot-micrometer-tracing-brave-4.1.1.jar
[sb-mm-jar]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-micrometer-metrics/4.1.1/spring-boot-micrometer-metrics-4.1.1.jar
[starter-web-411]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-web/4.1.1/spring-boot-starter-web-4.1.1.pom
[starter-test-411]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-test/4.1.1/spring-boot-starter-test-4.1.1.pom
[starter-act-411]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-actuator/4.1.1/spring-boot-starter-actuator-4.1.1.pom
[starter-redis-411]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-data-redis/4.1.1/spring-boot-starter-data-redis-4.1.1.pom
[jcore-jar]: https://repo1.maven.org/maven2/tools/jackson/core/jackson-core/3.1.5/jackson-core-3.1.5.jar
[jdb-jar]: https://repo1.maven.org/maven2/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5.jar
[batch-core-jar]: https://repo1.maven.org/maven2/org/springframework/batch/spring-batch-core/6.0.5/spring-batch-core-6.0.5.jar
[batch-infra-jar]: https://repo1.maven.org/maven2/org/springframework/batch/spring-batch-infrastructure/6.0.5/spring-batch-infrastructure-6.0.5.jar
[batch-test-jar]: https://repo1.maven.org/maven2/org/springframework/batch/spring-batch-test/6.0.5/spring-batch-test-6.0.5.jar
[springdoc-v2faq]: https://springdoc.org/v2/faq.html
[springdoc-faq]: https://springdoc.org/faq.html
[springdoc-291-pom]: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi/2.9.1/springdoc-openapi-2.9.1.pom
[springdoc-300]: https://github.com/springdoc/springdoc-openapi/releases/tag/v3.0.0
[springdoc-300-pom]: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi/3.0.0/springdoc-openapi-3.0.0.pom
[springdoc-310]: https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.0
[springdoc-meta]: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml
[swagger-core-pom]: https://repo1.maven.org/maven2/io/swagger/core/v3/swagger-core-jakarta/2.2.55/swagger-core-jakarta-2.2.55.pom
[springmockk-500]: https://github.com/Ninja-Squad/springmockk/releases/tag/5.0.0
[springmockk-readme]: https://github.com/Ninja-Squad/springmockk#versions-compatibility
[springmockk-501-module]: https://repo1.maven.org/maven2/com/ninja-squad/springmockk/5.0.1/springmockk-5.0.1.module
[mockito-meta]: https://repo1.maven.org/maven2/org/mockito/mockito-core/maven-metadata.xml
[mk-640-pom]: https://repo1.maven.org/maven2/org/mockito/kotlin/mockito-kotlin/6.4.0/mockito-kotlin-6.4.0.pom
[mk-600]: https://github.com/mockito/mockito-kotlin/releases/tag/v6.0.0
[instancio-600]: https://github.com/instancio/instancio/releases/tag/instancio-parent-6.0.0
[instancio-junit-610-pom]: https://repo1.maven.org/maven2/org/instancio/instancio-junit/6.1.0/instancio-junit-6.1.0.pom
[instancio-560-parent]: https://repo1.maven.org/maven2/org/instancio/instancio-parent/5.6.0/instancio-parent-5.6.0.pom
[instancio-kotlin-610-pom]: https://repo1.maven.org/maven2/org/instancio/instancio-kotlin/6.1.0/instancio-kotlin-6.1.0.pom
[archunit-150]: https://github.com/TNG/ArchUnit/releases/tag/v1.5.0
[archunit-141]: https://github.com/TNG/ArchUnit/releases/tag/v1.4.1
[archunit-j6-pom]: https://repo1.maven.org/maven2/com/tngtech/archunit/archunit-junit6-engine-api/1.5.0/archunit-junit6-engine-api-1.5.0.pom
[archunit-guide]: https://www.archunit.org/userguide/html/000_Index.html
[querydsl-meta]: https://repo1.maven.org/maven2/com/querydsl/querydsl-jpa/maven-metadata.xml
[sdjpa-411-pom]: https://repo1.maven.org/maven2/org/springframework/data/spring-data-jpa/4.1.1/spring-data-jpa-4.1.1.pom
[sd-parent-411-pom]: https://repo1.maven.org/maven2/org/springframework/data/build/spring-data-parent/4.1.1/spring-data-parent-4.1.1.pom
[sdjpa-querydsl]: https://docs.spring.io/spring-data/jpa/reference/repositories/core-extensions.html#core.extensions.querydsl
[ofq-70]: https://github.com/OpenFeign/querydsl/releases/tag/7.0
[ofq-77]: https://github.com/OpenFeign/querydsl/releases/tag/7.7
[ofq-77-pom]: https://repo1.maven.org/maven2/io/github/openfeign/querydsl/querydsl-root/7.7/querydsl-root-7.7.pom
[kt-ap]: https://kotlinlang.org/docs/jvm-annotation-processors.html
[kt-kapt]: https://kotlinlang.org/docs/kapt.html
[slack-meta]: https://repo1.maven.org/maven2/com/github/maricn/logback-slack-appender/maven-metadata.xml
[slack-repo]: https://github.com/maricn/logback-slack-appender
[slack-161-pom]: https://repo1.maven.org/maven2/com/github/maricn/logback-slack-appender/1.6.1/logback-slack-appender-1.6.1.pom
[slack-fork]: https://github.com/cyfrania/logback-slack-appender
[klog-module]: https://repo1.maven.org/maven2/io/github/oshai/kotlin-logging-jvm/8.0.4/kotlin-logging-jvm-8.0.4.module
[klog-readme]: https://github.com/oshai/kotlin-logging#readme
[ktlint-gradle-cl]: https://github.com/JLLeitschuh/ktlint-gradle/blob/main/CHANGELOG.md
[ktlint-gradle-937]: https://github.com/JLLeitschuh/ktlint-gradle/pull/937
[ktlint-gradle-meta]: https://plugins.gradle.org/m2/org/jlleitschuh/gradle/ktlint-gradle/maven-metadata.xml
[ktlint-gradle-1113]: https://github.com/JLLeitschuh/ktlint-gradle/issues/1113
[ktlint-180]: https://github.com/ktlint/ktlint/releases/tag/1.8.0
[ktlint-cl]: https://github.com/ktlint/ktlint/blob/master/CHANGELOG.md
[ktlint-re-180-pom]: https://repo1.maven.org/maven2/com/pinterest/ktlint/ktlint-rule-engine/1.8.0/ktlint-rule-engine-1.8.0.pom
[tcr-meta]: https://repo1.maven.org/maven2/com/redis/testcontainers-redis/maven-metadata.xml
[tcr-224-pom]: https://repo1.maven.org/maven2/com/redis/testcontainers-redis/2.2.4/testcontainers-redis-2.2.4.pom
[tcr-17]: https://github.com/redis-field-engineering/testcontainers-redis/issues/17
[boot-redis-build]: https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-data-redis/build.gradle
[sbgp-411-pom]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-gradle-plugin/4.1.1/spring-boot-gradle-plugin-4.1.1.pom
[sc-project]: https://spring.io/projects/spring-cloud
[jackson-bom-315]: https://repo1.maven.org/maven2/tools/jackson/jackson-bom/3.1.5/jackson-bom-3.1.5.pom
[jmk-readme]: https://github.com/FasterXML/jackson-module-kotlin#compatibility
[jmk-2214-jar]: https://repo1.maven.org/maven2/com/fasterxml/jackson/module/jackson-module-kotlin/2.21.4/jackson-module-kotlin-2.21.4.jar
[kotlin-group]: https://repo1.maven.org/maven2/org/jetbrains/kotlin/
[ktj5-2420-pom]: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-test-junit5/2.4.20/kotlin-test-junit5-2.4.20.pom
[kt-junit]: https://kotlinlang.org/docs/jvm-test-using-junit.html
[springdoc-common-pom]: https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-common/3.1.1/springdoc-openapi-starter-common-3.1.1.pom
[querydsl-commits]: https://github.com/querydsl/querydsl/commits
[sdjpa-4041]: https://github.com/spring-projects/spring-data-jpa/issues/4041
[sd-20251-rn]: https://github.com/spring-projects/spring-data-commons/wiki/Spring-Data-2025.1-Release-Notes
[gradle-versions]: https://services.gradle.org/versions/all
[bom400]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.0/spring-boot-dependencies-4.0.0.pom
[bom420m2]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.2.0-M2/spring-boot-dependencies-4.2.0-M2.pom
