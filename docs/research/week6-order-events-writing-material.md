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

Recorded 2026-10-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/168#issuecomment-6436994028) of [issue 168](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/168). The rule is rule 9 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) (the application declares its topic). The decisions are the "의존성과 설정" section and the "테스트 기반의 사실" notes of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167), and sections 7 and 8 of the [order events design](../design/order-events.md). The ticket asked to create this file with its comment as the first section; issue 169 landed first and created it, so this is the second. The code is:

- [`KafkaTestContainersConfig`](../../modules/kafka/src/testFixtures/kotlin/com/loopers/testcontainers/KafkaTestContainersConfig.kt): the broker, one per JVM, imported explicitly
- [`kafka.yml`](../../modules/kafka/src/main/resources/kafka.yml): `acks: all`, and a `test` document with no broker address and with `spring.kafka.admin.fail-fast`
- [`OrderEventsProperties`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderEventsProperties.kt) and [`OrderEventsKafkaConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderEventsKafkaConfig.kt): the `order-events` prefix and the `NewTopic`
- [`OrderEventsTopicTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/kafka/OrderEventsTopicTest.kt), [`OverriddenOrderEventsTopicTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/kafka/OverriddenOrderEventsTopicTest.kt) and [`OrderEventsProducerTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/adapter/integration/kafka/OrderEventsProducerTest.kt): the three seams
- [`BaseApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseApplicationServiceTest.kt), [`CommerceApiContextTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/CommerceApiContextTest.kt) and scene 8's `restarted` in [`OrderPaymentIntegrationTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderPaymentIntegrationTest.kt): the import
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the container and Kafka-test bullets under 도우미; [ADR 0012](../adr/0012-tests-inherit-setup-from-abstract-base-classes.md): the dated note

### Landed

Two commits, rebased onto issue 169's `6369374` and fast-forwarded onto `feat/order-events`:

- [`264cb0d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/264cb0d) is the code, the tests, `kafka.yml`, and the standards, ADR and design notes. No event is published yet; this ticket lays the rails.
- [`99fbaf6`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/99fbaf6) appends this section to the week 6 writing material.

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

## Issue 170: the saga step publishes the first confirmation through a required port inside TX B

Recorded 2026-10-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/170#issuecomment-6437905735) of [issue 170](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/170). The rules are rules 1–3, 6 and 7 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): the port, the event built in TX B, the adapter's check and Spring event, the derived event ID, and the cases that publish nothing. The decisions are the "포트와 이벤트" and "어댑터" sections of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167). The code is:

- [`OrderConfirmedEvent`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/required/OrderConfirmedEvent.kt): the event, its derived `eventId` and `from(order)`
- [`OrderEventPublisher`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/required/OrderEventPublisher.kt): the required port and its three promises
- [`SpringEventOrderEventPublisher`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/SpringEventOrderEventPublisher.kt): the adapter's `check` and `publishEvent`
- [`OrderPaymentSagaSteps`](../../apps/commerce-api/src/main/kotlin/com/loopers/application/order/OrderPaymentSagaSteps.kt): `applyResult` publishes after the save, on a change to success only
- [`BaseCommittingApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseCommittingApplicationServiceTest.kt): `@RecordApplicationEvents` and the `applicationEvents` field
- [`OrderConfirmedEventTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/required/OrderConfirmedEventTest.kt) and [`OrderEventPublishingTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventPublishingTest.kt): the unit seam and the provided-port seam
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): recording application events, under 도우미 next to the committing base

### Landed

Two commits, fast-forwarded onto `feat/order-events` on top of `4779179`, the tip after issue 168 landed:

