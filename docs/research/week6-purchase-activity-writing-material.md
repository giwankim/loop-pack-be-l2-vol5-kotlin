# Week 6 purchase activity: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 2 ticket's closing comment. The rules behind it are in [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) and the [purchase activity design](../design/purchase-activity.md). The step is [spec 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176).

## Issue 177: the streamer becomes a testable app with the listener factory it will consume order events with

Drafted 2026-10-28 as the closing comment of [issue 177](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/177); the comment's link is added once it is posted. The rules are rules 5 to 7 of [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) (stop on an unprocessable event, acknowledge after the commit, the listener's promises belong to the app). The decisions are the "streamer의 정리" and "리스너" sections of [spec 176](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/176), and sections 5 and 7 of the [purchase activity design](../design/purchase-activity.md). There is no model, recorder or listener yet. The code is:

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
