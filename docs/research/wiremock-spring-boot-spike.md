# wiremock-spring-boot as the test PG fixture on Boot 4.1.1: spike

Run on 2026-10-19 in the throwaway worktree `.claude/worktrees/agent-a85a4d9d77dd1f53a` (branch `worktree-agent-a85a4d9d77dd1f53a`, cut from `6bb6e6c`). Nothing was committed. Platform: Spring Boot 4.1.1, Spring Framework 7.0.9, JUnit 6.0.3, Kotlin 2.3.21, Java 25, Docker 29.8.2 for the existing Testcontainers suite.

Everything below was **run** unless it is marked *inference*. The `SPIKE|` lines quoted here come from the test-result XML (`apps/commerce-api/build/test-results/test/*.xml`) of the runs described.

Follow-up to [rest-client-timeouts-and-http-fixtures.md](./rest-client-timeouts-and-http-fixtures.md) §6. That section closed with "A WireMock 3.x + Boot 4.1.1 Jetty mix was not tried". This spike tried it.

## Verdict

**Viable, with caveats.** `wiremock-spring-boot` 4.4.3 works on Boot 4.1.1 and JUnit Platform 6.0.3. All eight scenes (a–h) behaved as specified, with one exception: `@InjectWireMock` on a **constructor** parameter fails in this repo (see the caveats below).

The existing commerce-api suite stays green with the new test dependencies on the classpath: 574/574 tests in every run. The web server is still Tomcat. The MVC converters and the Jackson beans are the same as on the baseline classpath.

The caveats:

1. Resolution puts the Jetty EE10 jars at 12.1.6 next to Jetty core 12.1.12. That mix ran every scene. One `mavenBom` line aligns all of Jetty at 12.1.12, and the full suite is green with it too.
2. `@InjectWireMock` on a constructor parameter collides with `spring.test.constructor.autowire.mode=all`. Field and method-parameter injection both work.
3. The new jars make a bare `RestClient.builder()` pick Apache HC5 on the test classpath. Build the request factory explicitly.
4. The WireMock server lives and dies with the Spring test context. PG state cannot survive a context restart (`@DirtiesContext`).

The hand-managed `wiremock-standalone` 3.13.2 also passed scenes b and d. A "restart the app, keep the PG" scene only works with standalone.

## 1. Coordinates that worked

| Purpose | Coordinate | Resolved |
| --- | --- | --- |
| Fixture | `org.wiremock.integrations:wiremock-spring-boot:4.4.3` | 4.4.3 is the latest on Maven Central (`<release>4.4.3</release>`, metadata `lastUpdated` 2026-10-05) |
| Test client | `org.apache.httpcomponents.client5:httpclient5` (no version) | 5.6.4 (Boot BOM) |
| Optional Jetty alignment | `dependencyManagement { imports { mavenBom("org.eclipse.jetty.ee10:jetty-ee10-bom:12.1.12") } }` in `apps/commerce-api/build.gradle.kts` | EE10 12.1.6 → 12.1.12 |
| Step 7 comparison | `org.wiremock:wiremock-standalone:3.13.2` | its own source set (see §6) |

What the published metadata declares:

- **4.4.3 POM:** `wiremock-jetty12:3.13.2` (compile). Ten Jetty artifacts at **12.1.6** (runtime): `jetty-server`, `jetty-proxy`, `jetty-alpn-{server,java-server,java-client,client}`, `jetty-ee10-{servlet,servlets,webapp}` and `jetty-http2-server`.
  - The Gradle module file (`.module`) splits these: `apiElements` carries only `wiremock-jetty12`, and `runtimeElements` adds the 12.1.6 Jetty jars.
  - It declares **no** Spring or JUnit dependency. The library compiles against them and uses the project's copies: `spring-test`, `spring-boot-test` (`TestPropertyValues`) and `junit-jupiter-api`.
- **`wiremock-jetty12:3.13.2` POM:** imports `jetty-bom` and `jetty-ee10-bom` **12.0.30**. It depends on `org.wiremock:wiremock:3.13.2` with every `org.eclipse.jetty*` exclusion.
- **`org.wiremock:wiremock:3.13.2` POM:** brings Guava 33.5.0-jre, Jackson 2 (`jackson-bom` 2.20.1), `httpclient5` 5.5.1, `json-path` 2.10.0, handlebars 4.3.1, xmlunit 2.11.0, json-unit 2.40.1, `json-schema-validator` 1.5.9, `commons-fileupload` 1.6.0 and jopt-simple 5.0.4.
- **`wiremock-standalone:3.13.2` POM:** declares **zero** dependencies.
- `wiremock-jetty12` has no release newer than 3.13.2.