- [`62ab5ed`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/62ab5ed) is the code, the tests and the standards sentences.
- [`9f1637a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/9f1637a) records this comment in this writing-material file.

It was written on `6369374` and rebased once 168 landed. The rebase conflicted only in this file, in `CODING_STANDARDS.md` and in `BaseApplicationServiceTest`'s KDoc, and both sides were kept.

### Acceptance criteria

- [x] **Unit tests: `OrderConfirmedEvent.from(order)` yields the derived eventId, items in ascending product ID order with the order's quantities, and the order's `confirmedAt`; an unconfirmed order is refused.** `OrderConfirmedEventTest` builds an order from products 30, 10 and 20 and reads back items 10, 20 and 30, each with its quantity, and the literal confirmation time. The DRAFT, PAYMENT_PENDING and PAYMENT_FAILED orders are refused with `IllegalArgumentException`.
  - An unsaved order's ID is 0, so the unit test cannot tell copying the order's ID from writing a constant. The derivation is tested on a constructed event: `orderId = 42` gives `order-confirmed:v1:42`. The first `OrderEventPublishingTest` scene pins a saved order's ID and `eventId`.
- [x] **With `@RecordApplicationEvents` in the committing base, a confirmation whose create answers SUCCESS records exactly one `OrderConfirmedEvent`; so do a customer reconcile and the PG's callback when each is what first confirms the order.** There are three scenes, and each asserts the whole event with `containsExactly`. The clock is pinned, so `confirmedAt` is a literal. The first scene also reads the same instant from the stored order.
- [x] **No event is recorded for a pending create answer, a lost create response, a failed transaction, a pre-create rejection, or a conflicting answer.** One parameterized scene covers all five. Each row also asserts the order and payment status it ends in.
  - "A failed transaction" is read as a PG transaction that failed, that is, an accepted create whose transaction is FAILED. The existing tests call it "an accepted create whose transaction failed". The five rows match spec 167's list: 결제 중, 결과 미확인, 실패, 생성 전 거절, 충돌.
  - A rolled-back TX B is not among them. `ApplicationEvents` records an event when `publishEvent` runs, so it cannot show that a rolled-back event is dropped. Dropping it is the AFTER_COMMIT relay's job, and design §8 already lists it as a broker-read scene.
- [x] **A duplicate callback, a repeated reconcile and a late create answer after confirmation record no second event.** All three are covered, plus a duplicate callback that races the first one.
  - The sequential duplicate callback and the repeated reconcile never reach TX B. The saga answers a finished payment from stored state before any lookup. Both scenes assert `lookupCount()` is 1 to pin that path.
  - Only the late create answer and the racing duplicate reach `applyResult` with `Unchanged`, which is why the racing scene was added (ADR 0024 rule 5). It also shows that events published on a thread the test starts are recorded.
- [x] **Calling `OrderEventPublisher.publish` outside a transaction throws `IllegalStateException`.** The committing base takes no test transaction. So the scene calls the real bean through the port type, and no event is recorded.
- [x] **Every existing `OrderPaymentIntegrationTest` scene passes unchanged.** All 63 tests pass, and none was modified.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes, with executed test counts checked.** After the rebase onto 168's tip (921), the result is 78 classes, **937 tests, 0 failures, 0 skipped**, with the Kafka container in every full context. Before the rebase, on `6369374` (918), it was 75 classes and 934 tests. The 16 new tests are 5 in `OrderConfirmedEventTest` and 11 in `OrderEventPublishingTest`.

### Red checks

The first scene went red before the port existed: the confirmation succeeded and nothing was recorded. The other scenes passed as soon as the call site was in. So the call site and the adapter were mutated by hand, and the class was run against each mutation:

| Mutation | Scenes that failed |
| --- | --- |
| publish whenever the saved order is CONFIRMED (state, not change) | late create answer, racing duplicate callback |
| `check(true)` in the adapter | the refusal outside a transaction |
| publish on any `Changed` | reconcile and callback that first confirm, racing duplicate, pending, failed, rejected |

Under the third mutation, `OrderConfirmedEvent.from` refused the unconfirmed order, so TX B rolled back instead of publishing. The lost-response row never reaches TX B and the conflicting row is never `Changed`, so no mutation of the call site can fail them. What they guard is the saga.

### Decisions made while implementing

- **`@RecordApplicationEvents` is on `BaseCommittingApplicationServiceTest`, not on the test class.** `CODING_STANDARDS.md` keeps test configuration annotations on the base classes, and the ticket says "in the committing base".
  - Spring scopes `ApplicationEvents` to the test method and cannot inject it into a test class's constructor. So the base exposes it as a `protected` field, like `entityManager`.
  - Recording is only a `TestExecutionListener`. Spring-test 7.0.9's `spring.factories` registers no context customizer for it, so the context cache key does not change.
  - The record is held in an `InheritableThreadLocal`. Events on the test thread and on threads the test starts (`inAnotherThread`, `runConcurrently`) are recorded. Events on a pool thread that existed before the test are not.
- **The scenes are a new class, `OrderEventPublishingTest`, not part of `OrderPaymentIntegrationTest`.** That class is the assignment's `*PaymentIntegrationTest` target. The new class uses two of its three spies (the PG call and the payment-modifying Service), so it starts one more context.
  - Its four helpers (`confirmPending`, `transactionOf`, `afterTheCreateIsAnswered`, `pauseTheFirstTxBHoldingTheOrder`) are copies. Moving them to `com.loopers.support` would rewrite the call sites in `OrderPaymentIntegrationTest`'s scenes, and this ticket was to leave those scenes passing unchanged.
- **The call site tests `result is Changed && result.to == SUCCESS` again after the save** rather than publishing inside the `when`. The ticket and spec 167 put the publish after `orderRepository.save`, and the event is built from the saved order.
- **The adapter is a plain `@Component`, like `ProductListRevisionMarker`.** `@ApplicationService` would open its own transaction for a call made outside one, so the check could never fail.
- **The port's "never throws" covers delivery.** A call outside a transaction is a programming error and throws `IllegalStateException`, and the KDoc says so.
- **`from(order)` uses `requireNotNull`, so an unconfirmed order raises `IllegalArgumentException`.** The `check` and `requireNotNull` messages are in English, as the ticket asks. The neighbouring Korean messages are left as they are.
- **The placeholder names stayed** (`SpringEventOrderEventPublisher`, `OrderEventPublishingTest`), so spec 167 needs no dated note about names.

### Review

The two-axis review (standards and spec) ran on the working tree before the commit. These findings were applied, and the ones not applied are covered under the decisions above:

- The new code said "announce" for the publish. In `CONTEXT.md`, 발행하다 lists 알리다 under _Avoid_ because it collides with the PG's 알림, and 주문 확정 이벤트 lists 주문 이벤트. Test names, KDoc and the check message now say "publish" and "order confirmed event". The issue body says "announces", but the glossary wins.
- The repeated-reconcile KDoc said its second reconcile was `Unchanged`, but that reconcile returns before any TX B. `applyResult`'s KDoc overstated it the same way. Both were corrected, and the two sequential duplicates now assert the lookup count.
- A KDoc and the standards pointed at a broker test that does not exist yet, and the claim about threads went further than `InheritableThreadLocal` allows. Both were reworded.

### Left for later

- **Ticket 171** adds the AFTER_COMMIT relay, and the scene where TX B rolls back (`failTxBOnceAfterItsWrites`) belongs there, read from the broker. 171 also decides whether the scenes in `OrderEventPublishingTest` that read recorded events stay beside the broker reads or become broker reads. Spec 167's testing decisions say not to assert how many Spring events were published.
- The relay's `@Async` virtual threads are created per task by the submitting thread. So the record should follow them, but this has not been tried yet.
- Once 171 or a later ticket settles the broker scenes, the four copied spy helpers could move to `com.loopers.support`, changing `OrderPaymentIntegrationTest`'s call sites in the same commit.

## Issue 171: an async after-commit relay publishes the order confirmed event to Kafka and observes the publish

Recorded 2026-10-27 from the closing comment of [issue 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171). The rules are rules 3–6 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): the Spring event relayed after the commit, why the relay is asynchronous, the publish observed and not awaited, and the key and envelope derived from the order. The decisions are the "어댑터" section of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167) and sections 2, 4, 5 and 8 of the [order events design](../design/order-events.md). The code is:

- [`OrderConfirmedKafkaRelay`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderConfirmedKafkaRelay.kt): `@Async("orderEventsExecutor") @TransactionalEventListener(AFTER_COMMIT)`, `send()` in `try/catch`, `whenComplete` logging one INFO or one WARN
- [`OrderConfirmedMessage`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderConfirmedMessage.kt): the envelope of design section 2
- [`OrderEventsKafkaConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderEventsKafkaConfig.kt): `@EnableAsync` and the `orderEventsExecutor` bean
- [`OrderEventPublishingTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventPublishingTest.kt): every scene now reads the broker
- [`BaseCommittingApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseCommittingApplicationServiceTest.kt): `@RecordApplicationEvents` and `applicationEvents` removed
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the broker-reading bullet under 도우미, next to the Kafka topic and producer tests

