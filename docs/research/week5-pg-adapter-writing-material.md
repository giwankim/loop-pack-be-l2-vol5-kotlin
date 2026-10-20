# Week 5 PG adapter: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one ticket's closing comment. The rules behind it are in [ADR 0021](../adr/0021-payment-is-its-own-aggregate-and-slice.md) and [ADR 0022](../adr/0022-only-the-pre-create-rejection-proves-no-pg-transaction.md).

## Issue 140: an accepted create crosses the payment gateway to the test PG

Recorded 2026-10-19 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/140#issuecomment-6268654903) of [issue 140](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/140). The code is [`LocalPgGateway`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGateway.kt), [`LocalPgGatewayConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/pg/LocalPgGatewayConfig.kt), [`PaymentGatewayTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/payment/required/PaymentGatewayTest.kt) and [`TestPg`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPg.kt).

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

## Issue 139: a payment is created as result unknown and saved once per order

Recorded 2026-10-20 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/139#issuecomment-6280498373) of [issue 139](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/139). The code is [`Payment`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/payment/Payment.kt), [`PaymentRepository`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/payment/required/PaymentRepository.kt), [`scalar-foreign-keys-drop.sql`](../../apps/commerce-api/src/main/resources/scalar-foreign-keys-drop.sql) and [`PaymentRepositoryTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/payment/required/PaymentRepositoryTest.kt).

### Landed

[`79a349f`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/79a349f) on `feat/pg-adapter` (pushed).

### Acceptance criteria

- [x] **A new payment is `UNKNOWN` with `pgOrderId` `LP-ORD-%010d`; a blank or over-64-character key and an amount under 1 are rejected at creation.**
  - `PaymentTest` (12 tests): a new payment is `UNKNOWN` and carries its order, key and amount.
  - Orders 1, 42 and 12345678901 give `LP-ORD-0000000001`, `LP-ORD-0000000042` and `LP-ORD-12345678901`. Ten digits is a minimum width, so a longer id is not cut.
  - Blank keys (`""`, `"   "`, `"\t\n"`) and a 65-character key throw `InvalidPaymentException`; 64 characters is kept. Zero won throws and one won is kept. A negative amount never reaches `Payment`, because `Money` rejects it first.
- [x] **Two payments for one order, or two with the same idempotency key, are rejected by the database.**
  - `PaymentRepositoryTest`: the second `save` throws `DataIntegrityViolationException` for each unique key.
  - A payment for an order that does not exist is refused by the foreign key.
- [x] **Payments are found by order id and by idempotency key, null when absent, and a deleted payment is absent.**
  - A saved payment reads back through both lookups after flush and clear.
  - An order without a payment and an unknown key both give null. A soft-deleted payment is found by neither.
- [x] **The table has no card column, and the two named unique keys exist.**
  - `information_schema.columns` lists exactly `id`, `order_id`, `idempotency_key`, `pg_order_id`, `amount`, `status`, `created_at`, `updated_at` and `deleted_at`. The list is exact on purpose: an `@Embedded Card` would add `type` and `number`, which a check for column names containing "card" would miss.
  - `information_schema.statistics` has `UK_PAYMENT_ON_ORDER_ID` on `order_id` and `UK_PAYMENT_ON_IDEMPOTENCY_KEY` on `idempotency_key`. `key_column_usage` has `FK_PAYMENT_ON_ORDER_ID` → `orders.id`.
  - A mutation run without the two unique keys and `@SQLRestriction` failed exactly the four tests that should fail.
- [x] **`./gradlew :apps:commerce-api:test --tests '*PaymentTest' --tests '*PaymentRepositoryTest'` passes, and `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes.**
  - Both exit 0.
  - `ktlintCheck check`: 66 classes, 606 tests, 0 failures, 0 skips. That is the previous 584 plus 12 in `PaymentTest`, 8 in `PaymentRepositoryTest` and 2 in `PaymentFixturesTest`.

### Decisions made while implementing

- **The table is `payment`, not `payments`.** The repository names a table only to avoid a reserved word (`users`, `likes`, `orders`), and `payment` is not one. The keys follow: `UK_PAYMENT_ON_ORDER_ID`, `UK_PAYMENT_ON_IDEMPOTENCY_KEY`, `FK_PAYMENT_ON_ORDER_ID`. [Spec 138](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/138) has a dated note on its storage line.
- **The foreign key needed a drop script.**
  - Hibernate drops tables in name order and leaves script-made foreign keys in place. `payment` sorts after `orders`, so on the next recreation MySQL refused to drop `orders` (error 3730), and then `users`.
  - All test contexts share one MySQL container per JVM, so every context after the first hit it. `OrderApiTest`'s recreation test failed with exactly `Cannot drop table 'orders' referenced by a foreign key constraint`.
  - `scalar-foreign-keys-drop.sql` drops the key before Hibernate's own drop (`jakarta.persistence.schema-generation.drop-source: script-then-metadata`, local and test profiles). Both `ddl-auto=create` and `SchemaManager.dropMappedObjects` read it (Hibernate 7.4 `SchemaManagementToolCoordinator`, `SchemaManagerImpl`).
  - An empty schema logs one more warning, because the table does not exist yet.
  - Not chosen: `set foreign_key_checks = 0` in the drop script. The DDL connection goes back to the Hikari pool, so the app's queries could run with foreign key checks off.
  - The recreation test now covers `payment`. The rule is in `CODING_STANDARDS.md`, and points-orders design 8.2 has a dated note. A migration tool is to be evaluated in [issue 145](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/145).
- **`pgOrderId` does not go through `String.format`.** `%d` uses the JVM default locale's digits: under `th-TH-u-nu-thai` it gave `LP-ORD-๐๐๐๐๐๐๐๐๔๒`. The number is built with `padStart`, and a test pins ASCII digits under that locale.
- **Fixture.** `createPayment(orderId, idempotencyKey?, amount?)` calls the real constructor, because the constructor computes fields (ADR 0010, as for `Order`).
  - Keys are UUID strings, so separately made payments never collide on the unique key. Amounts are 1..1,000,000,000 won.
  - `preparePayment` pays the order's total.
- **One exception, `InvalidPaymentException`, for both creation rules,** as `InvalidOrderException` is for the order.

### For 141 and step 2

- **Open: the key column compares case-insensitively.** `idempotency_key` uses the server collation `utf8mb4_general_ci`, so `abc`, `ABC` and `abc ` are one key, both for the unique key and for `findByIdempotencyKey`. The withdrawn design 12.2 chose `utf8mb4_bin` for the old charge key. This is left to step 2, which settles the key's request shape (spec: "고객이 멱등 키를 보내는 모양").
- **The column list in `PaymentRepositoryTest` is exact.** 141 adds the transaction key and the conflict fields, so it updates the list too.
- `pg_order_id` has no index or unique key yet. The step that adds a lookup by PG order number adds one (spec: "거래 키나 PG 주문 번호로 찾는 것은 그것을 쓰는 단계가 더한다").
- There is no CHECK on `amount > 0`. Orders have one; products and point accounts do not.