Annotation attributes, checked in the 4.4.3 sources jar and used in the spike:

- `@EnableWireMock(ConfigureWireMock(name = "pg", baseUrlProperties = ["pg.base-url"]))`
- other `ConfigureWireMock` attributes: `portProperties`, `resetWireMockServer` (default `true`), `registerSpringBean` (default `false`), `configurationCustomizers`, `port` (default `0`, random), `staticPortDirtySpringContext`
- `@InjectWireMock("pg")`

## 2. Resolved versions (`testRuntimeClasspath` of `:apps:commerce-api`)

"Baseline" is HEAD without the spike dependencies. "Spike" is with `wiremock-spring-boot` and `httpclient5`. "Aligned" adds the `jetty-ee10-bom` import. "BOM" means `dependencyInsight` reported *Selected by rule*: the io.spring.dependency-management plugin applying Boot's BOM.

| Artifact(s) | Baseline | Spike | Aligned | How chosen / flag |
| --- | --- | --- | --- | --- |
| `org.eclipse.jetty:jetty-{server,util,io,http,client,proxy,security,session,xml}`, `jetty-alpn-{server,java-server,client,java-client}` | absent | **12.1.12** | 12.1.12 | **BOM forced.** Requested 12.0.30 (wiremock-jetty12) and 12.1.6 (wsb). |
| `org.eclipse.jetty.http2:jetty-http2-{server,common,hpack}` | absent | 12.1.12 | 12.1.12 | BOM forced from 12.0.30/12.1.6 |
| `org.eclipse.jetty.compression:jetty-compression-{common,gzip}` | absent | 12.1.12 | 12.1.12 | BOM (new in Jetty 12.1) |
| `org.eclipse.jetty.ee:jetty-ee-webapp` | absent | 12.1.12 | 12.1.12 | BOM forced 12.1.6 → 12.1.12 |
| `org.eclipse.jetty.ee10:jetty-ee10-{servlet,servlets,webapp}` | absent | **12.1.6** | 12.1.12 | **MIXED.** Boot manages no EE10 BOM. Conflict resolution 12.0.30 vs 12.1.6 picks 12.1.6, next to core 12.1.12. |
| `org.eclipse.jetty:jetty-bom`, `jetty.ee10:jetty-ee10-bom` | absent | 12.0.30 (platform nodes) | 12.0.30 | Imported by the wiremock-jetty12 POM. They do not win. |
| `jakarta.servlet:jakarta.servlet-api` | absent | **6.1.0** | 6.1.0 | **BOM forced 6.0.0 → 6.1.0.** EE10 is Servlet 6.0. The class is loaded from this jar, beside `tomcat-embed-core`'s own copy. |
| `com.fasterxml.jackson.core:jackson-{core,databind}`, `jackson-datatype-jsr310`, `jackson-dataformat-yaml` | 2.21.5 | 2.21.5 | 2.21.5 | BOM. **Jackson 2 was already present at baseline** (via springdoc). Not new. |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | 2.21 | 2.21 | BOM |
| `tools.jackson.core:*`, `tools.jackson.module:jackson-module-kotlin` | 3.1.5 | 3.1.5 | 3.1.5 | BOM, unchanged |
| `com.google.guava:guava` | absent | **33.5.0-jre** | same | Not managed. Also brings `failureaccess` 1.0.3, `listenablefuture` 9999.0, `j2objc-annotations` 3.1 and `error_prone_annotations` 2.41.0. |
| `org.apache.httpcomponents.client5:httpclient5` | absent | 5.6.4 | 5.6.4 | BOM (wiremock asks 5.5.1) |
| `org.apache.httpcomponents.core5:httpcore5`, `httpcore5-h2` | absent | 5.4.3 | 5.4.3 | BOM |
| `com.jayway.jsonpath:json-path` | 2.10.0 | 2.10.0 | 2.10.0 | BOM, unchanged |
| `org.wiremock:wiremock`, `wiremock-jetty12` | absent | 3.13.2 | 3.13.2 | direct requirement |
| `org.wiremock.integrations:wiremock-spring-boot` | absent | 4.4.3 | 4.4.3 | catalog |
| `org.junit.jupiter:*`, `org.junit.platform:*` | 6.0.3 | 6.0.3 | 6.0.3 | BOM, unchanged |
| others new | — | handlebars(-helpers) 4.3.1, json-unit-core 2.40.1, json-schema-validator 1.5.9 (+ `com.ethlo.time:itu` 1.14.0), commons-fileupload 1.6.0, jopt-simple 5.0.4, xmlunit-legacy/-placeholders 2.11.0 | same | not managed |
| lower requests held by the BOM | — | slf4j-api 1.7.32 → 2.0.18, hamcrest-core 2.2 → 3.0, commons-io 2.19.0 → 2.20.0 | same | BOM |