### Landed

Committed on `feat/order-events` on top of `c56cfd7`:

- [`5d426ae`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/5d426ae) is the code, the tests, the standards bullet and the dated notes in ADR 0012, ADR 0032 and the design doc.
- The commit after it records this comment in this writing-material file.

### Acceptance criteria

- [x] **A confirmation that first confirms an order (via create answer, customer reconcile, and PG callback, each in its own scene) produces exactly one record on the test's topic within the `KafkaTestUtils` wait. The key equals the order ID as a string, and the body equals design section 2's envelope with the pinned `confirmedAt` rendered as an ISO-8601 UTC string and items in ascending product ID order.** The create-answer scene orders two products in descending ID order and compares the body with a hand-written envelope through `JsonContent(…).isStrictlyEqualTo(…)`. It also checks that the record carries no header. The reconcile and callback scenes compare the same envelope for their one product. Each scene reads exactly one record within a 10 s wait and finds no other in the 1 s that follows.
- [x] **The confirm/reconcile/callback call returns before or regardless of the record's arrival, with its week 5 status and body unchanged.** Each scene asserts the answer (`CONFIRMED` and `SUCCESS`) before it reads the broker. The relay now runs in every context that confirms an order. The week 5 suites (`OrderPaymentIntegrationTest`, `PaymentApiTest`, `OrderConfirmationApiTest`) pass unchanged, and the full run's output has no WARN from the relay and no ERROR from the async handler.
- [x] **Two orders confirmed in one test yield two records with different eventIds and keys.** Each record is matched to its order by key and `$.eventId`, in any order, since the two keys can land on different partitions.
- [x] **A TX B rolled back after its writes (existing `failTxBOnceAfterItsWrites` technique) yields no record. The reconcile that later confirms yields exactly one.** The helper is copied from `OrderPaymentIntegrationTest`, with a spy on `OrderPaymentSagaSteps`. The step has already published inside the doomed TX B, so the empty read shows that the relay drops a rolled-back event, not that nothing was published.
- [x] **Pending, unknown, failed, rejected and conflicting outcomes yield no record. Repeated application after confirmation yields no second record (assert by draining the topic after a bounded wait).** The five rows of the parameterized scene read for the 1 s quiet period and find nothing. "Unknown" is the lost create response. The repeated reconcile, the duplicate callback, the racing duplicate callback and the late create answer each find exactly one record. The late-create scene reads the callback's record inside the PG-call spy and then finds nothing after confirm's own TX B.
- [x] **One INFO line per successful send, naming eventId, topic, partition and offset, is captured (existing ListAppender technique).** The scene compares the whole message with the topic, partition and offset of the record the consumer read. The line is logged on the producer's network thread once the broker acknowledges the record, which can be after the consumer already sees it. So the appender's list is read under the appender's lock, and Awaitility waits for the line within a bound.
- [x] **The relay bean's method is proxied asynchronously: a test asserts the send happened on a thread other than the caller's, without asserting elapsed time.** A `@MockkSpyBean` on `KafkaTemplate` captures the thread that calls `send`. It is not the test's thread, and it is virtual. The INFO line's thread could not show this. It is `kafka-producer-network-thread | commerce-api-1`, which differs from the caller's thread even when the relay is synchronous.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes, with executed test counts checked.** 78 classes, **941 tests, 0 failures, 0 skipped**: 937 plus four new scenes (the rolled-back TX B, two orders, the INFO line and the publishing thread). `OrderEventPublishingTest` has 15 tests and takes about 40 s, mostly in the 1 s quiet periods. The review changed only KDoc, documents and one parameter name after that run, and the class was run again (15 of 15).

