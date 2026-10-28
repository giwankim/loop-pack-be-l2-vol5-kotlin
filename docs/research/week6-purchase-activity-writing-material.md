# Week 6 purchase activity: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 2 ticket's closing comment. The rules behind it are in [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) and the [purchase activity design](../design/purchase-activity.md). The step is [spec 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176).

## Issue 177: the streamer becomes a testable app with the listener factory it will consume order events with

Recorded 2026-10-28 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177#issuecomment-6466319429) of [issue 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177). The rules are rules 5 to 7 of [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) (stop on an unprocessable event, acknowledge after the commit, the listener's promises belong to the app). The decisions are the "streamer의 정리" and "리스너" sections of [spec 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176), and sections 5 and 7 of the [purchase activity design](../design/purchase-activity.md). There is no model, recorder or listener yet. The code is:

- [`OrderEventsListenerConfig`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventsListenerConfig.kt) and [`OrderEventsProperties`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventsProperties.kt): the record listener factory and the topic name
- [`application.yml`](../../apps/commerce-streamer/src/main/resources/application.yml): the name `commerce-streamer`, the group, `earliest`, no topic auto-creation, and the demo listener off under `test`
- [`kafka.yml`](../../modules/kafka/src/main/resources/kafka.yml): `value-serializer` → `value-deserializer`, the only change in `modules:kafka`
- [`DemoKafkaConsumer`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/DemoKafkaConsumer.kt): moved from `interfaces.consumer` to `adapter.consumer`, with an `autoStartup` property
- [`ApplicationService`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/support/stereotype/ApplicationService.kt), [`ValidatedApplicationService`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/support/stereotype/ValidatedApplicationService.kt) and [`UnprocessableOrderEventException`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/support/error/UnprocessableOrderEventException.kt): the copies and the marker type
- [`BaseApplicationServiceTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt), [`BaseConsumerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseConsumerTest.kt) and [`TestOrderEventsTopicConfig`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/TestOrderEventsTopicConfig.kt): the two bases and the test-declared topic
- [`CommerceStreamerContextTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/CommerceStreamerContextTest.kt), [`OrderEventsListenerConfigTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/OrderEventsListenerConfigTest.kt) and [`DemoKafkaConsumerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/DemoKafkaConsumerTest.kt): the three seams
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the streamer bullet under 도우미; the [purchase activity design](../design/purchase-activity.md): four dated notes in sections 5 and 7

### Landed

Two commits on `feat/purchase-activity`, on top of `a2bd12c`:

- [`7b42ed8`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/7b42ed8) is the code, the tests, the two YAML files, the standards and the design notes.
- The commit after it adds this file with this section.

### Acceptance criteria

- [ ] **`CommerceStreamerContextTest` starts the full context on the two fixtures; a context without the Kafka import fails as commerce-api's does.** Half met. The context test imports `MySqlTestContainersConfig` and `KafkaTestContainersConfig`, as `BaseApplicationServiceTest` does, and the two share one cached context (10 s for the first class, 0.1 s and 0.03 s for the next two). The second half does not hold for that context. A throwaway probe removed the Kafka import, and the context **started**: no `BOOTSTRAP_SERVERS` was set, and nothing touched the broker. `KafkaAdmin.initialize()` builds an admin client only when the context has a `NewTopic` bean (spring-kafka 4.1.1, `if (!newTopics.isEmpty())`). The streamer declares no topic, and under `test` its only listener container, the demo's, no longer starts. commerce-api fails only because it declares `order-events`. The second probe ran a `BaseConsumerTest` subclass, whose context declares the test topic, without the Kafka import, and it **failed** as commerce-api's does: `IllegalStateException: Could not create admin` ← `KafkaException: Failed to create new KafkaAdminClient` ← `ConfigException: Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`. Before this ticket, the demo container auto-started three consumers in the test profile, which the first context run's log shows. Design section 7 has a dated note, and the base's KDoc says it as it is. The `order-events` listener in issue 180 starts with every context, so that ticket re-runs the probe.
- [x] **A test reads the `order-events` listener container factory's consumer configuration and finds `enable.auto.commit=false` and the value deserializer `ByteArrayDeserializer`; the factory's ack mode is `MANUAL_IMMEDIATE` and its concurrency 3.** `OrderEventsListenerConfigTest` reads `factory.consumerFactory.configurationProperties`. That map holds only explicit settings, so a client default cannot stand in. The factory has no concurrency getter, so ack mode and concurrency are read from `factory.createContainer("order-events")`, a container the factory configures but nobody starts. A third test, beyond the criterion, finds `group.id=commerce-streamer`, `auto.offset.reset=earliest` and `allow.auto.create.topics=false`. **Red check:** with only the deserializer set, the map read `"enable-auto-commit"="false"`, `"auto.offset.reset"="latest"` and `"group.id"="loopers-default-consumer"`, and had no `enable.auto.commit` key.
- [x] **The demo listener's container is not running in the test profile.** `DemoKafkaConsumerTest` finds the container by its topic, because it has no listener id, and asserts `isRunning == false`. Finding it at all shows the bean is still registered. **Red check:** before `autoStartup = "\${demo-kafka.auto-startup:true}"`, the assertion failed. The context test's log had already shown three consumers of group `loopers-default-consumer` running on the test broker.
- [x] **`CODING_STANDARDS.md` gains the streamer's two bases and the test-declared topic under "도우미".** One bullet with four sub-bullets at the end of the base list: the two bases, the test-declared topic, and the assignment wait. It also covers the copied test resources and the context test that shares the base's `@Import`.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check :modules:kafka:ktlintCheck` passes; `./gradlew :apps:commerce-api:check` still passes after the YAML fix.** Streamer: **3 classes, 5 tests, 0 failures, 0 skipped**, where it had no tests before. commerce-api: **79 classes, 943 tests, 0 failures, 0 skipped**, the same counts as at `f792658` before this branch. `BOOTSTRAP_SERVERS` was unset in both shells.

### Red checks and probes

Each probe was a throwaway test, deleted before the commit.

- **`redis.yml`.** With `modules:redis` removed but `redis.yml` still imported, the context test failed with `ConfigDataResourceNotFoundException`. Removing the import made it pass.
- **The `kafka.yml` typo.** The template's `consumerFactory` bean read values with `StringDeserializer`, Boot's default, before the fix, and with `ByteArrayDeserializer` after it. The streamer's factory sets its deserializer explicitly, so only this probe sees the fix.
- **The YAML document order.** With the streamer's Kafka block in the document that imports `kafka.yml`, `auto.offset.reset` and `allow.auto.create.topics` landed, but `group.id` stayed `loopers-default-consumer`. In a second document below it, all three landed.
- **The factory end to end.** A `BaseConsumerTest` subclass with a temporary `@KafkaListener` on the factory confirmed several things:
  - the topic was created with 3 partitions;
  - the base's wait saw all three assigned;
  - a JSON map sent through `modules:kafka`'s `KafkaTemplate` arrived as a Jackson `ObjectNode` on consumer thread `probe-0-C-1`, at partition 0, offset 0;
  - the ack committed offset 1 on partition 0.

  The first run gave the listener `id = "probe"`, and its group was **`probe`**, not `commerce-streamer`; the committed offsets of `commerce-streamer` were empty. `@KafkaListener.idIsGroup` defaults to true and uses the id as the group. With `idIsGroup = false`, the group was `commerce-streamer`, and each of the three consumers took one partition.

### Decisions made while implementing

- **The factory sets `enable.auto.commit=false` itself.** `kafka.yml` writes `consumer.properties.enable-auto-commit: false`. The raw `properties` map passes keys to Kafka as written, and Kafka's key is `enable.auto.commit`, so the line does nothing. The container saves the template anyway: `determineAutoCommit` sets `"false"` when the factory leaves the key out, and refuses to start a manual ack mode with auto-commit on. Without the explicit put, though, the criterion's map has nothing to find, and `MANUAL_IMMEDIATE`'s precondition rests on a container default. The module's dead key stays, because the ticket allows only the typo fix there and the spec puts template cleanup out of scope. Design section 5 has a dated note.
- **The streamer's Kafka block is a document of its own.** `spring.config.import` behaves as if the imported file sat directly below the document that imports it, and later documents win. So the same key in the importing document loses to the module's file. `earliest` had landed anyway, because it lives in `consumer.properties`, which `buildConsumerProperties()` puts after the common `properties` that hold the template's `latest`. The `application.yml` comment and a dated note in design section 5 say why the block sits where it does.
- **`BaseConsumerTest` extends `BaseApplicationServiceTest` with `NOT_SUPPORTED`,** as commerce-api's committing base does, so the `prepare` helpers later tickets add serve both.
- **`BaseConsumerTest` finds containers by topic, not by listener id,** so the base does not name issue 180's `id = "order-events"` before it exists. If no container listens to the class's topic, the base fails at once instead of letting a scene wait out its timeout.
- **The test topic config is a top-level class.** Nested in the base, it made Spring Framework 7 warn that a nested configuration class in a test's hierarchy is "default configuration": ignored today, picked up from 7.1. It lives in `com.loopers.support`, like commerce-api's `Test*Config` classes.
- **`META-INF/services/org.junit.jupiter.api.extension.Extension` is copied too.** The ticket names only the two properties files, but the copied `junit-platform.properties` turns on extension autodetection for exactly this file (`InstancioExtension`).
- **The stereotypes are verbatim copies plus one English paragraph each.** `ApplicationService`'s Korean KDoc keeps its commerce-api examples (`OrderPaymentSaga`, the PG services), and its added line says "examples included". A `diff` against commerce-api shows only the added paragraph.
- **The factory bean is named `orderEventsListenerContainerFactory`** behind the constant `ORDER_EVENTS_LISTENER`, following commerce-api's `ORDER_EVENTS_EXECUTOR = "orderEventsExecutor"`.

### Review

A two-axis review ran before the commit.

- **Standards:** no hard violations. Its process finding, that the code departs from design sections 5 and 7 without dated notes, was fixed with four notes: the explicit auto-commit, `idIsGroup`, the YAML document order, and the context that starts without Kafka. **Kept:**
  - the container-by-topic lambda in `DemoKafkaConsumerTest` and `BaseConsumerTest` (two copies);
  - the `kafka.yml` key, since fixing it is out of scope;
  - the copied KDoc's commerce-api examples;
  - the config tests' paired names, since the criterion pairs those properties.
- **Spec:** the first criterion's second half and the explicit put went into the notes above. The writing material is this file, and commerce-api's check has since passed. Its hazard finding is real: the factory test cannot see the group a listener joins, because `idIsGroup` overrides it. The two factory tests now say "the order events factory's consumers", and the test's KDoc states the limit.

### Left for later

- **Issue 180's listener needs `idIsGroup = false`,** or it joins group `order-events`. Its test should assert the running container's `groupId`, since the factory test cannot see the override (dated note in design section 5).
- **Issue 180 re-runs the context probe.** Once the `order-events` listener starts with the context, a context without the Kafka import should fail. That is expected, not yet seen.
- **Not probed: the listener's topic placeholder.** `@KafkaListener(topics = ["\${order-events.topic}"])` resolves against the `Environment`, which does not see `OrderEventsProperties`' Kotlin default. Outside tests that override it, the placeholder needs a default (`"\${order-events.topic:order-events}"`) or a SpEL reference to the properties bean.
- **Not observed: one group across cached contexts.** Each `BaseConsumerTest` class starts a context of its own, and each will run consumers in group `commerce-streamer` on its own topic. Under the classic group protocol, every join rebalances the whole group, including the containers of contexts already cached.
- **Spec 176's body** could take dated notes for the explicit put and the first criterion's second half, alongside the design's.
- `kafka.yml`'s dead `enable-auto-commit` key waits for the template cleanup the spec puts out of scope.

## Issue 178: processed order confirmations and purchase activity are two roots with their own repositories

Recorded 2026-10-28 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/178#issuecomment-6467052939) of [issue 178](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/178). The rules are rules 1 and 2 of [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) (the streamer knows orders and products by ID only; two roots, not one aggregate), and the model is section 3 of the [purchase activity design](../design/purchase-activity.md). Nothing reads Kafka yet, and nothing records. The code is:

- [`ProcessedOrderConfirmation`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/domain/purchaseactivity/ProcessedOrderConfirmation.kt) and [`PurchaseActivity`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/domain/purchaseactivity/PurchaseActivity.kt): the two roots
- [`ProcessedOrderConfirmationRepository`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/required/ProcessedOrderConfirmationRepository.kt) and [`PurchaseActivityRepository`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/required/PurchaseActivityRepository.kt): the Spring Data ports
- [`ProcessedOrderConfirmationFixtures.kt`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/domain/purchaseactivity/ProcessedOrderConfirmationFixtures.kt) and [`PurchaseActivityFixtures.kt`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/domain/purchaseactivity/PurchaseActivityFixtures.kt), each with its contract test
- [`PurchaseActivityTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/domain/purchaseactivity/PurchaseActivityTest.kt) and [`ProcessedOrderConfirmationTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/domain/purchaseactivity/ProcessedOrderConfirmationTest.kt): the unit tests
- [`ProcessedOrderConfirmationRepositoryTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/application/purchaseactivity/required/ProcessedOrderConfirmationRepositoryTest.kt) and [`PurchaseActivityRepositoryTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/application/purchaseactivity/required/PurchaseActivityRepositoryTest.kt): the repository tests
- [`BaseApplicationServiceTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt): `entityManager`, `prepareProcessedOrderConfirmation`, `preparePurchaseActivity` and the two `delete…` helpers
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the streamer's repository tests under 도우미, the drawn IDs under Fixture; the [purchase activity design](../design/purchase-activity.md): dated notes in sections 3 and 7

### Landed

Two commits on `feat/purchase-activity`, on top of `78cc2fa`:

- [`f64a186`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/f64a186) is the code, the tests, the standards and the design notes.
- The commit after it adds this section.

### Acceptance criteria

- [x] **Unit tests: a non-positive quantity is refused; `confirmedAt` is stored to micro precision.** `PurchaseActivityTest` builds activities of quantity 0, -1 and `Int.MIN_VALUE` and gets `IllegalArgumentException("A purchase activity's quantity must be positive, but was …")` from the constructor. Both roots' unit tests pass `2026-10-27T01:02:03.123456789Z` and hold `…03.123456Z`. The repository tests read the same seven-digit input back as `…03.123456Z` after flush and clear, so the column keeps what the entity holds. **Red checks:** the tests did not compile before the entities existed, and the micros test failed before the constructor cut the time.
- [x] **Repository tests: the two unique keys and the CHECK exist under their names in the created schema and a violating save fails; a deleted row is not found.** Both tests extend the streamer's `BaseApplicationServiceTest`; the ticket allowed it.
  - **Under their names.** Each table's test reads every row of `information_schema.table_constraints` for the table, so the list also shows there is no foreign key. `processed_order_confirmation` has exactly `PRIMARY`, `UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID` and `UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID`. `purchase_activity` has exactly `PRIMARY`, `UK_PURCHASE_ACTIVITY_ON_ORDER_ID_AND_PRODUCT_ID` and `CK_PURCHASE_ACTIVITY_ON_QUANTITY_POSITIVE`. `information_schema.statistics` gives the key columns, `order_id,product_id` in that order for the pair.
  - **A violating save fails.** A second confirmation of the same event, a confirmation of the same order under another event ID (`order-confirmed:v2:1`), and a second activity for the same order and product each throw `DataIntegrityViolationException` on `save`, with that key's name in the root cause. The ID is IDENTITY, so the save is the INSERT. The CHECK cannot be reached by a save, because the constructor refuses the quantity first, so a native `UPDATE purchase_activity SET quantity = 0` (and -1) shows MySQL refusing it with 3819 (`ER_CHECK_CONSTRAINT_VIOLATED`). Design section 7 has a dated note.
  - **A deleted row is not found.** A deleted confirmation is found by neither its event ID nor its order ID. Of two activities of one order, the deleted one is gone from `findAllByOrderId` and the other is still there.
  - **Red checks:** before the `@Table` constraints, every violating save went through and the constraint lists held only `PRIMARY`. Before `@SQLRestriction`, the deleted rows were found.
- [x] **`CommerceStreamerContextTest` still starts and commerce-api's schema is untouched (no new table in its context).** The context test passes and pays the context start (11 s); the two repository classes then run in 0.46 s and 0.11 s, so they share its cached context. The log shows the context creating the two new tables. commerce-api's `testRuntimeClasspath` dependency report (1,072 lines) has the projects `modules:jpa`, `modules:redis`, `modules:kafka`, `supports:jackson`, `supports:logging` and `supports:monitoring`, and no `commerce-streamer`, so its `@EntityScan("com.loopers")` cannot see these entities. `scalar-foreign-keys.sql` is unchanged. commerce-api's `check` was not run; design section 11 leaves that to the last ticket.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes with executed test counts checked.** **9 classes, 28 tests, 0 failures, 0 skipped**, up from 3 classes and 5 tests at issue 177. The new six classes hold 23 tests: unit 5, fixture contracts 5, repositories 13.

### Red checks and probes

Each probe was a temporary edit, undone before the commit.

- **MySQL rounds, it does not truncate.** With the constructor's cut removed, the confirmation saved as `…03.123456789Z` read back as **`…03.123457Z`**. MySQL rounds a seventh fractional digit into `datetime(6)`. So the cut is not tidiness: issue 179 compares a redelivered event's `confirmedAt` with the stored one (ADR 0033 rule 3), and an uncut nanosecond time would come back a microsecond later and turn the event's own redelivery into a conflict. The round-trip tests now catch a missing cut too.
- **The contract tests can fail.** Widening the quantity default to 1..11 failed `PurchaseActivityFixturesTest`.
- **Instancio's `past()`** draws from `[1970-01-01T00:00Z, now)` (instancio-core 5.0.2, `Constants.DEFAULT_MIN`), inside `datetime(6)`'s range, so drawn defaults always save. The contract tests assert that range.
- **A Kafka container flake, not reproduced.** One repository-test run failed at context load: the Testcontainers broker `apache/kafka-native:4.3.2` exited with code 1 before logging `Transitioning from RECOVERY to RUNNING`. Docker had no leftover containers. The rerun, with no change, passed, and every run since has.

### Decisions made while implementing

- **The fixtures call the real constructors.** Each constructor cuts `confirmedAt` to microseconds, which is computing a field, and ADR 0010 sends such entities to their constructors. An Instancio-built entity would skip the cut and could hold nanoseconds the column rounds. So there is no `unsaved()` copy in the streamer.
- **The fixtures draw the order and product IDs.** `CODING_STANDARDS.md` says a fixture takes other aggregates' IDs without a default. In this context they are values, not other aggregates, and nothing has a foreign key (ADR 0033 rule 1), so the fixtures draw them over the whole positive `Long` range. The default event ID is derived from the order ID as commerce-api derives it, `order-confirmed:v1:{orderId}`, so a default confirmation looks like what arrives. The standards file now records the exception next to the order line item's.
- **Repository tests on the service base.** The streamer gets no `@DataJpaTest` base. Its repository tests run in the context the other tests already cache, so the streamer keeps the two bases design section 7 names. No provided port records anything yet, so `prepare…` saves a fixture through its repository and `delete…` saves explicitly. The base's KDoc, the standards' streamer bullet and a dated note in design section 7 say so.
- **The ports have `save` and the lookups, not `saveAll`.** Spec 176's port line lists `saveAll` on `PurchaseActivityRepository`; ticket 178 lists only `findAllByOrderId`. `save` serves the `prepare` helper and the unique-key test. `saveAll` arrives with the recorder in issue 179, its first caller. The two tables need only derived queries, so `adapter.persistence.purchaseactivity` does not exist yet. A dated note in design section 3 records both.
- **The entity refuses with `IllegalArgumentException`.** Design section 6 lists "엔티티의 `IllegalArgumentException`" among the exceptions that stop the consumer, and the recording request's Bean Validation refuses the quantity first. commerce-api's entities throw domain exceptions instead, because their messages reach HTTP responses; the streamer's reach a log line.
- **No `@NaturalId`.** ADR 0016 rule 6 is `Like`'s. These roots have named unique keys, as `Payment` does.

### Review

A two-axis review ran before the commit.

- **Standards:** no hard violations. **Fixed:** the drawn-ID exception went into `CODING_STANDARDS.md`; the fixture contract tests now check the uniqueness their KDoc promises (`doesNotHaveDuplicates` on drawn order IDs, event IDs and order–product pairs), as `PaymentFixturesTest` does; a test name said "items" for a quantity; the fixture local `confirmedOrderId` became `fixedOrderId`, after `PaymentFixtures`' `fixedPgOrderId`. **Kept:** the truncation line in both roots and the `information_schema` query pair in both repository tests, as commerce-api inlines them; no dated note in ADR 0012 for the repository tests' base, since the streamer's bases from issue 177 on are recorded in design section 7 and the standards file, not there.
- **Spec:** implements the ticket. It accepted the CHECK shown by an `UPDATE` and the deferred `saveAll`. It raised the `IllegalArgumentException` against ADR 0033 rule 5's single exception type; the entity's KDoc now names design section 6's row, and the gap went to "Left for later".

### Left for later

- **Issue 179 adds `saveAll`.** Once the recorder exists, the standards' rule that `BaseApplicationServiceTest` prepares through provided ports may move `preparePurchaseActivity` behind it. `PurchaseActivityRepository.save` would then serve only tests.
- **The future `DefaultErrorHandler` would retry the entity's `IllegalArgumentException`.** Design section 6's production shape registers only `UnprocessableOrderEventException` and `ConstraintViolationException` as not retryable. Bean Validation should keep the entity's exception unreachable, but the stop-policy study should name it or give the entity a subtype.
- **Spec 176's body** still lists `saveAll` on the port; it could take a dated note like issue 177's.
- **ADR 0012** has no dated note for any of the streamer's bases.

## Issue 179: the recorder reads first and records both roots in one transaction

Recorded 2026-10-28 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179#issuecomment-6467757042) of [issue 179](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/179). The rules are rules 3 and 4 of [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) (read first, in one transaction; tell the same fact from a conflict), and the recording is section 4 of the [purchase activity design](../design/purchase-activity.md). Nothing reads Kafka yet; the listener is issue 180. The code is:

- [`PurchaseActivityRecorder`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/provided/PurchaseActivityRecorder.kt), [`PurchaseActivityRecordRequest`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/provided/PurchaseActivityRecordRequest.kt), [`PurchaseActivityRecordResult`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/provided/PurchaseActivityRecordResult.kt) and [`PurchaseActivityConflictException`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/provided/PurchaseActivityConflictException.kt): the provided port and its types
- [`PurchaseActivityModifyService`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/PurchaseActivityModifyService.kt): the implementation
- [`PurchaseActivityRepository`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/application/purchaseactivity/required/PurchaseActivityRepository.kt): `saveAll`
- [`PurchaseActivityRecorderTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/application/purchaseactivity/provided/PurchaseActivityRecorderTest.kt): the port's scenes
- [`PurchaseActivityFixtures.kt`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/domain/purchaseactivity/PurchaseActivityFixtures.kt): `createPurchaseActivityRecordRequest`, with its contract tests in [`PurchaseActivityFixturesTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/domain/purchaseactivity/PurchaseActivityFixturesTest.kt)
- [`BaseApplicationServiceTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt): `prepareRecordedEvent`
- [`build.gradle.kts`](../../apps/commerce-streamer/build.gradle.kts): the validation starter on the compile path
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the streamer's prepare rule under 도우미, the new fixture under Fixture; the [purchase activity design](../design/purchase-activity.md): dated notes in section 4

### Landed

Two commits on `feat/purchase-activity`, on top of `ad38c0a`:

- [`d0e9473`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/d0e9473) is the code, the tests, the standards and the design notes.
- The commit after it adds this section.

### Acceptance criteria

- [x] **On `BaseApplicationServiceTest`: a new request yields one processed confirmation and one activity row per item, all with the request's `confirmedAt`; the result is `RECORDED`.** The request is the fixture's, with 1..5 drawn items. After flush and clear the test reads both tables whole: exactly one confirmation with the request's event ID, order ID and time, and one activity per item as (order, product, quantity, time) in any order. **Red check:** `NotImplementedError` from the empty service, so the context, the validation proxy and the port were all wired before any logic existed.
- [x] **The same request again is `ALREADY_RECORDED` and no row is added; a request whose items are in a different order is also `ALREADY_RECORDED`.** "No row is added" compares every row of both tables, ID included, before and after the call, so an added, removed or changed row shows. **Red check:** before the event-ID read, recording the same request again failed with `DataIntegrityViolationException` on `UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID`. That is step 1's shape, where the common redelivery becomes the exception path, which ADR 0033 rule 3 rejected. The reordered items passed at once, since the items compare as a map. **Beyond the criterion:** a fact recorded with `…03.123456789Z` and sent again is `ALREADY_RECORDED` (below).
- [x] **Same eventId with a different quantity, a different product set, a different `confirmedAt`, or a different orderId throws `PurchaseActivityConflictException`; same orderId under a different eventId throws it too; no row changes in any of these.** Each test records an event, sends a copy with one value changed, and asserts the whole message and the unchanged rows:
  - quantity: `items (product=quantity) recorded {1=2, 2=1}, incoming {1=3, 2=1}`
  - product set: `items (product=quantity) recorded {1=2, 2=1}, incoming {1=2, 3=1}`
  - time, one microsecond apart, the smallest difference the column keeps: `confirmation time recorded 2026-10-27T01:02:03.123456Z, incoming 2026-10-27T01:02:03.123457Z`
  - order: `Order confirmed event order-confirmed:v1:1 of order 2 conflicts with the recorded one: order ID recorded 1, incoming 2.`
  - event ID: `Order confirmed event order-confirmed:v2:1 of order 1 conflicts with the recorded one: event ID recorded order-confirmed:v1:1, incoming order-confirmed:v2:1.`

  **Red checks:** each failed with "nothing was thrown" until its comparison existed. The event-ID case failed with `DataIntegrityViolationException` on `UK_PROCESSED_ORDER_CONFIRMATION_ON_ORDER_ID` until the order-ID read existed.
- [x] **Empty items, a zero quantity and a repeated product ID are refused before anything is written.** Each throws `ConstraintViolationException` with one message, and both tables are empty afterwards: `An order confirmed event must have at least one item.`, `An item's quantity must be positive.`, `An order confirmed event must name each product once.` These passed on the first run, because the constraints came with the request type, so the mutants below are their red checks.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes with executed test counts checked.** With `:modules:kafka:ktlintCheck` as design section 11 lists: **10 classes, 43 tests, 0 failures, 0 skipped**, up from 9 classes and 28 tests at issue 178. The new tests are `PurchaseActivityRecorderTest` 12 and three request-fixture contracts. The counts come from the JUnit XML reports after deleting the old ones. `BOOTSTRAP_SERVERS` was unset. The pre-commit hook's tree-wide `ktlintCheck` passed.

### Red checks and probes

Each mutant was a temporary edit, run and undone before the commit.

- **Comparing the raw request time.** The same fact recorded with `…03.123456789Z` became a conflict with itself: `confirmation time recorded 2026-10-27T01:02:03.123456Z, incoming 2026-10-27T01:02:03.123456789Z`. Issue 178 found MySQL rounds a seventh digit; the entities cut it, so the comparison must use the cut value too.
- **No `@Valid` on the port's parameter.** All three refusals failed, each differently. An empty event was **recorded**: a confirmation with no activities, with nothing thrown. A zero quantity reached the entity's `IllegalArgumentException`. A repeated product reached `DataIntegrityViolationException` on the activities' unique key, after the confirmation's INSERT. So Bean Validation is the only guard against an empty event, and the only guard that keeps a repeated product from looking like a race.
- **No `@AssertTrue`.** The repeated product failed alone, with the same `DataIntegrityViolationException`.
- **No `truncatedTo(MICROS)` in the request fixture.** The contract test failed on drawn times such as `2003-04-05T04:15:49.998859357Z`. Instancio's temporal generator (instancio-core 6.1.0, `JavaTimeTemporalGenerator`) draws the nanosecond of the second at random, and its spec has `truncatedTo`.

### Decisions made while implementing

- **The service is `PurchaseActivityModifyService`, not the ticket's `PurchaseActivityRecorderService`.** ADR 0013 has a slice's write port implemented by `<X>ModifyService`, and design section 3 puts the streamer on that structure. The review caught it; design section 4 has a dated note.
- **The incoming fact is compared as the entities it would be saved as.** The service builds the confirmation and the activities from the request before it reads, compares those, and saves them as they are when the event is new. The microsecond cut stays in the entities' constructors, in one place.
- **The items compare as a map from product to quantity.** Each product appears once on both sides, by the activities' unique key and by the request's validation, so the map is the ticket's set of (productId, quantity). Sorted by product, it is also what the message prints.
- **A repeated product is refused by the request's `@get:AssertTrue`.** Spec 176 allowed the entity's check or the request's. The entity is one row and cannot see a repeat. In the request it becomes a `ConstraintViolationException`, which design section 6's production shape already registers as not retryable, instead of a `DataIntegrityViolationException` that issue 180's adapter would retry as a race.
- **Bare constraint annotations, not the ticket's `@field:`.** The convention plugin passes `-Xannotation-default-target=param-property`, so a bare annotation on a constructor property lands on the field too. commerce-api's requests are written that way, and the zero-quantity test shows the cascade reaching the item.
- **Every constraint has an English message.** Hibernate Validator's default messages follow the JVM locale, so an explicit message keeps the tests and the log line the same on every machine.
- **The message names the incoming event and order IDs**, then each difference as `<name> recorded <value>, incoming <value>`, joined by `; `. Issue 180's ERROR line can carry it with the partition and offset.
- **`prepareRecordedEvent` goes through the port** (ADR 0012) and returns the request it recorded, because the port returns only what it did; it sets no entity field. Tests send that request again, or a `copy` with one value changed. **The repository tests keep their repository prepares.** They stand where commerce-api's `BaseRepositoryTest` does, below the port. Through the port, every repository test would fail whenever recording broke, and an order's activities could not be prepared one at a time. This answers issue 178's first "Left for later" item: `preparePurchaseActivity` stays.
- **The request fixture draws microsecond times,** as commerce-api publishes them, so a stored time equals the request's. The sub-microsecond case is a named value in its own test.

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards:** one hard finding, the service name against ADR 0013. **Fixed:** the rename; the standards bullet had called a lone root "a state the port blocks", which contradicted the Fixture rule that such helpers show the bypass in their name, so it now gives the real reason; the Fixture bullet lists the new fixture; `difference`/`mismatch` became `differenceOrNull`/`difference`. **Kept:** the message is framed by the exception and each difference is formatted by the service (a `Difference` type would give the exception the whole format, but no caller needs the structure yet); the event-ID derivation in both fixtures; the expected messages spelled out in each test; the comparison in the service, where ADR 0033 rule 3 puts it.
- **Spec:** implements the ticket, with this section then pending. It noted the bare annotations, the `@AssertTrue`, the additions beyond the ticket (`prepareRecordedEvent`, the standards edits, the sub-microsecond test), and that the glossary's 같은 사실 says the time compares "as a time" without naming the stored precision.

### Left for later

- **The glossary's 같은 사실** could say the time compares at the microsecond precision it is stored with. Not edited here; `CONTEXT.md` is the grilled vocabulary.
- **Spec 176's body** names `PurchaseActivityRecorderService` and `@field:` annotations; it could take a dated note under its 기록 bullets.
- **Issue 180** calls the port once more on `DataIntegrityViolationException`, and its ERROR line can use the conflict's message.
- **The entity's `IllegalArgumentException`** is now reachable only with Bean Validation off (the mutant above). Issue 178's note for the stop-policy study stands.
- **commerce-api's `check`** was not run; design section 11 leaves it to the last ticket.

## Issue 180: the listener records what reaches the broker and acknowledges after the commit

Recorded 2026-10-28 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180#issuecomment-6469017935) of [issue 180](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/180). The rules are rules 3, 6 and 7 of [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) (read first and settle a race by calling again; acknowledge after the commit; the listener's promises belong to the app), and the listener is sections 2 and 5 of the [purchase activity design](../design/purchase-activity.md). The failure scenes and the week 7 consumed case are issue 181. The code is:

- [`OrderEventsListener`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventsListener.kt): the inbound Kafka adapter
- [`OrderEventMessage`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventMessage.kt) and [`OrderConfirmedPayload`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderConfirmedPayload.kt): the reader DTOs
- [`UnsupportedOrderEventException`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/UnsupportedOrderEventException.kt) and [`MalformedOrderEventException`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/MalformedOrderEventException.kt): two more `UnprocessableOrderEventException`s
- [`OrderEventsProperties`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventsProperties.kt): `DEFAULT_TOPIC`, which the listener's placeholder repeats
- [`OrderEventsListenerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/OrderEventsListenerTest.kt): the broker-to-tables scenes, with the hand-written envelope helpers
- [`BaseConsumerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseConsumerTest.kt): a settled assignment and a fail-fast on a stopped container; [`BaseApplicationServiceTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt): its KDoc on the Kafka import
- [`build.gradle.kts`](../../apps/commerce-streamer/build.gradle.kts): the Kafka test starter now stays for `KafkaTestUtils`
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the consumer scene, the assignment wait and the Kafka import under 도우미; the [purchase activity design](../design/purchase-activity.md): dated notes in sections 5 and 7

### Landed

Two commits on `feat/purchase-activity`, on top of `552f49b`:

- [`2b94a73`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/2b94a73) is the code, the tests, the standards and the design notes.
- The commit after it adds this section.

### Acceptance criteria

- [x] **On `BaseConsumerTest`: one published `OrderConfirmed` record becomes one processed confirmation and one activity row per item, with the record's `confirmedAt` for each of the three ISO_INSTANT forms; the group's committed offset for that partition is one past the record.** A parameterized scene sends `…03.123456Z`, `…03.100Z` and `…03Z` as written, for an event with two items, and asserts the one confirmation (event ID, order ID, time) and the two activities (order, product, quantity, time). It first waits until the committed offset of group `commerce-streamer` on the record's partition is the record's offset plus one. **Red check:** the base's guard, `No listener container listens to order-events-listener.` The first green run then showed the plain context failing with `PlaceholderResolutionException: Could not resolve placeholder 'order-events.topic'` (below).
- [x] **The same record published twice leaves one set of rows and both offsets committed; the INFO lines show `RECORDED` then `ALREADY_RECORDED`.** Same key, so same partition. The second offset is the first plus one, the group commits past the second, there is one confirmation and one set of activities, and the INFO lines are exactly `Recorded the order confirmed event. eventId=…, orderId=…, partition=1, offset=2, result=RECORDED` and the same with `offset=3, result=ALREADY_RECORDED` (the run's values). **Red check:** rows and offsets passed at once, since issue 179's recorder already makes a redelivery a no-op. The log assertion failed on an empty list until the INFO line existed.
- [x] **A record with an unknown `eventType` adds no row, is acked, and logs one INFO line.** `OrderCancelled` with payload `{"orderId":1}`: the offset is committed, both tables are empty, and the one line is `Skipped an order event of a type the streamer does not record. eventId=order-cancelled:v1:1, eventType=OrderCancelled, partition=0, offset=0`. **Red check:** without the skip, the payload failed conversion (`KotlinInvalidNullException` on `confirmedAt`), the stopping handler stopped the container (`Stopped container`), and each later test waited 60 s for an assignment a stopped container never gets: a 4 min 26 s run. That is why the base now fails at once on a stopped container.
- [x] **Two records of different orders on different partitions both become rows.** The scene names partitions 0 and 1 with `ProducerRecord`, waits for both commits and finds both events' rows. It passed on the first run. The log shows partition N read by thread `order-events-N-C-1`, one consumer per partition, but the scene would also pass with one consumer holding all three; `OrderEventsListenerConfigTest` pins the concurrency.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes with executed test counts checked.** With `:modules:kafka:ktlintCheck` as design section 11 lists: **11 classes, 49 tests, 0 failures, 0 skipped**, up from 10 classes and 43 tests at issue 179. The new tests are `OrderEventsListenerTest`'s 6 (the parameterized scene counts three). The counts come from the JUnit XML reports after deleting the old ones. `BOOTSTRAP_SERVERS` was unset. The pre-commit hook's tree-wide `ktlintCheck` passed.

### Red checks and probes

Each probe was a temporary test, run and deleted before the commit.

- **The first generation holds everything.** The first log of the scenes showed consumer 1 alone in generation 1 with `order-events-listener-0, -1, -2`, consumers 0 and 2 only asking to join, and the scenes over before the next generation. With the new wait, the log shows generation 1 (one consumer, three partitions), generation 2 (two and one), and generation 3 one heartbeat later, 3 s, with one partition each, before the first scene. testcontainers-kafka 2.0.5's `KafkaHelper` sets `KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0`, where a broker's default waits 3 s for more members. spring-kafka-test 4.1.1's `ContainerTestUtils.waitForAssignment` adds up the children's partitions and returns once the sum reaches the count, so it returned at generation 1 and let generation 3's rebalance fall inside a scene. A commit that meets a rebalance can redeliver a record, which would break the scenes that count INFO lines.
- **The topic placeholder.** `${order-events.topic}` alone failed every context without the test property: a Kotlin default exists only in the binder, not in the `Environment` a placeholder reads. Issue 177 had left this unprobed.
- **Schema version 2.** The container stopped on `UnsupportedOrderEventException: Order confirmed event order-confirmed:v1:… is at schema version 2, and the streamer reads version 1.`, with no committed offset and no row.
- **No `orderId`.** `MalformedOrderEventException: The payload of order confirmed event order-confirmed:v1:1 cannot be read: Missing required creator property 'orderId' (index 0)`; stopped, nothing committed, no row.
- **A JSON `null` payload.** `treeToValue` returns null for it, so the listener checks: `… cannot be read: the payload is null.`; stopped, nothing committed.
- **The race, simulated.** The winner recorded the event through the port. A `@MockkSpyBean` on `ProcessedOrderConfirmationRepository` returned null for the loser's first `findByEventId` and `findByOrderId`, as if they ran before the winner's commit. The loser's insert hit `Duplicate entry 'order-confirmed:v1:…' for key 'processed_order_confirmation.UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID'`, the listener called again, and the second call read the winner's rows: `result=ALREADY_RECORDED`, offset 1 committed, one confirmation. **Mutant without the retry:** `DataIntegrityViolationException` stopped the container with nothing committed. A spy on the recorder itself could not be stubbed: MockK's `every` went through the Spring proxy, and method validation ran on its placeholder request (`HV000090: Unable to access isEachProductOnce`).
- **No Kafka import.** A context importing only MySQL now fails: `Failed to start bean 'internalKafkaListenerEndpointRegistry'` ← `Failed to construct kafka consumer` ← `Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`. Issue 177's AC1 second half is true now, through the listener container rather than `KafkaAdmin`.
- **One group across cached contexts.** Every context's listener joins `commerce-streamer`. When the listener test's context started, the base context's three consumers logged `consumer pro-actively leaving the group` and `Consumer stopped`: Spring Framework 7.0.9 pauses a cached context that is not in use (`ContextCache.PauseMode`, `spring.test.context.cache.pause`), so only the running test's consumers stay in the group.
- One `apache/kafka-native` container failed to start once (`Container exited with code 1`) and passed on the rerun, as issue 178 saw.

### Decisions made while implementing

- **A scene's record value is a JSON tree, not bytes.** The ticket asked for the envelope's JSON bytes, but the shared `KafkaTemplate`'s `JacksonJsonSerializer` writes a `byte[]` with `writeValueAsBytes`, so bytes would reach the broker as a base64 string (spring-kafka 4.1.1 source). A tree parsed from hand-written JSON goes out compact and in the order written, as commerce-api's message does, and strings stay as written, so the three `ISO_INSTANT` forms are exact. The cost: the shared template cannot send bytes that are not JSON.
- **A scene waits for the committed offset, then reads the rows.** The listener logs, then acknowledges, after the recording's commit, and `MANUAL_IMMEDIATE` commits there, so nothing of the record happens after the commit is visible, including its INFO line. The group is the literal `commerce-streamer`, which also covers issue 177's hand-off to assert the container's `groupId`: a listener in another group commits where no scene looks. The price is a 10 s timeout instead of a mismatch message.
- **The base waits for a settled assignment**: each consumer holds a partition, and together all three. Once per consumer context it costs about one heartbeat. A stopped container fails at once.
- **The topic default is one constant**, `OrderEventsProperties.DEFAULT_TOPIC`, used by the property and by the placeholder `${order-events.topic:order-events}`.
- **A JSON `null` payload is malformed too.** It is one way a payload cannot be read, and `treeToValue` returns null for it instead of throwing.
- **The race retry is `recordSettlingARace`**, one more call on `DataIntegrityViolationException` without its own log line; the INFO line shows how it ended. A second violation leaves the listener and stops the container.
- **The two new exceptions sit in `adapter.consumer`**, where they are thrown, under `support.error.UnprocessableOrderEventException` as design section 6 asks.
- **No ERROR line yet.** Design section 5 wants one for each unprocessable record, with partition and offset, which only the listener knows. No scene of this ticket sees it; issue 181's scenes assert it.

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards:** three hard findings, all fixed. The wire helpers sat in `com.loopers.support` with one caller, but a single class's helper stays in that class, as `TopicReader` did until a second class needed it; they moved into the test, and the standards bullet says they move when another class uses them. Design section 7's 의존 bullet still said the Kafka test starter was only for `ContainerTestUtils`; it got a dated note, and `build.gradle.kts`'s comment changed with it. A positional literal key became a named argument. Of the judgement calls, fixed: the unused `schemaVersion` parameter, the repeated envelope shape (`orderConfirmedValue` takes the wire time), the private `record` named like the port (`recordSettlingARace`), an unsorted import, and the unknown-type test's name, which now uses the glossary's 건너뛰다. Kept: "one processed confirmation" in a test name, as issue 179's tests say.
- **Spec:** nothing missing or wrong. Fixed: the first scene drew 1..5 items, so some runs checked a single item; it now names two. The section 7 note above. Noted: the `groupId` assertion is covered indirectly; the settled wait and the stopped-container check go beyond the ticket and are recorded; the retry has no permanent test; three things issue 181 assumes (below).

### Left for later

- **The ERROR line.** Issue 181's criteria assert one per unprocessable record, but neither ticket's "What to build" adds it. The natural place is the listener: catch `UnprocessableOrderEventException` and `ConstraintViolationException`, log ERROR with eventId, orderId, partition and offset, and rethrow.
- **Issue 181's helpers.** `publish`, `awaitCommittedPast`, `infoLines`, the row reads and the envelope helpers are private to `OrderEventsListenerTest`. Scenes in contexts of their own move them to `com.loopers.support` or the base.
- **The race retry has no permanent test.** The repository-spy probe above could become one. It needs its own context and a `@MockkSpyBean`, which the standards' list of subclass annotations would have to name.
- **An unreadable body** (spec 176: 읽을 수 없는 본문도 세운다) has no scene and no owner; 181 lists only a missing `orderId`. The shared template cannot send bytes that are not JSON, but a JSON value that is not an envelope, such as a bare string, fails in the converter the same way.
- **Spec 176's body** still says `waitForAssignment` and that the topic default is the Kotlin default argument; it could take dated notes, as for issues 178 and 179.
- **commerce-api's `check`** was not run; design section 11 leaves it to the last ticket.
