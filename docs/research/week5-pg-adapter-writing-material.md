# Week 5 PG adapter: writing material

Recorded 2026-10-19 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/140#issuecomment-6268654903) of [issue 140](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/140). This is material for the technical writing quest, not the write-up itself. The rules behind it are in [ADR 0021](../adr/0021-payment-is-its-own-aggregate-and-slice.md) and [ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md). The code is [`LocalPgGateway`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGateway.kt), [`LocalPgGatewayConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGatewayConfig.kt), [`PaymentGatewayTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/payment/required/PaymentGatewayTest.kt) and [`TestPg`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPg.kt).

## Issue 140: an accepted create crosses the payment gateway to the test PG

### Landed

[`4bf7721`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/4bf7721) on `feat/pg-adapter` (pushed).

### Acceptance criteria

- [x] **An accepted create returns the PG's transaction, and the PG received exactly one POST with the contract's path, header and JSON types.**
  - `PaymentGatewayTest` gets `Accepted(PgTransaction("TEST-TX-0042", "LP-ORD-0000000042", 7000, PENDING))`, and `createCount()` is 1.
  - The `TestPg` stub matches only `X-USER-ID: 135135` and the exact body (`equalToJson`, `amount` as a JSON number). A wrong header, field or type gets WireMock's 404 and comes back `Unknown`, so the test fails.
  - The test sets `pg.callback-url` to a value different from the test profile's. The matched body therefore proves the URL comes from configuration.
  - A parameterized test shows a 202 is `Accepted` for each of `PENDING`, `SUCCESS` and `FAILED`.
- [x] **Any other response comes back as `Unknown` and is never thrown.**
  - A 500 comes back as `Unknown("HTTP 500")`, after one POST.
  - There is one `catch (RestClientException)`. `ResourceAccessException`, the converter's read errors and the adapter's own `UnreadablePgResponseException` (empty body, unknown status, negative amount) all extend it.
- [x] **Each call writes one log line without a body or card number, and `Card`'s string form masks the number.**
  - Output capture finds exactly one `LocalPgGateway` line per call, e.g. `PG POST /api/v1/payments Accepted (HTTP 202) in 43 ms, pgOrderId=LP-ORD-0000000042, transactionKey=TEST-TX-0042`. `Unknown` logs at WARN.
  - `Card` prints as `Card(type=TEST, number=****)`.
- [x] **The test PG binds 127.0.0.1, and `HexagonalArchitectureTest` knows `adapter.integration`.**
  - The Jetty log shows `NetworkTrafficServerConnector{HTTP/1.1, (http/1.1)}{127.0.0.1:…}`, and a test asserts the bind address and h2c off.
  - The new layer may use domain and application, and no layer may use it. `integrationSlicesAreFreeOfCycles` was added like the other layers' cycle rules.
- [x] **`./gradlew :apps:commerce-api:test --tests '*PaymentGatewayTest'` passes, and `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes with the new dependencies.**
  - `PaymentGatewayTest`: 8 tests, 0 failures.
  - `ktlintCheck check`: 63 classes, 584 tests, 0 failures, 0 skips. The spike's 574 plus 8 here, 1 in `CardTest` and 1 new architecture rule.

### Decisions made while implementing

- **Jetty EE10 BOM.** Boot's version could not be read. Reading `dependencyManagement.importedProperties` resolves the imports, and an import declared after the read is silently ignored: EE10 stayed at 12.1.6.
  - The version is pinned in the catalog as `jettyEe10 = "12.1.12"`.
  - A configuration-time `check` compares it with Boot's `jetty.version` and stops the build on a mismatch.
  - Every Jetty jar on the test classpath now resolves to 12.1.12.
- **Strict mapper.**
  - It also turns `ACCEPT_FLOAT_AS_INT` off. The contract says `amount` is an integer won, and without this `7000.9` reads as 7000.
  - `FAIL_ON_MISSING_CREATOR_PROPERTIES` turned out to be unnecessary. A missing or null `amount` already fails through `FAIL_ON_NULL_FOR_PRIMITIVES` and the Kotlin module.
- **The 202 body is `{transactionKey, orderId, amount, status}`** (assignment §2). `PgTransaction` therefore carries the order number and amount the PG returned, not values copied from the request.
- **Test configuration.** `PaymentGatewayTest` reads `application.yml` with the test profile through `ConfigDataApplicationContextInitializer`. It overrides only `pg.response-timeout=1s` and `pg.callback-url`.
- **`Unknown.cause`** is `HTTP <status>` or the root exception's simple name. Exception messages are not logged, because they can quote fragments of the response.
- **dev, qa and prd** read `${PG_BASE_URL}` and `${PG_CALLBACK_URL}`, the same pattern as `redis.yml`. A missing variable fails on the first call, not at startup.
- **Docs.**
  - ADR 0013 has a dated note for the new layer.
  - ADR 0008's "HTTP Service Clients" row notes that they were not adopted (ADR 0022 rule 5).
  - `CODING_STANDARDS.md` records `PaymentGatewayTest` as a test that inherits no base class.

### For 142 and 143

- **Mapper probe.** A throwaway probe sent these 202 bodies through the gateway; it is not committed.
  - `Unknown`: a string amount, a missing or null amount, `7000.5`, `-1`, an unknown status, an array instead of an object, trailing tokens, an empty body, non-JSON.
  - `Accepted`: an extra field.
  - **Open:** `"transactionKey": ""` comes back `Accepted` with a blank key.
- **`TestPg`** has `acceptsCreate(…, pgStatus)`, `answersCreate(…, httpStatus)` and `createCount()`. Its constructor takes the expected callback URL, which defaults to the test profile's value.
- **The log line already labels `Rejected` as `HTTP 422`.** The `when` over the sealed result needs a branch, and ADR 0022 rule 2 fixes that status.
- **Test logging.** Run alone, the slim context has no Boot logging, so logback's DEBUG default prints HttpClient's wire log, request bodies included. The log assertions therefore look only at the gateway's own lines.