Runtime confirmation from the scenes test:

- Default resolution: `jetty.util.Jetty.VERSION=12.1.12`, `jetty-server=12.1.12`, `jetty-ee10-servlet=12.1.6`, `wiremock=3.13.2`, `jakarta.servlet.Servlet` loaded from `jakarta.servlet-api-6.1.0.jar`.
- Aligned resolution: `jetty-ee10-servlet=12.1.12`.

## 3. Scenes

The test client is HC5 behind `HttpComponentsClientHttpRequestFactory`:

- `disableAutomaticRetries()` and `disableRedirectHandling()`
- `RequestConfig.responseTimeout` 200 ms
- connect timeout 200 ms on the connection manager
- base URL from `pg.base-url`
- every call goes through `exchange { _, response -> }`

The context is a plain `@SpringJUnitConfig(PgSpikeConfig::class)` with no MySQL and no Redis. The `RestClient` bean is built from `@Value("\${pg.base-url}")`. That works because the library publishes the property before refresh.

| Scene | Observed (verbatim where quoted) | Result |
| --- | --- | --- |
| a. header + `equalToJson` | Number amount → `202 {"transactionKey":"TEST-TX-0042","status":"PENDING"}`. WireMock saw `{"orderId":"LP-ORD-0000000042","cardType":"TEST","cardNo":"0000-0000-0000-0000","amount":7000,"callbackUrl":"http://127.0.0.1:8080/api/v1/payments/callback"}` (Jackson 3 serialized the `Map`). `amount:"7000"` → **404** with WireMock's "Request was not matched" diff. `verify(exactly(2), postRequestedFor(...))`, `verify(exactly(1), ...withRequestBody(equalToJson(...)))` and `findAllUnmatchedRequests().size == 1` all hold. | pass |
| b. `Fault.EMPTY_RESPONSE` on POST | `org.springframework.web.client.ResourceAccessException` ← `org.apache.hc.core5.http.NoHttpResponseException: The target server failed to respond`. `verify(exactly(1), postRequestedFor(...))` holds. The journal holds the **full request body**, so WireMock read the body before applying the fault. | pass |
| c. GET + `withFixedDelay(1000)` | After 202–251 ms: `ResourceAccessException` ← `java.net.SocketTimeoutException: Read timed out`. `verify(exactly(1), getRequestedFor(...))` holds immediately after the throw. | pass |
| d. 503 on POST | `Observed(status=503)`, exactly 1 POST | pass |
| d. 307 + `Location: /api/v1/payments/redirected` | `Observed(status=307)`, exactly 1 POST, `verify(exactly(0), anyRequestedFor(urlEqualTo("/api/v1/payments/redirected")))` | pass |
| e. `uri { it.path(...).queryParam("orderId", ...).build() }` | 200. WireMock saw `/api/v1/payments?orderId=LP-ORD-0000000042`. `urlPathEqualTo(...).withQueryParam("orderId", equalTo(...))` matched, and `verify(exactly(1), ...)` holds. | pass |
| f. two methods, one class (`@Order` 1 then 2) | Method 1 leaves 1 stub, 1 serve event and scenario `pg=CREATED`. Method 2 sees `stubs=0 serveEvents=0 scenarios=[] sameServer=true`. **Reset is on by default**: `resetAll()` runs before each method of a class that carries the config. | pass |
| g. identical config on several classes | 6 classes share one context (same `identityHashCode`) and one server on one port, e.g. 63501. The classes: scenes, reset, A, B, a **composed annotation** (`@PgWireMockSpike` meta-annotated with `@SpringJUnitConfig` + `@EnableWireMock`), and a subclass of an **abstract base** carrying the config (ADR 0012 style). `PgSpikeConfig` built the `RestClient` once. The `WireMockContextCustomizer` is part of `MergedContextConfiguration`, so identical `ConfigureWireMock` values give the same cache key. | pass |
| g. different config (adds `portProperties = ["pg.port"]`) | New context, new server, new port (63502 vs 63501). `pg.port` was published. The `configurationCustomizers` customizer was called once. | pass |
| h. `@InjectWireMock("pg") lateinit var wireMock` | Injected. `baseUrl == "http://localhost:${wireMock.port()}"`. Java `@Target(FIELD, PARAMETER)` lands on the backing field. | pass |
| h. `@InjectWireMock` on a **method** parameter | Resolved, same instance as the field | pass |
| h. `@InjectWireMock` on a **constructor** parameter | **Fails**: `ParameterResolutionException: Discovered multiple competing ParameterResolvers ... SpringExtension, WireMockSpringJunitExtension`. `junit-platform.properties` sets `spring.test.constructor.autowire.mode=all`, so `SpringExtension` also claims the parameter. The class is kept `@Disabled` with this message. | **fails in this repo** |
| JUnit Platform 6 | `jupiter=6.0.3 platform-launcher=6.0.3 platform-commons=6.0.3`. The library's JUnit internals (`ReflectionUtils.newInstance` via `configurationCustomizers`, `AnnotationSupport`, `SpringExtension.getApplicationContext`) all ran. | pass |
| static DSL | `WireMock.stubFor(...)` / `WireMock.verify(...)` hit this server: the listener calls `WireMock.configureFor` per test. | pass |

