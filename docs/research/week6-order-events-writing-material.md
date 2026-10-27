# Week 6 order events: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 1 ticket's closing comment. The rules behind it are in [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) and the [order events design](../design/order-events.md). The step is [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167).

## Issue 169: the confirmation instant comes from a Clock bean the tests can pin

Recorded 2026-10-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/169#issuecomment-6436774946) of [issue 169](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/169). The rule is rule 8 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) (the confirmation time comes from a clock bean the reproduction can pin), and the decisions are the "시계 (ADR 0032 규칙 8)" section of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167). The code is:

- [`ClockConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/support/time/ClockConfig.kt): the `Clock.systemUTC()` bean
- [`Order`](../../apps/commerce-api/src/main/kotlin/com/loopers/domain/order/Order.kt): `completePayment(confirmedAt)`
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt): `applyResult` passes `clock.instant()`
- [`TestClock`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestClock.kt) and [`TestPgLookupBreakerConfig`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TestPgLookupBreakerConfig.kt): the breaker's clock as the `@Primary` `Clock`, and `pin`
- [`OrderTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/domain/order/OrderTest.kt) and [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the two seams
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the shared clock and `pin`, under 도우미 next to the breaker's clock

### Landed

Two commits, fast-forwarded onto `feat/order-events` on top of `23a9244`:

- [`3df8325`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/3df8325) is the code, the tests and the standards sentences.
- [`0ce5e63`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/0ce5e63) creates this writing-material file. Ticket 168 was to create it but had not landed yet, so 168 will meet an add/add conflict here.

### Acceptance criteria

- [x] **`Order.completePayment(confirmedAt)` stores exactly the given instant truncated to micros.** `OrderTest` confirms at `2026-10-27T01:02:03.123456789Z` and reads back `2026-10-27T01:02:03.123456Z`.
- [x] **A second `completePayment` is still refused with `OrderStatusConflictException`.** The parameterized test covers DRAFT, CONFIRMED and PAYMENT_FAILED. The CONFIRMED order was confirmed at 01:00 and is retried at 02:00, so an overwrite would show. `confirmedAt` is unchanged.
- [x] **In `OrderPaymentIntegrationTest`, a confirmation made with the test clock advanced by a known duration shows that instant as the order's `confirmedAt` in a fresh transaction.** The new scene pins the `TestClock` at `2026-10-27T01:02:03.123456789Z` and advances it one day. It then confirms through the test PG, and a fresh transaction reads `2026-10-28T01:02:03.123456Z`.
  - **Red check:** while the saga step still passed `Instant.now()`, the first version of the scene failed. The stored value was `2026-10-27T07:44:14.828320Z`, a day before the clock's window.
- [x] **There is exactly one `Clock` bean in the test context and it is the `TestClock` the breaker tests already use.** This one holds with a deviation in wording. The breaker and the confirmation share one `TestClock` instance, and the breaker's wait tests pass with only the field rename (`OrderPaymentIntegrationTest` 63, `PaymentApiTest` 23).
  - The context still holds the main configuration's `Clock.systemUTC()` as a second `Clock` bean, because the ticket also says to expose the `TestClock` "as a `@Primary` `Clock`". `@Primary` only makes sense beside a second bean.
  - Spec 167 frames the goal as "두 시계가 생기지 않게" (so that two clocks don't arise), meaning no second `TestClock`. That is what holds.
  - The new scene proves the sharing: it moves the `TestClock` the breaker scenes inject and sees that instant stored.
- [x] **No remaining `Instant.now()` in `Order.kt`.** `grep -c` gives 0.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes, with executed test counts checked.** 73 classes, **918 tests, 0 failures, 0 skipped**. `feat/order-events` had 917. The one new test is the clock scene, and the changed `OrderTest` test replaced the old one (26 tests either way).
- **"Existing tests that assert `confirmedAt` is 'about now'":** none exist. Every other `confirmedAt` assertion is `isNotNull`, `isNull`, or two answers compared with each other, so all were left as they were.

### Decisions made while implementing

- **The `Clock` bean is in `support.time`.**
  - The other configuration classes in commerce-api each sit next to the adapter they configure. The clock configures nothing, and the application layer reads it.
  - Every layer may use `support` (`HexagonalArchitectureTest`). The shared modules also keep cross-cutting configuration under `config.<concern>`.
- **`@Primary`, not a single bean.**
  - Boot disables bean-definition overriding.
  - `@ConditionalOnMissingBean` on a user configuration depends on registration order, and Boot recommends it only for auto-configuration.
  - `@Primary` resolves the same way in every context. In a context without `@EnableTestPg` the system clock is the only `Clock`, and nothing there confirms through the PG.
  - Spring prefers the primary bean over the parameter-name fallback. So `clock: Clock` in `OrderPaymentSagaSteps` gets the `TestClock` even though the main bean is named `clock`, and the scene shows it.
- **The test clock runs unless a test pins it.** By default `TestClock` is the system clock plus what `advance` added, so two confirmations in one scene get different instants.
  - A clock frozen by default would make the scenes that check "the second application keeps the first `confirmedAt`" pass by construction. These are two reconciles applying the same answer, a reconcile racing confirm's TX B, a callback before TX B, a callback after a reconcile, and a callback racing a reconcile.
  - `pin(instant)` stops the clock, `advance` still moves it from there, and `reset` unpins it. The base class already resets the clock before each test.
  - The scene's first draft only bracketed the confirmation between two reads of the moving clock. The review pointed to the design doc §8 ("확정 시각을 단언하는 테스트는 시계를 고정한다", tests that assert the confirmation time pin the clock) and to spec 167's "`confirmedAt`을 고정해 단언한다" (pin `confirmedAt` and assert it) for tickets 170 and 171. So `pin` was added, and the scene asserts a literal.
- **Truncation stays in the entity.** Spec 167 has the step pass `clock.instant().truncatedTo(MICROS)`. The ticket keeps the truncation in `Order` and has the step pass `clock.instant()`, so the cut happens in one place.
- **`pgLookupClock` became `testClock`** for the bean, `clock` for the constructor parameters of `OrderPaymentIntegrationTest` and `PaymentApiTest`, and `clocks` for the base class's `ObjectProvider`. The clock now also decides the confirmation time, and a confirmation scene reading `pgLookupClock.advance` would mislead.
- **The scene follows the fixture rules.**
  - It accepts the create with `acceptNextCreate`, because it does not assert the transaction key.
  - It leaves the stock to the fixture default.
  - Price 1,000 × quantity 2 stays, because the stubbed amount 2,000 depends on it.

### Left for later

- Ticket 170 can pin the clock and assert `OrderConfirmedEvent.confirmedAt` as a literal. Ticket 171 can do the same for the broker record's `confirmedAt`.
- Ticket 173 (docs) could add to design doc §8 that a PG test context holds two `Clock` beans with the `TestClock` primary, and that `TestClock` gained `pin`.
- `BaseEntity` in `modules:jpa` still stamps `createdAt`, `updatedAt` and `deletedAt` with `Instant.now()`. That is outside this ticket.

## Issue 168: commerce-api talks to a Kafka test container and declares the order-events topic

Recorded 2026-10-27 from the closing comment drafted for [issue 168](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/168). The rule is rule 9 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) (the application declares its topic). The decisions are the "의존성과 설정" section and the "테스트 기반의 사실" notes of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167), and sections 7 and 8 of the [order events design](../design/order-events.md). The ticket asked to create this file with its comment as the first section; issue 169 landed first and created it, so this is the second. The code is:

