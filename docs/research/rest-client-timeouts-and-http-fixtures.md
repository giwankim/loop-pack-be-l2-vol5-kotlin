# RestClient timeouts, hidden retries, and real-HTTP test fixtures on Boot 4.1.1

Researched 2026-10-19 against the Spring Boot 4.1.1 BOM, reference docs and source (tag `v4.1.1`), Spring Framework 7.0.9 source and reference docs (tag `v7.0.9`), JDK 25 source (`openjdk/jdk25u`, identical to the local 25.0.4 `src.zip` for every file cited unless noted) and Oracle Java 25 API docs, Apache HttpClient 5.6.4 / HttpCore 5.4.3 source, Reactor Netty 1.3.7 source, the OkHttp 5.5.0 / WireMock / MockServer / Testcontainers artifacts and docs, and Maven Central metadata.

**Verified** means the claim was read in the linked source or document. **Inference** means it is my reading of what follows from that source; it was not run. Nothing was executed against an HTTP server. The only command run against this checkout was `./gradlew --offline :apps:commerce-api:dependencies --configuration testRuntimeClasspath`. Repo facts below come only from that report, `apps/commerce-api/build.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/`, and a `git grep`.

## Short answer

- **Factory today:** only `spring-web` 7.0.9 is on the classpath, so the client uses the **JDK `java.net.http.HttpClient` (`JdkClientHttpRequestFactory`)**. That holds through Boot's detection and through plain `RestClient.builder()`. `SimpleClientHttpRequestFactory` is used only when `java.net.http.HttpClient` cannot be loaded. *Verified* (§3).
- **Missing Boot module:** the auto-configured `RestClient.Builder`, `spring.http.clients.*` and `spring.http.serviceclient.*` live in `spring-boot-restclient` / `spring-boot-http-client`. `spring-boot-starter-webmvc` does not bring either. You need `spring-boot-starter-restclient`, which the BOM manages. You can also build the factory by hand from `spring-web` alone. *Verified* (§2).
- **Hidden retries:** on HTTP/1.1 the JDK client **never resends a POST** unless the JVM-wide system property `jdk.httpclient.enableAllMethodRetry` is set. It **does resend a GET once** when the connection closes before any response byte arrives. Apache HttpClient 5's default strategy **resends a POST** that gets a 429/503 response, because Spring's Jackson request body is repeatable. Neither Boot nor Spring disables that strategy. *Verified* (§5).
- **Recommended client:** JDK factory, pinned to `HttpClient.Version.HTTP_1_1`, with explicit connect and read timeouts. `jdk.httpclient.enableAllMethodRetry` stays unset. Optionally add `-Djdk.httpclient.disableRetryConnect=true`. Prove it with a fixture test that drops the create response and asserts exactly **one** create hit on the server (§7).
- **Recommended fixture:** **OkHttp `mockwebserver3` 5.5.0**. The core artifact needs no JUnit dependency, and documented `SocketEffect` triggers cover "stored then dropped" and "stall past the timeout". `RecordedRequest.connectionIndex`/`exchangeIndex` make a hidden resend visible. It is not BOM-managed, so pin it in the catalog. The zero-dependency alternative is the JDK's `com.sun.net.httpserver.HttpServer` (§6–§7).

## 1. Versions in play