Controls ran the same faults with HC5 defaults (retries and redirects on). They show what the two `disable*` calls buy:

| Control | Observed |
| --- | --- |
| `EMPTY_RESPONSE`, default client | **GET sent 2×**, POST sent 1× (`NoHttpResponseException` both) |
| 307 on POST, default client | Redirect **followed**: `Observed(status=201)`, 1 request to the Location |
| 503 on POST, default client, 200 ms response timeout | **1 POST** after 2–4 ms, no resend |
| 503 on POST, default client, **2 s** response timeout | **2 POSTs** after 1025 ms, both bodies 157 bytes |

The 503 rows qualify the earlier research doc's §5c claim that HC5's default resends a POST on 429/503. §5c now carries the qualification. `DefaultHttpRequestRetryStrategy.retryRequest(HttpResponse, …)` in 5.6.4 returns `false` when its default retry interval (1 s) exceeds the request's response timeout ([source](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/DefaultHttpRequestRetryStrategy.java), lines 206–212 of the 5.6.4 sources jar). So the resend happens only when the response timeout is at least 1 s. With `disableAutomaticRetries()` the point is moot.

## 4. Full suite with the new test classpath

Command: `./gradlew :apps:commerce-api:test --rerun`. The wall time is Gradle's "BUILD SUCCESSFUL in"; Docker containers were warm from earlier runs.

| Run | Tests | Failed | Skipped | Wall |
| --- | --- | --- | --- | --- |
| Baseline: spike deps removed (574 existing + 1 WireMock-free probe) | 575 | 0 | 0 | 2m 12s |
| + `wiremock-spring-boot` + `httpclient5`, default resolution (EE10 12.1.6) | 599 (574 + 25 spike) | 0 | 1 (the disabled constructor probe) | 2m 9s |
| + EE10 BOM alignment | 599 | 0 | 1 | 1m 54s |

The final spike-only run, with two more controls, has 26 spike tests: 25 pass and 1 is skipped. Wall time does not change in any meaningful way.

`AppWiringProbeTest` is `@SpringBootTest(RANDOM_PORT)` on the real app with MySQL and Redis. It printed the same three lines on the baseline and on both spike classpaths:

```
SPIKE| app: webServer=org.springframework.boot.tomcat.TomcatWebServer
SPIKE| app: mvc converters=[ProjectingJacksonHttpMessageConverter, ByteArrayHttpMessageConverter, StringHttpMessageConverter, ResourceHttpMessageConverter, ResourceRegionHttpMessageConverter, AllEncompassingFormHttpMessageConverter, JacksonJsonHttpMessageConverter, MappingJackson2YamlHttpMessageConverter, Jaxb2RootElementHttpMessageConverter]
SPIKE| app: Jackson3 ObjectMapper beans=[jacksonJsonMapper] Jackson2 ObjectMapper beans=[]
```

Jetty jars do not switch the server. Boot 4's Jetty web server lives in `spring-boot-jetty`, which is absent: `org.springframework.boot.jetty.JettyWebServer` is not on the classpath. The `MappingJackson2YamlHttpMessageConverter` was already there at baseline, because `jackson-dataformat-yaml` 2.x is present before the spike.

The library registers a `ContextCustomizerFactory` and a `TestExecutionListener` in `spring.factories` for **every** test in the module.