- [`KafkaTestContainersConfig`](../../modules/kafka/src/testFixtures/kotlin/com/loopers/testcontainers/KafkaTestContainersConfig.kt): the broker, one per JVM, imported explicitly
- [`kafka.yml`](../../modules/kafka/src/main/resources/kafka.yml): `acks: all`, and a `test` document with no broker address and with `spring.kafka.admin.fail-fast`
- [`OrderEventsProperties`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderEventsProperties.kt) and [`OrderEventsKafkaConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderEventsKafkaConfig.kt): the `order-events` prefix and the `NewTopic`
- [`OrderEventsTopicTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/kafka/OrderEventsTopicTest.kt), [`OverriddenOrderEventsTopicTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/kafka/OverriddenOrderEventsTopicTest.kt) and [`OrderEventsProducerTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/kafka/OrderEventsProducerTest.kt): the three seams
- [`BaseApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt), [`CommerceApiContextTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/CommerceApiContextTest.kt) and scene 8's `restarted` in [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the import
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the container and Kafka-test bullets under 도우미; [ADR 0012](../adr/0012-tests-inherit-setup-from-abstract-base-classes.md): the dated note

### Landed

Two commits on `worktree-issue-168`, rebased onto `feat/order-events`' `6369374` (issue 169's tip):