### Red checks

The first scene went red before the relay existed: the confirmation succeeded, and no record arrived within the 10 s wait. Once the relay was in, every other scene passed without new code. So the relay was mutated by hand, and the class was run against each mutation:

| Mutation | Scenes that failed |
| --- | --- |
| `@TransactionalEventListener(phase = AFTER_COMMIT)` → `@EventListener` | the rolled-back TX B, which found a record |
| `@Async` removed | the publishing thread, which was `Test worker` |
| each envelope sent twice | 9 scenes: every scene that expects a record |

Each mutation failed only the scene meant to catch it, and the file was restored byte for byte after each run.

### Decisions made while implementing

- **No producer mapper is configured.** `modules:kafka` declares `JacksonJsonSerializer` by class name, so it uses spring-kafka 4.1.1's default `JsonMapper` (Jackson 3). Jackson 3 writes `Instant` with `ISO_INSTANT`, and the pinned `2026-10-27T01:02:03.123456Z` went out as written. `ISO_INSTANT` writes the fraction in groups of three as far as needed. A time whose micros end in `000` goes out as `…03.100Z`, and a whole second as `…03Z`. Precision is unchanged, and ISO-8601 parsers read all three forms, so design section 2 now says the consumer compares `confirmedAt` as an instant, not as a string.
- **The broker reader does not use a unique group and `earliest`.** A class's tests share its topic, and `DatabaseCleanUp`'s `TRUNCATE` restarts `AUTO_INCREMENT`. Order IDs, and so keys and eventIds, start again at 1 in every test, so reading from the earliest offset would pass earlier scenes' records off as this one's. Before each scene, the consumer is assigned every partition and placed at its end, and `position` fixes those offsets before the scene runs. Without the group protocol, the race that `earliest` guards against, an assignment that lands after the record, cannot happen. The group name `consumerProps` asks for is never used. `CODING_STANDARDS.md` and design section 8 record this.
- **Waits:** 10 s for the records a scene expects, with the read returning as soon as they arrive, then a 1 s quiet period. A wrong record would follow a commit the call has already returned from, after one hand-off and one round trip to the local broker. A quiet period that is too short can only let a bug pass. It cannot make a correct scene fail. No scene sleeps or asserts elapsed time.
- **The scenes read the broker instead of recorded Spring events.** Issue 170 left this choice to 171, and spec 167's testing decisions say not to assert how many Spring events were published. Recorded events are a detail of the adapter, the port → Spring event hop, which week 7's outbox may change. The broker is what other services see. No test read `applicationEvents` any more, so `@RecordApplicationEvents` and the field left the committing base, following the rule against helpers that no test uses. The two bases now match `4779179`.
- **The envelope is built outside the `try`.** A bug in the mapping goes to Spring's default `AsyncUncaughtExceptionHandler` as an ERROR and is not mistaken for a failed publish. That handler logs the method and the stack trace but not the argument (`SimpleAsyncUncaughtExceptionHandler`, spring-aop 7.0.9). ADR 0032 and design section 4 had said it logs the argument, and both now carry a dated correction.
- **The executor is an `Executor` bean, so Boot 4.1 backs off from its own `applicationTaskExecutor`** (`spring.task.execution.mode` defaults to `auto`). Nothing in commerce-api uses it: no MVC handler returns an async type, and no other method is `@Async`. `TaskExecutorAdapter` has no `close`, so nothing waits for running relays when the context closes. A relay that has not published by then falls in the publish gap of design section 6.
- **Log text says "publish".** 발행하다 lists "send" under _Avoid_, so `send` appears only as the name of the Kafka method.
- **The placeholder names stayed** (`OrderConfirmedKafkaRelay`, `OrderEventsKafkaConfig`), so spec 167 needs no dated note about names.