- For classes without the annotation, the factory returns `null`, so cache keys do not change.
- The listener returns early on the reset path, but it still scans each test instance for `@InjectWireMock` fields.
- The unchanged pass count is the evidence that this is harmless.

**First-attempt failure (my spike bug, worth knowing):** the first full run failed 368 of 580 tests. The cause was `PlaceholderResolutionException: Could not resolve placeholder 'pg.base-url'`.

- The spike's `PgSpikeConfig` was a plain `@Configuration` under `com.loopers`, so `@SpringBootApplication` scanning picked it up in every `@SpringBootTest` context.
- Switching it to `@TestConfiguration` fixed it, because `TestTypeExcludeFilter` keeps it out of scanning. `AdminSecurityConfig` and `SqlCaptureConfig` already do this.
- A real PG test config must follow the same rule.

## 5. Lifecycle

- **The server belongs to the Spring test context.** `WireMockContextCustomizer.customizeContext` starts the server before refresh, publishes the properties, and registers a `ContextClosedEvent` listener plus a disposable bean that both call `stop()`.
  - Observed: after `@DirtiesContext(AFTER_METHOD)` the old server was `running=false`.
  - The next method got a new server on a new port (63504 → 63509) with no stubs: 404 "No response could be served as there are no stub mappings".
  - The old base URL refused connections (`HttpHostConnectException`).
  - *Inference:* the same happens when Spring's context cache (default size 32) evicts the context, and at JVM shutdown.
- **Recreating clients or services inside one context keeps PG state.** In one method, a scenario moved `STARTED → CREATED` on a POST from a first HC5 client, and that client was then closed. After that, GETs from these all returned `200 {"status":"SUCCESS"}`:
  - a brand-new HC5 client and `RestClient`;
  - a whole new `AnnotationConfigApplicationContext(PgSpikeConfig)` pointed at the same `pg.base-url`.

  Closing that second app context did not stop the server (`running after that context closed=true`).
- **Across test methods,** state survives only with `resetWireMockServer = false`: the next method still saw `SUCCESS` and 4 serve events. That switch applies to every class sharing the context, so stubs and the journal then leak between classes.
- **Across a context restart,** PG state cannot survive with this library. A "restart" scene that must keep PG state through an app-context restart (rather than recreating beans inside one context) needs a server outside the context. That is the standalone pattern in §6. *Inference:* a static `port = …` keeps the URL stable, but the server is still recreated empty, and a static port marks the context dirty after each class by default (`staticPortDirtySpringContext = true`, read in source).
- **Shutdown noise:** when a test's delayed response (`withFixedDelay(1000)`) is still sleeping as the JVM's context shutdown hook stops Jetty, Jetty logs `WARN ... writeError: status=500, message=java.nio.channels.WritePendingException` from `NotFoundHandler`. Seen once, when the scenes class ran alone so its timeout scene was near the end. The build stayed green. Keeping fixed delays only modestly above the client timeout (e.g. 500 ms against 200 ms) shortens the window.

## 6. Comparison: `wiremock-standalone` 3.13.2 with a hand-written lifecycle

The standalone and non-shaded artifacts cannot share one test classpath. *Inference from packaging:* both ship WireMock's own `com.github.tomakehurst.wiremock` classes unrelocated. So the comparison ran in a separate source set, `src/spikeStandalone`, with a `spikeStandaloneTest` task.

The setup:

- The `WireMockServer(options().dynamicPort())` lives in the test's companion object, outside any Spring context: `@BeforeAll start()`, `@AfterAll stop()`.
- `@DynamicPropertySource` supplies `pg.base-url`.
- `@BeforeEach` calls `resetAll()`.

All 5 tests pass:

| Check | Observed |
| --- | --- |
| classpath | `shaded Jetty=11.0.26`; no unshaded `org.eclipse.jetty`, Guava or Jackson 2 on that source set; HC5 shaded under `wiremock.org.apache.hc...` |
| b. `EMPTY_RESPONSE` | Same as above: `ResourceAccessException` ← `NoHttpResponseException`, exactly 1 POST |
| d. 503 / 307 | `503` and `307` returned as is, exactly 1 POST each, 0 requests to the Location |
| restart | After `@DirtiesContext(AFTER_METHOD)` the new context got a **new `RestClient` bean** but the **same PG**: GET → 200, 2 serve events across the restart |

The costs are about 15 lines of lifecycle per fixture (or one shared base/extension) and the manual reset. It also needs its own `@DynamicPropertySource`. The standalone jar ships Jetty **11**, shaded.