- [`264cb0d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/264cb0d) is the code, the tests, `kafka.yml`, and the standards, ADR and design notes. No event is published yet; this ticket lays the rails.
- The second commit appends this section.

### Acceptance criteria

- [x] **`./gradlew :apps:commerce-api:check` boots the commerce-api test context against a Kafka container started by the fixture; no test needs `BOOTSTRAP_SERVERS` in the environment.** `BOOTSTRAP_SERVERS` was unset in the shell that ran the suite. The fixture starts `apache/kafka-native:4.3.2` in 0.4 s (MySQL takes 5 s) and registers `spring.kafka.bootstrap-servers` through a `DynamicPropertyRegistrar`. `BaseApplicationServiceTest`, `CommerceApiContextTest` and scene 8's restarted context import it.
- [x] **After the context starts, an `AdminClient` pointed at the container describes `order-events` with 3 partitions and replication factor 1 (asserted in a small test next to the configuration).** `OrderEventsTopicTest` in `adapter.integration.kafka` calls `KafkaAdmin.describeTopics("order-events")` on the shared context. That opens an admin client on the fixture's address and finds 3 partitions with one replica each. The check uses the application's own admin configuration, not an independent client, but the test profile has no address other than the fixture's. The test reads the broker rather than the `NewTopic` bean because the bean is only a request. `KafkaAdmin` leaves an existing topic that has more partitions as it is and only logs it at INFO.
- [x] **A test context that overrides `order-events.topic` gets a topic of that name instead.** `OverriddenOrderEventsTopicTest` sets `order-events.topic=order-events-overridden` with `@TestPropertySource` and describes that topic, which has the default 3 partitions. **Red check:** while the name was still hardcoded, the describe failed with `KafkaException: Failed to obtain topic descriptions`.
- [x] **The producer factory's effective config shows `acks=all` (read through `ProducerFactory.getConfigurationProperties()`).** `OrderEventsProducerTest` checks it. **Red check:** before the `kafka.yml` line existed, the map had no `acks` entry at all. The factory's map holds only explicit settings. kafka-clients' own default has been `all` since 3.0, so reading a live producer's value would have passed without the line.
- [x] **Forgetting the `@Import` fails the context with an unresolved `spring.kafka.bootstrap-servers` placeholder, as the MySQL fixture does, documented in the fixture's KDoc.** Checked with a throwaway probe that was deleted before the commit: a `SpringApplicationBuilder` context with only MySQL and Redis, under the test profile. Three runs:
  - The template as it was (`local, test` → `localhost:19092`): the context **started** after 61 s. `KafkaAdmin` waited out its 30 s operation timeout and its 10 s close, logged `Could not configure topics` and carried on. Had compose been running, the topic would have been created on the local broker.
  - `test` split from `local`, with no address: the admin client rejected the literal `${BOOTSTRAP_SERVERS}`, but `KafkaAdmin` only logged `Could not create admin`, and the context **started** after 16 s.
  - `spring.kafka.admin.fail-fast: true` added to the test profile: the context **failed** after 18 s with `IllegalStateException: Could not create admin` ← `KafkaException: Failed to create new KafkaAdminClient` ← `ConfigException: Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`.
- [x] **Image tag is fully pinned and the choice is noted in design section 8.** The pin is `apache/kafka-native:4.3.2`. Re-checked on Docker Hub on 2026-10-27: `4.3.2` was published on 2026-10-20 and `4.4.0` on 2026-10-22, and there is no `4.3` tag (404). The patch release of the previous minor was chosen over the first release of the new one.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check :modules:kafka:ktlintCheck` passes, with executed test counts checked and the previous count (917 before this branch) not reduced.** Before the rebase: 76 classes, **920 tests, 0 failures, 0 skipped**, on 917. After rebasing onto issue 169's tip (918): **921 tests, 0 failures, 0 skipped**. The three new tests run, and the other 918 still pass with the broker in every full context.

### Decisions made while implementing

- **`kafka.yml`'s `local, test` document is split.** `local` keeps `localhost:19092`. `test` has no address and turns on `spring.kafka.admin.fail-fast`. The split is what makes the fifth criterion true. It also gives `kafka.yml` the shape of `jpa.yml`, whose `test` profile has no JDBC URL either. Production keeps `fail-fast` off, because with it on commerce-api would not start without a broker. Design section 7 has a dated note.
- **The template's `admin.properties.bootstrap.servers: kafka:9092` is dead config.** Boot's `KafkaAutoConfiguration` overwrites the admin's `bootstrap.servers` with the common address from `KafkaConnectionDetails`, so the line never applies. It stays in `local`, because cleaning up the template is out of scope.
- **`BaseApplicationServiceTest` imports the fixture, so the contexts stay shared.** The fifth criterion settled the choice the spec left open. To meet it, the test profile has no address and fails fast, so every full context that declares the topic needs a broker to start. Importing the fixture only in the publishing tests would mean giving every other context an address of its own:
  - a real broker's address leaks those contexts to the local broker;
  - an address with nothing behind it costs each context `KafkaAdmin`'s wait (61 s without the fixture).

  Every base that starts a full context has the same import, so no cached context splits. The suite starts 17 test contexts, and the only new one belongs to `OverriddenOrderEventsTopicTest`. `BaseRepositoryTest` (`@DataJpaTest`) has no Kafka auto-configuration and imports only MySQL. Dated notes are on spec 167 and in ADR 0012.
- **The streamer inherits the profile split.** `kafka.yml` is shared, so commerce-streamer's `test` profile has no broker address either. The streamer has no tests today. Its first context test on `feat/purchase-activity` will need to import the fixture. Design section 10 notes this.
- **The topic properties carry Kotlin defaults.** `OrderEventsProperties(topic = "order-events", partitions = 3)` sits under the top-level prefix `order-events`, like `pg`. `OrderEventsKafkaConfig` enables it and declares the topic with `TopicBuilder` and one replica. There is no `order-events` block in `application.yml`, so the defaults live in one place.
- **Review.** A two-axis review ran before the commit.
  - **Standards:** no hard violations. Four places in the docs did not match the code, and all four were fixed:
    - "every base imports it" ignored `BaseRepositoryTest`;
    - `OrderEventsTopicTest`'s reason ("a failed create is only logged") is false under `fail-fast`;
    - `OrderEventsProperties` described the streamer's record listener as if it already existed;
    - scene 8's first sentence in the standards left out Kafka.

    The acks test was renamed to say what it checks, and the topic literal became a local.
  - **Spec:** nothing was missing and nothing was out of scope; the profile split and `fail-fast` follow from the fifth criterion. "There was no real choice" read as circular, so the notes now name the criterion. The streamer side effect was added to design section 10.
  - **Kept:**
    - `3` written in both `OrderEventsProperties` and the template's `setConcurrency(3)`: it is a documented pairing, not a derived value;
    - the fail-fast explanation repeated across the KDoc, the standards, the ADR and the design (local practice);
    - the `spring-kafka-test` dependency, which nothing uses yet but the ticket requires for ticket 171's `KafkaTestUtils`.

### Left for later

- Ticket 171 reads records with `KafkaTestUtils` and isolates its topic per class with the override this ticket proved.
- The template's dead `admin.properties.bootstrap.servers` waits for the `kafka.yml` cleanup that the spec puts out of scope this week.