### Review

The two-axis review (standards and spec) ran on the working tree before the commit. It found no hard violation, no missing criterion and no scope creep. These findings were applied:

- The relay's KDoc, ADR 0032 and design section 4 said the default async handler logs the argument. It does not, and all three were corrected.
- Earlier new kinds of spy each got a dated note in ADR 0012, and the `KafkaTemplate` spy now has one too.
- `publishedRecords(count)` became `publishedRecords(expected)`, because the list it returns can hold more than that number.
- Two KDocs said the callback "is published". They now say the confirmation is.

These findings were not applied:

- `failTxBOnceAfterItsWrites` is a fifth helper copied from `OrderPaymentIntegrationTest`. Moving the copies to `com.loopers.support` is still the follow-up that issue 170 named.
- `@EnableAsync` sits in the Kafka adapter's configuration because the ticket puts it there. Its KDoc says so.
- The rolled-back TX B scene asserts in the middle of its setup. The criterion names it as one scene.
- `relayInfoLines()` returns at the first INFO line. A second line would come from a second publish, and the 1 s quiet period before it already catches that as a second record.

### Left for later

- Spec 167's "기반" bullet still says the consumer uses a unique group and `earliest`. Only design section 8 and `CODING_STANDARDS.md` record the change, so the spec could get a dated note.
- Spec 167's seam 1 lists "포인트로 확정된 주문(fixture)에는 record가 없다". It is not among 171's criteria, and neither 172 nor 173 plans it.
- Whether `confirmedAt` should always carry six fraction digits. A `@JsonFormat` on `OrderConfirmedMessage.Payload.confirmedAt` would do it without touching the shared template.
- Issue 172 reads the broker too. By design section 8's rule ("같은 준비가 두 테스트에서 반복되면 그때 fixture에 도우미를 둔다"), that is when the reader leaves `OrderEventPublishingTest` for a shared helper.
- The relay's virtual threads have no name, so the WARN from its `catch` shows an empty thread name.