| Component | Boot 4.1.1 BOM manages | Source |
| --- | --- | --- |
| Spring Framework | 7.0.9 (`spring-framework.version`) | [BOM POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) |
| JUnit Jupiter | 6.0.3 (`junit-jupiter.version`, imports `junit-bom`) | same |
| Apache HttpClient 5 / HttpCore 5 | 5.6.4 / 5.4.3 | same |
| Jetty | 12.1.12; imports `jetty-bom` and `jetty-ee11-bom`, **no `jetty-ee10-bom`** | same |
| Reactor | BOM 2025.0.7, which pins `reactor-netty-http` 1.3.7 | same, [reactor-bom 2025.0.7](https://repo1.maven.org/maven2/io/projectreactor/reactor-bom/2025.0.7/reactor-bom-2025.0.7.pom) |
| Testcontainers | 2.0.5 (imports `testcontainers-bom`, which lists `testcontainers-mockserver`) | same, [TC BOM 2.0.5](https://repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/2.0.5/testcontainers-bom-2.0.5.pom) |
| OkHttp / `mockwebserver3`, WireMock, MockServer, `wiremock-testcontainers-module` | **not managed** (no match in the BOM) | same |

Boot 4 also dropped dependency management for Spring Retry: "an explicit version is now required". [Boot 4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

## 2. What this checkout has today

- `apps/commerce-api` declares `spring-boot-starter-webmvc`. Its 4.1.1 POM depends on `spring-boot-starter`, `-starter-jackson`, `-starter-tomcat`, `spring-boot-http-converter` and `spring-boot-webmvc`. It does **not** depend on `spring-boot-restclient`. *Verified* [POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-webmvc/4.1.1/spring-boot-starter-webmvc-4.1.1.pom)
- The `testRuntimeClasspath` report has `org.springframework:spring-web:7.0.9`. It has no `httpclient5`, Jetty, `reactor-netty`, OkHttp, `spring-boot-restclient` or `spring-boot-http-client`. `junit-jupiter-api` 5.13.4 is already resolved up to 6.0.3. *Verified* (local Gradle report)
- `git grep` outside `docs/` finds no `RestClient`, `RestTemplate`, Resilience4j, Spring Retry or `@Retryable`. *Verified* (local)
- The Boot 4.0 migration guide lists `spring-boot-starter-restclient` (test: `spring-boot-starter-restclient-test`) as the starter for "Spring's Imperative `RestClient` and `RestTemplate`". [Migration guide, Starters](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) That starter depends on `spring-boot-starter`, `spring-boot-starter-jackson` and `spring-boot-restclient`. [POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-restclient/4.1.1/spring-boot-starter-restclient-4.1.1.pom) `spring-boot-restclient` in turn depends on `spring-boot-http-client`. [POM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-restclient/4.1.1/spring-boot-restclient-4.1.1.pom)
- The prototype `RestClient.Builder` bean is defined in `RestClientAutoConfiguration` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-restclient/src/main/java/org/springframework/boot/restclient/autoconfigure/RestClientAutoConfiguration.java#L76-L93)). The `spring.http.clients` properties are bound in `spring-boot-http-client` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/autoconfigure/HttpClientsProperties.java#L28)). **Inference:** today, injecting `RestClient.Builder` fails and the properties are ignored. Adding `spring-boot-starter-restclient` (no version; the BOM manages it) fixes both and follows the repo's BOM-first rule.

## 3. Which request factory Boot 4.1 picks (Q1)

The Boot reference lists, "in order of preference": Apache HttpClient, Jetty HttpClient, Reactor Netty HttpClient, the JDK client (`java.net.http.HttpClient`), and the simple JDK client (`java.net.HttpURLConnection`). "If multiple clients are available on the classpath, and no global configuration is provided, the most preferred client will be used." [Boot reference, HTTP Client Detection](https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.clienthttprequestfactory) (the page rendered as 4.1.1).

The source matches. `ClientHttpRequestFactoryBuilder.detect` checks these classes in order:

1. `org.apache.hc.client5.http.impl.classic.HttpClients`
2. `org.eclipse.jetty.client.HttpClient`
3. `reactor.netty.http.client.HttpClient`
4. `java.net.http.HttpClient`

It falls back to `simple()` if none is present. *Verified* [detect()](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/ClientHttpRequestFactoryBuilder.java#L204-L233). The auto-configured builder uses `detect` unless `spring.http.clients.imperative.factory` is set. *Verified* [ImperativeHttpClientAutoConfiguration](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/autoconfigure/imperative/ImperativeHttpClientAutoConfiguration.java#L62-L74). The allowed values are `http-components`, `jetty`, `reactor`, `jdk` and `simple` ([enum](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/autoconfigure/imperative/ImperativeHttpClientsProperties.java#L35-L79)).

Without Boot, Spring's own `RestClient.builder().build()` uses the same order: HttpComponents, Jetty, Reactor Netty, `new JdkClientHttpRequestFactory()`, then `SimpleClientHttpRequestFactory`. *Verified* [DefaultRestClientBuilder.initRequestFactory](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/client/DefaultRestClientBuilder.java#L444-L464). Neither list includes OkHttp. Spring Framework 7.0.9's `org.springframework.http.client` package has no OkHttp factory. *Verified* (sources jar listing)

**With only `spring-web` on the classpath, the JDK `HttpClient` is used.** *Verified*

**Trap: test dependencies can switch the factory.** `org.wiremock:wiremock:3.13.2` depends on `httpclient5` at compile scope ([POM](https://repo1.maven.org/maven2/org/wiremock/wiremock/3.13.2/wiremock-3.13.2.pom)). WireMock 4 betas pull it in through `wiremock-httpclient-apache5` ([POM](https://repo1.maven.org/maven2/org/wiremock/wiremock-httpclient-apache5/4.0.0-beta.40/wiremock-httpclient-apache5-4.0.0-beta.40.pom)). **Inference:** adding the non-standalone WireMock to `testImplementation` would make tests run Apache HC5, with its default retry strategy, while production runs the JDK client. Pinning `spring.http.clients.imperative.factory=jdk`, or building the factory explicitly, prevents this. `wiremock-standalone:3.13.2` relocates HC5 and the Jetty client under `wiremock/…`, so detection is unaffected. *Verified* by listing the jar: `wiremock/org/apache/hc/client5/http/impl/classic/HttpClients.class`.

## 4. Timeout configuration on Boot 4.1 (Q2)

### Property names (Boot 4 renamed them)

| Boot 3.x name (deprecated since 4.0.0) | Boot 4 name |
| --- | --- |
| `spring.http.client.connect-timeout` | `spring.http.clients.connect-timeout` |
| `spring.http.client.read-timeout` | `spring.http.clients.read-timeout` |
| `spring.http.client.factory` | `spring.http.clients.imperative.factory` |
| `spring.http.client.redirects` / `.ssl.bundle` | `spring.http.clients.redirects` / `.ssl.bundle` |
| `spring.http.reactiveclient.*` | `spring.http.clients.*`, `spring.http.clients.reactive.connector` |

*Verified* [configuration metadata](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/resources/META-INF/additional-spring-configuration-metadata.json#L1-L50). The common settings are `redirects`, `connect-timeout`, `read-timeout`, `cookie-handling` and `ssl.bundle` ([HttpClientSettingsProperties](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/autoconfigure/HttpClientSettingsProperties.java#L39-L59)). There is **no** retry, pool-size or pool-wait property. *Verified* (same class)

### Programmatic hooks

- `HttpClientSettings` is a record. `withConnectTimeout`, `withReadTimeout` and `withTimeouts` return a copy. `HttpClientSettings.defaults()` is all `null`, which means no timeouts. *Verified* [source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/HttpClientSettings.java#L73). The Boot reference builds a client with `ClientHttpRequestFactoryBuilder.jdk().build(settings)` and `RestClient.builder().requestFactory(requestFactory)`. [Boot reference, global configuration](https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.global-configuration)
- "For more complex customizations, you can use `ClientHttpRequestFactoryBuilderCustomizer` or declare your own `ClientHttpRequestFactoryBuilder` bean which will cause auto-configuration to back off." [Boot reference](https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.clienthttprequestfactory.configuration) The customizer is `B customize(B builder)`, applied through `LambdaSafe` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/autoconfigure/imperative/ImperativeHttpClientAutoConfiguration.java#L76-L83)).
- `RestClientCustomizer` beans change the auto-configured `RestClient.Builder` app-wide. `RestClient.create()` skips all auto-configuration. [Boot reference, RestClient Customization](https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.restclient.customization)
- **HTTP Service Clients.** Interfaces annotated with `@HttpExchange` / `@PostExchange` / `@GetExchange` are imported with `@ImportHttpServices(group = "...", types = [...])` or `basePackages`. Each group is configured under `spring.http.serviceclient.<group>.*`: base URL, default headers, API versioning, redirects, connection and read timeouts, SSL bundle. Global `spring.http.clients.*` values apply unless overridden. [Boot reference, HTTP Service Interface Clients](https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.httpservice.properties) Per group, Boot builds a **separate request factory** from the shared `ClientHttpRequestFactoryBuilder` bean with that group's settings, then applies `base-url`, `default-header` and `apiversion`. *Verified* [PropertiesRestClientHttpServiceGroupConfigurer](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-restclient/src/main/java/org/springframework/boot/restclient/autoconfigure/service/PropertiesRestClientHttpServiceGroupConfigurer.java#L82-L98), [HttpClientProperties](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/autoconfigure/HttpClientProperties.java#L42-L54). **Inference:** a `ClientHttpRequestFactoryBuilderCustomizer`, such as an HTTP/1.1 pin or `disableAutomaticRetries`, therefore also reaches service-client groups.

### How "connect" and "read" map onto each client

| Boot builder | `connect-timeout` becomes | `read-timeout` becomes | What the read timeout bounds | Pool-wait setting |
| --- | --- | --- | --- | --- |
| `jdk()` | `HttpClient.Builder.connectTimeout` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/JdkHttpClientBuilder.java#L98-L111)) | `JdkClientHttpRequestFactory.setReadTimeout` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/JdkClientHttpRequestFactoryBuilder.java#L118-L124)) | **One deadline for the whole exchange** (Spring's `TimeoutHandler`, below) | None. The JDK's HTTP/1.1 keep-alive cache is unbounded by default (`jdk.httpclient.connectionPoolSize`, default 0) ([module docs](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/module-info.java#L53-L56)) |
| `httpComponents()` | `ConnectionConfig.setConnectTimeout` (HC5 default 3 min) | `ConnectionConfig.setSocketTimeout` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/HttpComponentsHttpClientBuilder.java#L234-L245)) | Socket timeout "for I/O operations", i.e. per read, not a deadline ([HC5 ConnectionConfig](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/config/ConnectionConfig.java#L217-L234)) | `RequestConfig.connectionRequestTimeout`, **default 3 minutes** ([HC5 RequestConfig](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/config/RequestConfig.java#L482-L491)). Not mapped by Boot. Set it with `withDefaultRequestConfigCustomizer { it.setConnectionRequestTimeout(...) }` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/HttpComponentsClientHttpRequestFactoryBuilder.java#L154)) or `HttpComponentsClientHttpRequestFactory.setConnectionRequestTimeout` ([source](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.java#L120-L139)). Pool defaults are 25 total / 5 per route ([source](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManager.java#L119-L120)) |
| `simple()` | `URLConnection.setConnectTimeout` | `URLConnection.setReadTimeout` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/SimpleClientHttpRequestFactoryBuilder.java#L90-L91)) | The wait "when reading from Input stream", per read ([Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URLConnection.html#setReadTimeout(int))) | None |
| `jetty()` / `reactor()` | factory `setConnectTimeout` | factory `setReadTimeout` ([Jetty](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/JettyClientHttpRequestFactoryBuilder.java#L148-L155), [Reactor](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/ReactorClientHttpRequestFactoryBuilder.java#L163-L170)) | Not researched further: neither client is on the classpath | — |

**The JDK factory in detail:**

- Spring's `JdkClientHttpRequestFactory.setReadTimeout` Javadoc says `@see HttpRequest.Builder#timeout`. The 7.0.9 code never calls `HttpRequest.Builder.timeout` ([buildRequest](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java#L166-L198)).
- Instead, when a read timeout is set, `executeInternal` starts a `TimeoutHandler` right after `sendAsync`. When the timer fires, it cancels the response future, or closes the response body if headers already arrived. The cancellation surfaces as `HttpTimeoutException`. The timer is disarmed only when the response body stream is closed. Spring labels it a "temporary workaround … until JDK-8208693 is fixed". *Verified* [executeInternal](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java#L113-L127), [TimeoutHandler](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java#L270-L322).
- **Inference:** the deadline therefore covers connecting, sending, waiting for headers and reading the body.
- The JDK's own `HttpRequest.Builder.timeout` "stops when the response headers are received". JDK-8208693 extends it to the body; it is fixed in **JDK 26**, not 25. [JDK-8208693](https://bugs.openjdk.org/browse/JDK-8208693), [Java 25 Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#timeout(java.time.Duration))
- `HttpClient.Builder.connectTimeout` has "no effect" when a pooled connection is reused. [JDK source](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/java/net/http/HttpClient.java#L244-L261)
- The no-argument `JdkClientHttpRequestFactory()` uses `HttpClient.newHttpClient()` with no read timeout ([source](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequestFactory.java#L53-L55)). **Inference:** the default `RestClient.builder()` path waits forever.
- `RestClient` wraps any `IOException`, including `HttpTimeoutException`, in `ResourceAccessException`. [DefaultRestClient](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/client/DefaultRestClient.java#L623-L624)

**HC5 semantics:** "response timeout is not a deadline. Its absolute value can be exceeded, for example, in case of automatic request re-execution." [RequestConfig](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/config/RequestConfig.java#L538-L564) Spring's own `HttpComponentsClientHttpRequestFactory.setReadTimeout` maps to `RequestConfig.responseTimeout` ([source](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.java#L257-L270)). Boot's builder uses `ConnectionConfig.socketTimeout` instead.

## 5. Hidden retries (Q3)

| Client | POST resent automatically? | GET resent automatically? | Off switch |
| --- | --- | --- | --- |
| JDK `java.net.http` (HTTP/1.1) | **No.** Exception: after a `ConnectException`, when no request bytes were sent. | **Yes, once**, if the connection reports EOF/reset before any response byte | Retry of non-idempotent methods is off by default (`jdk.httpclient.enableAllMethodRetry=false`); `jdk.httpclient.disableRetryConnect=true` stops connect retries. **No switch turns off the GET retry.** |
| `HttpURLConnection` via Spring's `SimpleClientHttpRequestFactory` | **No**, because Spring always uses streaming mode for request bodies | **Yes, once**, on an I/O error (not a read timeout) while reading the status line | `-Dsun.net.http.retryPost=false` (default `true`) covers non-streaming POSTs; nothing for GET |
| Apache HC5 (`HttpClientBuilder` default) | **Yes, once, on 429/503** after 1 s or `Retry-After`; **no** on `IOException` | Yes, once on `IOException` (`NoHttpResponseException`…), once on 429/503 | `HttpClientBuilder.disableAutomaticRetries()`; neither Boot nor Spring calls it |
| Reactor Netty | Only if the connection resets **before** headers were sent ("retry once") | same | `HttpClient.disableRetry(true)` |
| Spring `RestClient` / HTTP interfaces | No retry code | No retry code | — |

### (a) `HttpURLConnection` and `sun.net.http.retryPost`

- `sun.net.www.http.HttpClient` reads `sun.net.http.retryPost` once in a static initializer. It defaults to `true` "to preserve behavior from previous releases". [source](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/sun/net/www/http/HttpClient.java#L107-L109), [L205-L224](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/sun/net/www/http/HttpClient.java#L205-L224)
- In `parseHTTP`, a `SocketTimeoutException` is rethrown without retry: "We don't want to retry the request when the app. sets a timeout". Any other `IOException`, and an EOF before 8 status-line bytes, sends the request **once more on a new connection**. The exceptions are `CONNECT`, a `streaming` request, an interrupted virtual thread, or a `POST` with `retryPost=false`. *Verified* [L736-L765](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/sun/net/www/http/HttpClient.java#L736-L765), [L914-L931](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/sun/net/www/http/HttpClient.java#L914-L931)
- The resend is **not limited to reused keep-alive connections**: the only guard is `failedOnce`. *Verified*, same lines.
- For a streaming POST, `HttpClient.New` also discards a cached connection that is not `available()`. [L336-L350](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/sun/net/www/http/HttpClient.java#L336-L350)
- `streaming()` is true when fixed-length or chunked streaming mode is set. [HttpURLConnection L1222](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/sun/net/www/protocol/http/HttpURLConnection.java#L1222-L1225) (line taken from the jdk25u `master` copy, which differs slightly from the local 25.0.4 file)
- Spring 7.0.9's `SimpleClientHttpRequest` always calls `setFixedLengthStreamingMode` or `setChunkedStreamingMode` when `doOutput` is true, and `doOutput` is true for POST/PUT/PATCH/DELETE. *Verified* [executeInternal](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/SimpleClientHttpRequest.java#L67-L90), [prepareConnection](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/SimpleClientHttpRequestFactory.java#L148-L163). So Spring's POST is never resent by `HttpURLConnection`, regardless of `retryPost`, while a GET can be resent once.
- `retryPost` is not in the Oracle networking-properties page, so it exists only in source. [net properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html) The silent-POST-retry bug is [JDK-6382788](https://bugs.openjdk.org/browse/JDK-6382788).

### (b) JDK `java.net.http.HttpClient`

- Documented properties ([module-info](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/module-info.java#L64-L70), [Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html)):
  - `jdk.httpclient.enableAllMethodRetry` (default `false`): "Whether it is permitted to automatically retry non-idempotent HTTP requests."
  - `jdk.httpclient.disableRetryConnect` (default `false`).
  - `jdk.httpclient.redirects.retrylimit` (default 5): the maximum number of send attempts "when redirected or any failure occurs" ([L146-L149](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/module-info.java#L146-L149)).
- Both flags are read into `static final` fields, so they are JVM-wide and fixed once the class loads. *Verified* [MultiExchange L491-L508](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java#L491-L508)
- Idempotent means **`GET` and `HEAD` only**. `canRetryRequest` returns true for those, or for any method when `enableAllMethodRetry` is set. [L511-L526](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java#L511-L526)
- A retry is considered for a `ConnectionExpiredException`, for a `ConnectException` (unless `disableRetryConnect`), or for an HTTP/2 exchange the peer did not process. Cancelled requests are never retried. Except for `ConnectException`, a non-idempotent request "fail[s] with the original cause". There is at most one retry (`retriedOnce`). [L537-L600](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java#L537-L600)
- On HTTP/1.1, `sendHeadersAsync` arms "retry on error" for every exchange ([Http1Exchange L299-L303](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/Http1Exchange.java#L299-L303)). If an `IOException` arrives before **any** response byte, the receiver turns it into `ConnectionExpiredException` "to try & force a retry" ([Http1AsyncReceiver L481-L497](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/Http1AsyncReceiver.java#L481-L497)). An older comment in that class says this happens only for pooled connections, but the 25 code arms it on every exchange.
- **Inference:** "server stored the transaction, then closed without a response" yields one failed POST and no resend. The same staging on a GET lookup reaches the server twice.
- **Cleartext HTTP/2 upgrade.** A new client prefers HTTP/2, and "each request will attempt to upgrade to HTTP/2" ([HttpClient.Builder.version](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/java/net/http/HttpClient.java#L323-L344)). On `http://` with no pooled h2c connection, the JDK sends the request over HTTP/1.1 with `Connection: Upgrade, HTTP2-Settings` and `Upgrade: h2c` headers ([ExchangeImpl L154-L163](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/ExchangeImpl.java#L154-L163), [HttpRequestImpl L293-L297](https://github.com/openjdk/jdk25u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/HttpRequestImpl.java#L293-L297)). Boot's `JdkHttpClientBuilder` does not set a version ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/JdkHttpClientBuilder.java#L98-L111)). **Inference:** pin `HTTP_1_1`. That keeps the headers seen by the fixture stable. It also keeps the "unprocessed by peer" HTTP/2 retry path, which bypasses the method check, out of play if a fixture such as Jetty-based WireMock accepts h2c.

### (c) Apache HttpClient 5.6.4

- `DefaultHttpRequestRetryStrategy.INSTANCE` uses `maxRetries = 1` and a 1-second `defaultRetryInterval` ([L166-L167](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/DefaultHttpRequestRetryStrategy.java#L166-L167)).
- Exceptions it never retries: `InterruptedIOException` (so socket timeouts), `UnknownHostException`, `ConnectException`, `ConnectionClosedException`, `NoRouteToHostException`, `SSLException`. Status codes it retries: **429, 503** ([L137-L145](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/DefaultHttpRequestRetryStrategy.java#L137-L145)).
- An `IOException` retry requires `Method.isIdempotent` ([L196](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/DefaultHttpRequestRetryStrategy.java#L196)). `POST` and `PATCH` are not idempotent; `GET`, `PUT` and `DELETE` are ([HttpCore 5.4.3 `Method`](https://github.com/apache/httpcomponents-core/blob/rel/v5.4.3/httpcore5/src/main/java/org/apache/hc/core5/http/Method.java)).
- The **status-code retry does not check the method**. It is skipped only when a `responseTimeout` is set and is shorter than the retry interval ([L204-L214](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/DefaultHttpRequestRetryStrategy.java#L204-L214)).
- `HttpRequestRetryExec` skips both kinds of retry only for a non-repeatable entity ([L115-L149](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpRequestRetryExec.java#L115-L149)). Spring's HC5 entity reports `body.repeatable()` ([HttpComponentsClientHttpRequest L158-L160](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/HttpComponentsClientHttpRequest.java#L158-L160)). Jackson converters return `supportsRepeatableWrites = true` ([AbstractJacksonHttpMessageConverter L506-L508](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/converter/AbstractJacksonHttpMessageConverter.java#L506-L508)). **Inference:** a JSON POST that gets a 503, from the PG or any proxy in front of it, is resent once.
- The builder installs the retry exec unless `disableAutomaticRetries()` was called ([HttpClientBuilder L574-L577, L1002-L1009](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.4/httpclient5/src/main/java/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.java#L1002-L1009)). Boot's builder calls `HttpClientBuilder.create().useSystemProperties()…` with no retry setting ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/HttpComponentsHttpClientBuilder.java#L198-L207)). Spring's no-arg factory uses `HttpClients.createSystem()` ([L79-L80](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.java#L79-L80)). `grep -ri retry` over the `spring-boot-http-client`/`spring-boot-restclient` 4.1.1 sources finds nothing. *Verified*
- The switch is `ClientHttpRequestFactoryBuilder.httpComponents().withHttpClientCustomizer { it.disableAutomaticRetries() }` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/HttpComponentsClientHttpRequestFactoryBuilder.java#L83-L88)).

### (d) Spring `RestClient`, proxies, resilience libraries

- `grep -ri retry` over the `spring-web` 7.0.9 sources finds no client-side retry. The only hits are the `Retry-After` header constant, the SSE `retry` field and server async timeouts. *Verified* (sources jar)
- Spring Framework 7's own `@Retryable` and `RetryTemplate` act only when used explicitly. Enable annotations with `@EnableResilientMethods`, or a `RetryAnnotationBeanPostProcessor` bean. Their defaults are "at most 3 retry attempts … and a delay of 1 second". [Spring reference, Resilience](https://docs.spring.io/spring-framework/reference/core/resilience.html) (page shows 7.0.9). No `spring-boot-autoconfigure`, `-http-client` or `-restclient` 4.1.1 source references them. *Verified* (grep)
- **Proxies:** Boot's JDK builder installs `ProxySelector.getDefault()` ([source](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-client/src/main/java/org/springframework/boot/http/client/JdkHttpClientBuilder.java#L98-L111)). `http.nonProxyHosts` defaults to `localhost|127.*|[::1]` ([net properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html)). **Inference:** a loopback test PG is never proxied by default. A production intermediary, such as a mesh sidecar or gateway, has its own retry policy that this repo cannot see. Reactor Netty's "retry once" fires only while headers are unsent ([HttpClient.disableRetry](https://github.com/reactor/reactor-netty/blob/v1.3.7/reactor-netty-http/src/main/java/reactor/netty/http/client/HttpClient.java#L677-L687), [HttpClientConnect L378-L391](https://github.com/reactor/reactor-netty/blob/v1.3.7/reactor-netty-http/src/main/java/reactor/netty/http/client/HttpClientConnect.java#L378-L391)). It is not on the classpath.
- **Not verified:** Jetty `HttpClient` retry behaviour. It is not on the classpath and was not researched.

### Zero-resend setting for the recommended factory (JDK)

1. Use HTTP/1.1: `withHttpClientCustomizer { it.version(HttpClient.Version.HTTP_1_1) }`.
2. Leave `jdk.httpclient.enableAllMethodRetry` **unset** (default `false`).
3. Optionally set `-Djdk.httpclient.disableRetryConnect=true` (JVM-wide) so a refused connection is not re-attempted. This one is cosmetic: no request bytes were sent.
4. Add no `RetryTemplate`, `@Retryable` or Resilience4j retry around the create call.

*Verified* (sources above). *Inference:* together these give zero automatic resends of the POST.

## 6. Real-HTTP fixture options (Q4)

The Spring reference says `MockRestServiceServer` "relies on configuring the client under test with a custom `ClientHttpRequestFactory`… without running a server", and recommends "mock web servers for more complete testing of the transport layer and network conditions". [Spring reference, Testing Client Applications](https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-client.html) `bindTo(RestClient.Builder)` calls `restClientBuilder.requestFactory(mockFactory)` ([source](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-test/src/main/java/org/springframework/test/web/client/MockRestServiceServer.java#L295-L299)). It **replaces** the factory whose timeouts and retries are under test, so it **fails the "real adapter → local HTTP server" requirement**. *Verified*

| Option | Latest (Maven Central) | Boot 4.1.1 manages? | Real socket | Cost |
| --- | --- | --- | --- | --- |
| OkHttp `com.squareup.okhttp3:mockwebserver3` | **5.5.0, 2026-08-16** ([listing](https://repo1.maven.org/maven2/com/squareup/okhttp3/mockwebserver3/), [changelog](https://github.com/square/okhttp/blob/master/CHANGELOG.md)) | No | Yes | `okhttp-jvm` 5.5.0, `okio-jvm` 3.18.1, `kotlin-stdlib` ≥ 2.1.21 ([POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/mockwebserver3/5.5.0/mockwebserver3-5.5.0.pom)) |
| JDK `com.sun.net.httpserver.HttpServer` (`jdk.httpserver`) | Java 25 | n/a | Yes | None |
| WireMock `wiremock-standalone` | 3.13.2, 2025-11-14; 4.0.0-beta.40, 2026-10-07 ([listing](https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/)) | No | Yes | One shaded jar (Jetty 11) |
| WireMock `wiremock-jetty12` / `wiremock-spring-boot` | 3.13.2 / 4.4.3, 2026-10-05 ([listing](https://repo1.maven.org/maven2/org/wiremock/integrations/wiremock-spring-boot/)) | No | Yes | Jetty 12.0/12.1 EE10 + Guava + Jackson 2 + HC5 |
| Testcontainers MockServer (`testcontainers-mockserver`) + `mockserver-client-java` | TC 2.0.5, 2026-04-20; MockServer 9.0.0, 2026-10-15 ([listing](https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/)) | TC module yes; client no | Yes (container) | Docker + Netty client |
| `wiremock-testcontainers-module` | 1.0-alpha-15, 2025-03-30 ([listing](https://repo1.maven.org/maven2/org/wiremock/integrations/testcontainers/wiremock-testcontainers-module/)) | No | Yes (container) | Docker; built against TC 1.20.6 ([POM](https://repo1.maven.org/maven2/org/wiremock/integrations/testcontainers/wiremock-testcontainers-module/1.0-alpha-15/wiremock-testcontainers-module-1.0-alpha-15.pom)) |
| Spring `MockRestServiceServer` | spring-test 7.0.9 | Yes | **No** | — |

### OkHttp `mockwebserver3` 5.5.0

- **Packaging.** The core artifact is "Core module. No JUnit dependency!". The old `okhttp3.mockwebserver` artifact is "Obsolete. Depends on JUnit 4." OkHttp 5.0.0 (2025-07-02) replaced `SocketPolicy` with **`SocketEffect`**, "split[ting] triggers (request start, response body, etc.) from effects". It also renamed `sequenceNumber` to `exchangeIndex`, added `connectionIndex`, and replaced the JUnit 5 parameter extension with `@StartStop`. [Changelog 5.0.0](https://github.com/square/okhttp/blob/master/CHANGELOG.md#version-500)
- **(i) State.** `Dispatcher.dispatch(request)` "may block (for instance, to wait on a CountdownLatch)" ([Dispatcher](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/Dispatcher.kt)). A custom `Dispatcher` holding a `ConcurrentHashMap` of transactions can serve create, lookup by key, list by `orderId`, and flip PENDING to SUCCESS or FAILED per call. Set it with `server.dispatcher = …` ([MockWebServer L139-L147](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockWebServer.kt#L139-L147)).
- **(ii) Drop after reading.** For HTTP/1, the server reads the request, increments `requestCount`, records it, calls `dispatcher.dispatch`, then runs `onResponseStart` **before writing any response byte** ([L583-L601](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockWebServer.kt#L583-L601)). `MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket())` therefore means "transaction stored, response lost" ([MockResponse L447-L451](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockResponse.kt#L447-L451), [SocketEffect](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/SocketEffect.kt)). `onRequestStart`/`onRequestBody` cover "dropped before the server saw it".
- **(iii) Slow response.** `onResponseStart(SocketEffect.Stall)` stops processing and sleeps until the socket is closed. That trips the client timeout without timing guesses ([L976-L979](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockWebServer.kt#L976-L979)). `headersDelay(n, unit)` and `bodyDelay(n, unit)` give a slow but successful reply ([L484-L502](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockResponse.kt#L484-L502)). A latch inside `dispatch` also works.
- **(iv) Recording.**
  - `requestCount` ([L126-L127](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockWebServer.kt#L126-L127)) and `takeRequest()` / `takeRequest(timeout, unit)` ([L250-L265](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/MockWebServer.kt#L250-L265)).
  - `RecordedRequest` exposes `method`, raw `target` ("the request target from the original HTTP request", for asserting query encoding), parsed `url`, `headers`, `body: ByteString?` and `chunkSizes` ([RecordedRequest](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver/src/main/kotlin/mockwebserver3/RecordedRequest.kt#L26-L84)).
  - It also exposes `connectionIndex` and `exchangeIndex`. "If two recorded requests share a connection index, they also shared a socket connection." **Inference:** a hidden resend shows up as a second recorded request on a new `connectionIndex`.
- **JUnit 6.** The core has no JUnit dependency. `mockwebserver3-junit5` 5.5.0 declares `junit-jupiter-api:5.13.4` ([POM](https://repo1.maven.org/maven2/com/squareup/okhttp3/mockwebserver3-junit5/5.5.0/mockwebserver3-junit5-5.5.0.pom)), which the BOM aligns to 6.0.3 here. `@StartStop` uses `BeforeEachCallback`/`BeforeAllCallback`, `AnnotationSupport.findAnnotatedFields` and `store.put(field, server)` ([source](https://github.com/square/okhttp/blob/parent-5.5.0/mockwebserver-junit5/src/main/kotlin/mockwebserver3/junit5/internal/StartStopExtension.kt)). It relies on JUnit closing `AutoCloseable` store values, which the JUnit 6.0.3 guide documents ([Keeping state](https://docs.junit.org/6.0.3/extensions/keeping-state-in-extensions.html)). **Inference:** it should work on 6.0.3; not run. Skipping the module and calling `start()`/`close()` in `@BeforeEach`/`@AfterEach` removes the question.
- **Factory side effects:** none. OkHttp is in neither detection list (§3). *Verified*

### JDK `com.sun.net.httpserver.HttpServer`

- **(i) State and threads.** Write a plain `HttpHandler` with your own maps. Without `setExecutor`, "a default implementation is used, which uses the thread which was created by the `start()` method" ([HttpServer L230-L242](https://github.com/openjdk/jdk25u/blob/master/src/jdk.httpserver/share/classes/com/sun/net/httpserver/HttpServer.java#L230-L242)). That default runs the task inline ([ServerImpl L202-L206](https://github.com/openjdk/jdk25u/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerImpl.java#L202-L206)). **Inference:** one blocked handler then stalls every other request. Set `Executors.newVirtualThreadPerTaskExecutor()` before `start()`.
- **(ii) Drop after reading.** Read the body, store the transaction, then call `exchange.close()` **without** `sendResponseHeaders`. `ExchangeImpl.close()` closes the underlying connection when "the wrapper output stream is not set up", i.e. headers were never sent ([ExchangeImpl L125-L152](https://github.com/openjdk/jdk25u/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ExchangeImpl.java#L125-L152)). A handler that throws has the same effect while the write is unfinished ([ServerImpl L903-L907](https://github.com/openjdk/jdk25u/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerImpl.java#L903-L907)). *Verified* in the implementation. The public `HttpExchange.close()` Javadoc only says it closes the streams ([Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html#close())), so this is implementation behaviour, not spec.
- **(iii) Delay.** Block the handler on a latch or `Thread.sleep`.
- **(iv) Recording.** Hand-rolled: `requestMethod`, `requestURI.rawPath`/`rawQuery`, `requestHeaders` and `requestBody.readAllBytes()`. `sendResponseHeaders(code, n)` takes `n > 0` for a fixed length, `0` for chunked and `-1` for no body ([Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html#sendResponseHeaders(int,long))). There is no built-in connection index, so a resend shows only as an extra hit.
- **JUnit 6.** No dependency. The JUnit 6.0.3 guide itself uses an `HttpServer` as its `AutoCloseable` store example ([Keeping state](https://docs.junit.org/6.0.3/extensions/keeping-state-in-extensions.html)).

### WireMock

- **Jetty and Boot 4.**
  - "WireMock ships with Jetty 11 by default but fully supports Jetty 12 as well with a new module `wiremock-jetty12`." ([Jetty 12](https://wiremock.org/docs/jetty-12/))
  - `wiremock-jetty12:3.13.2` uses Jetty **12.0.30 / EE10** ([POM](https://repo1.maven.org/maven2/org/wiremock/wiremock-jetty12/3.13.2/wiremock-jetty12-3.13.2.pom)). Boot 4.1.1 manages Jetty core 12.1.12 and EE11 but **no EE10 BOM** (§1).
  - `wiremock-spring-boot` 4.4.3 pins EE10 12.1.6 "until wiremock-jetty12:jar uses newer jetty". It compiles against Boot 4.0.3, Framework 7.0.5 and JUnit 6.0.3 ([build.gradle at 4.4.3](https://github.com/wiremock/wiremock-spring-boot/blob/4.4.3/build.gradle)).
  - **Inference:** expect mixed 12.1.12 core with 12.0.x or 12.1.6 EE10 jars unless you align them by hand. `wiremock-standalone` avoids this: "most of the standalone JAR's dependencies are shaded" ([Download](https://wiremock.org/docs/download-and-installation/)). It also avoids the HC5 detection trap from §3.
- **(i) State.** Scenarios (`inScenario`, `whenScenarioStateIs`, `willSetStateTo`, `Scenario.STARTED`) give a fixed state machine ([Stateful behaviour](https://wiremock.org/docs/stateful-behaviour/)). Generated transaction keys and listing by `orderId` need templating, a custom extension, or `org.wiremock.extensions:wiremock-state-extension` (0.10.1, 2026-04-14; [listing](https://repo1.maven.org/maven2/org/wiremock/extensions/wiremock-state-extension/)).
- **(ii) Drop after reading.** `Fault.EMPTY_RESPONSE` ("Return a completely empty response") or `Fault.CONNECTION_RESET_BY_PEER` (`SO_LINGER` 0; "only seems to work properly on *nix OSs"). The page does not say whether the request body is read first ([Simulating faults](https://wiremock.org/docs/simulating-faults/)).
- **(iii) Delay.** `withFixedDelay(ms)`, global delays, `withChunkedDribbleDelay` (same page).
- **(iv) Recording.** `verify(exactly(n), postRequestedFor(...))`, `findAll(...)`, `getAllServeEvents()` ([Verifying](https://wiremock.org/docs/verifying/)).
- **h2c.** WireMock has `http2PlainDisabled(true)` to "Disable HTTP/2 over HTTP", and the page does not state the default ([Configuration](https://wiremock.org/docs/configuration/)). **Inference:** with the JDK client's `Upgrade: h2c` (§5b), disable it or pin the client to HTTP/1.1.

### Testcontainers MockServer / WireMock modules

- `org.testcontainers:testcontainers-mockserver:2.0.5` is BOM-managed. "Client version should match to the version in a container tag", and `mockserver-client-java` must be added separately ([Testcontainers MockServer](https://java.testcontainers.org/modules/mockserver/)).
- MockServer can drop a connection with `error().withDropConnection(true)` and delay with `withDelay(...)` ([MockServer expectations](https://www.mock-server.com/mock_server/creating_expectations.html)).
- The WireMock module is still `1.0-alpha-15`, built against Testcontainers 1.20.6, while this repo resolves 2.0.5.
- **Inference:** both put Docker and a port-mapped hop between the adapter and the fixture. Keeping state means callbacks or extensions, and nothing here needs a container.

## 7. Recommendation (Q5)

### Fixture ranking

| Rank | Fixture | Why | Dependency cost | Risks |
| --- | --- | --- | --- | --- |
| 1 | **OkHttp `mockwebserver3` 5.5.0, core artifact only** | Documented triggers map one-to-one onto the four staged scenes: `onResponseStart(CloseSocket())` for stored-then-lost and `Stall` for timeout. A blocking `Dispatcher` holds state. `RecordedRequest.target`/`connectionIndex`/`requestCount` cover raw query, resend and hit-count assertions. *Verified API* | Pin one entry in `libs.versions.toml` (`testImplementation`); brings `okhttp-jvm` + `okio-jvm` | Not BOM-managed, so manual upgrades. Its threading model was not studied beyond `dispatch` being allowed to block. |
| 2 | **JDK `HttpServer`** | Zero dependencies. Every scene can be built with a few dozen lines. | None | Drop-before-headers is implementation behaviour (§6). Recording is hand-rolled, with no connection index. You must set an executor. |
| 3 | WireMock `wiremock-standalone` 3.13.2 | Mature fault and verification APIs | One large shaded jar | Dynamic state needs extensions. The non-standalone artifact flips factory detection to HC5. h2c default undocumented. |
| 4 | Testcontainers MockServer / WireMock | Real separate process | Docker, client jar, image pull | Slower. Version-matched client. WireMock module is alpha on TC 1.x. |
| — | `MockRestServiceServer` | — | — | No socket; replaces the factory under test, so it fails the requirement. *Verified* |

### Client configuration ranking

| Rank | Factory + settings | Retry stance | Cost | Risks |
| --- | --- | --- | --- | --- |
| 1 | **JDK** (`jdk`), `version(HTTP_1_1)`, `connect-timeout` + `read-timeout` set | POST never resent on HTTP/1.1 without `enableAllMethodRetry`. GET resent once on an early close. *Verified* | None; already detected | Retry flags are JVM-wide system properties, not per client. The GET retry cannot be disabled, so lookup-count tests must expect 2 hits when they drop a GET. No pool bound or pool-wait timeout. |
| 2 | **Apache HC5** (`http-components`) + `disableAutomaticRetries()` + `setConnectionRequestTimeout(...)` | Zero automatic resends once disabled. *Verified* | `org.apache.httpcomponents.client5:httpclient5` with no version (BOM 5.6.4) | Forgetting the switch resends POSTs on 429/503 (§5c). Boot's read timeout is per-read, not a deadline. |
| 3 | Simple (`simple`) | POST safe because Spring streams; GET resent once; `retryPost=false` as a guard | None | Per-read timeout only. No pool control. |

### Sketch (illustrative, not compiled)

With `spring-boot-starter-restclient` added and the JDK factory pinned:

```properties
spring.http.clients.imperative.factory=jdk
spring.http.serviceclient.pg.base-url=http://localhost:${pg.port}
spring.http.serviceclient.pg.connect-timeout=1s
spring.http.serviceclient.pg.read-timeout=3s
```

```kotlin
@Bean
fun http11(): ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder> {
    return ClientHttpRequestFactoryBuilderCustomizer { builder ->
        builder.withHttpClientCustomizer { it.version(HttpClient.Version.HTTP_1_1) }
    }
}
```

Without the Boot module, `spring-web` alone suffices:

```kotlin
val httpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(1))
    .build()
val requestFactory = JdkClientHttpRequestFactory(httpClient)
requestFactory.setReadTimeout(Duration.ofSeconds(3))
```

MockWebServer scene for "stored, then the response is lost":

```kotlin
override fun dispatch(request: RecordedRequest): MockResponse {
    val created = store(request) // parse JSON body, assign transactionKey, status PENDING
    if (loseNextCreateResponse.getAndSet(false)) {
        return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
    }
    return MockResponse.Builder().code(202).body(json(created)).build()
}
// assert: exactly one POST recorded (server.requestCount / takeRequest), adapter got ResourceAccessException
```

For the query-encoding check: `RestClient`'s default `DefaultUriBuilderFactory` uses `EncodingMode.TEMPLATE_AND_VALUES`, which encodes URI variables including reserved characters ([source](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/util/DefaultUriBuilderFactory.java#L50), [enum doc](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-web/src/main/java/org/springframework/web/util/DefaultUriBuilderFactory.java#L230-L246)). Use an `orderId` with `&`, `+` or a space, and assert the raw `target`/`rawQuery`, not the decoded value.

## Open points

- **Not run:** the JDK resend behaviour is from reading source; a two-request fixture test should confirm it. Lookup-count expectations assume the GET retry described in §5b.
- Jetty `HttpClient` retry behaviour was not researched; it is not on the classpath.
- `mockwebserver3-junit5` on JUnit 6.0.3 is inferred to work, not tested.
- WireMock's h2c default and whether its faults fire after reading the body are undocumented on the pages read.
- A WireMock 3.x + Boot 4.1.1 Jetty mix was not tried. The EE10/EE11 split above is the reason to expect trouble.