## 7. Gotchas

1. **Constructor injection.** Under `spring.test.constructor.autowire.mode=all`, use `@InjectWireMock("pg") lateinit var` or a method parameter, not a constructor parameter.
2. **Bare `RestClient.builder()` changes client on the test classpath.** It picked `HttpComponentsClientHttpRequestFactory`, because Framework 7.0.9's `DefaultRestClientBuilder` checks HC5 → Jetty client → Reactor → JDK, and this dependency brings both HC5 and `jetty-client`.
   - On the main runtime classpath (neither present) the same code gets the JDK client.
   - Build the factory explicitly, as the spike does, or put the same client in `implementation`.
   - *Inference:* once `spring-boot-starter-restclient` is added, Boot's `ClientHttpRequestFactoryBuilder.detect()` will flip the same way in tests. It is not on the classpath today.
3. **HC5 defaults matter, and WireMock shows them.** HC5's defaults resend an idempotent GET after an empty response and follow a 307 on POST, body included. They resend a 503 POST only when the response timeout is at least 1 s (§3 controls). The spike's `disableAutomaticRetries()` + `disableRedirectHandling()` gave exactly one request in every scene.
4. **Mixed Jetty is the default outcome.** EE10 12.1.6 sits on core 12.1.12, and Servlet API 6.1.0 is forced under EE10. It ran every scene and the full suite. One `mavenBom("org.eclipse.jetty.ee10:jetty-ee10-bom:12.1.12")` line aligns it, and both the spike and the full suite pass aligned.
   - The version is hard-coded. *Inference:* reading Boot's `jetty.version` through the dependency-management plugin's imported properties would avoid that, but this was not tried.
   - Alignment must be re-checked when Boot bumps Jetty or wiremock-spring-boot bumps its 12.1.x pin.
5. **WireMock listens on `0.0.0.0` and advertises h2c.** The log shows `NetworkTrafficServerConnector ... {HTTP/1.1, (http/1.1, h2c)}{0.0.0.0:0}`. HC5 classic speaks HTTP/1.1 only. *Inference:* a JDK `HttpClient` left at its HTTP/2 default would try `Upgrade: h2c`, so pin it to HTTP/1.1 if the client choice changes.
6. **The reset happens before each method, not after.** The last method of a class leaves its stubs until the next WireMock-configured method on the same context starts. Tests should never assume an empty journal unless they carry the config.
7. **Test configs must be `@TestConfiguration`** (§4). A plain `@Configuration` in test sources under `com.loopers` leaks into every `@SpringBootTest`.
8. **The library uses JUnit internal APIs:** `org.junit.platform.commons.util.ReflectionUtils` and `StringUtils`. They work on 6.0.3. *Inference:* they can break on a JUnit upgrade without notice.
9. *Inference:* putting `@EnableWireMock` on an ADR 0012 base class (e.g. `BaseApplicationServiceTest`) adds the customizer to that base's cache key. Then `CommerceApiContextTest`, which shares the context only by mirroring the base's `@Import`, would need the same annotation to keep sharing it.

## 8. Where the code was (discarded)

The spike code was never committed. It was discarded on 2026-10-19 together with the worktree and its branch, and this report is its record. The real fixture is built in [issue 140](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/140). The files were:

- `gradle/libs.versions.toml`: `wiremockSpringBoot = "4.4.3"`, `wiremockStandalone = "3.13.2"`, `wiremock-springBoot`, `wiremock-standalone`, `httpclient5` (versionless)
- `apps/commerce-api/build.gradle.kts`: the two `testImplementation` lines, the EE10 BOM alignment, and the `spikeStandalone` source set + `spikeStandaloneTest` task
- `apps/commerce-api/src/test/kotlin/com/loopers/spike/wiremock/`:
  - `PgSpikeSupport.kt`: client builders, `PgSpikeConfig`, the composed annotation and the abstract base
  - `PgWireMockScenesTest.kt`: a–e, h, versions, classpath and controls
  - `PgWireMockResetTest.kt`: f
  - `PgWireMockContextSharingTests.kt`: g
  - `PgWireMockRestartTest.kt`: lifecycle
  - `PgWireMockConstructorInjectionTest.kt`: `@Disabled` with the observed error
  - `AppWiringProbeTest.kt`
- `apps/commerce-api/src/spikeStandalone/kotlin/com/loopers/spike/standalone/StandaloneWireMockTest.kt`
