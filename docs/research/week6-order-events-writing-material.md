# Week 6 order events: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 1 ticket's closing comment, with three exceptions. The W7 handoff is recorded from one run of issue 172's reproduction. Issue 173 added the preface below and the draft PR body at the end. The rules behind it are in [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) and the [order events design](../design/order-events.md). The step is [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167).

## Preface: what this material is evidence for

Added 2026-10-27 by [issue 173](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/173). The [draft PR body](#draft-pr-body) for `feat/order-events` → `week6` is at the end.

### Writing-quest topics

- **Why publishing after the commit still leaks.** The order commits, the reservation is consumed, the confirmation answers, and the event exists nowhere. The database and the broker are not one transaction. See [W7 handoff: unpublished case](#w7-handoff-unpublished-case) for one run's values and [issue 172](#issue-172-a-publish-that-fails-or-stops-after-tx-b-commits-leaves-the-order-confirmed-and-no-record-on-the-broker) for the scenes. The window is at least one hand-off to the relay's virtual thread wide. When the broker cannot be reached, `send` can wait up to `max.block.ms` (60 s) for the topic's metadata. Once `send` has returned, the producer can go on trying for up to `delivery.timeout.ms` (120 s) more. A process that stops inside the window leaves no event and not even the WARN.
- **Why the relay is asynchronous.** Spring runs an AFTER_COMMIT listener in `afterCompletion`, before `cleanupAfterCompletion` returns TX B's connection to the pool. A synchronous publish would hold that connection for the round trip to the broker. A `send` waiting for metadata would also hold the confirming thread for up to `max.block.ms`. The reason first written down, that the listener's exception would leave `commit()` and turn a committed confirmation into a 500, was wrong. Issue 172's red check made the relay synchronous and let the failure escape, and the confirmation still answered normally (ADR 0032 rule 4's dated note). Issue 171's red check shows the relay runs off the confirming thread: with `@Async` removed, `send` ran on `Test worker`.
- **Why there is no await bound.** The future of `send` ends only when the broker acknowledges the record or the producer gives up at `delivery.timeout.ms` (120 s by default). Cutting the wait shorter would see "still trying", not a failure. The 3 s first proposed was only needed while a synchronous relay held the DB connection, and nothing in the assignment backed it (ADR 0032 rule 5, design section 5).

### Questions the instructor's comments prompt

The instructor (Kev) left four comments on the assignment page on 2026-10-24. They were read on 2026-10-27 through the page's comment buttons, which show which block each comment sits on. Chrome's policy blocks clicking on the page, so the exact words each comment highlights could not be read. The questions below are read from the block.

- **On the opening paragraph** (purchase activity is a stored fact that feeds week 9's ranking, and there is no ranking API or outbox this week): "왜??" (why?). Why hand the confirmation to another service as an event, rather than have commerce-api write the purchase activity or let the streamer read commerce-api's tables? And why leave the outbox to week 7 when the gap is known?
  - The outbox half has evidence: design sections 6 and 9, and ADR 0032's 고르지 않은 것 ("지금 Outbox").
  - The first half has little written evidence yet. `CONTEXT.md` defines the event as a notice to another context after the commit, and ADR 0028's dated note says it is not a step of TX B. No document argues why the purchase activity lives in the streamer rather than in commerce-api. The assignment asks the design to show why the event is needed, so step 2's grilling (`feat/purchase-activity`) is the place to write it down.
- **On the contract table** (topic, eventType and schemaVersion, key, eventId, orderId and confirmedAt, items), two comments: "왜??" (why?) and "다른 방법은?" (what other ways are there?). Why the order ID as the key, an eventId derived from the order rather than stored, an envelope with the metadata in the body, and items normalised per product? The alternatives: a stored eventId, a fresh UUID per publish attempt, a stored fingerprint, Kafka headers instead of the envelope. Evidence: ADR 0032 rule 6, design section 2 and the eventId entry in `CONTEXT.md`.
- **On the paragraph about where to publish** (an after-commit listener or orchestration after the commit; a `send` at the end of a `@Transactional` method is not after the commit; build the payload beforehand so the listener needs no lazy entity): "면접에 나오는거~ 이거 코드 어케짤래~" (this comes up in interviews; how would you code it?). The branch's answer is a required port called inside TX B, a Spring event, and an async AFTER_COMMIT relay. ADR 0032's 고르지 않은 것 records the alternatives: the saga publishing after the step returns, a hand-registered `afterCommit`, Spring Data's `@DomainEvents`, and calling `ApplicationEventPublisher` directly. The event is built from the locked order inside TX B, so nothing reads an entity after the commit. Issue 171's red check is the proof: with `@EventListener` in place of the AFTER_COMMIT listener, the rolled-back TX B scene found a record.

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

Recorded 2026-10-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171#issuecomment-6439907100) of [issue 171](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/171). The rules are rules 3–6 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): the Spring event relayed after the commit, why the relay is asynchronous, the publish observed and not awaited, and the key and envelope derived from the order. The decisions are the "어댑터" section of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167) and sections 2, 4, 5 and 8 of the [order events design](../design/order-events.md). The code is:

- [`OrderConfirmedKafkaRelay`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderConfirmedKafkaRelay.kt): `@Async("orderEventsExecutor") @TransactionalEventListener(AFTER_COMMIT)`, `send()` in `try/catch`, `whenComplete` logging one INFO or one WARN
- [`OrderConfirmedMessage`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderConfirmedMessage.kt): the envelope of design section 2
- [`OrderEventsKafkaConfig`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderEventsKafkaConfig.kt): `@EnableAsync` and the `orderEventsExecutor` bean
- [`OrderEventPublishingTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventPublishingTest.kt): every scene now reads the broker
- [`BaseCommittingApplicationServiceTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/test/BaseCommittingApplicationServiceTest.kt): `@RecordApplicationEvents` and `applicationEvents` removed
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the broker-reading bullet under 도우미, next to the Kafka topic and producer tests

### Landed

Two commits on `feat/order-events` on top of `c56cfd7`, the tip after issue 170 landed, pushed to origin:

- [`5d426ae`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/5d426ae) is the code, the tests, the standards bullet and the dated notes in ADR 0012, ADR 0032 and the design doc.
- [`9f3808a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/9f3808a) records this comment in this writing-material file.

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
- **The broker reader does not use a unique group and `earliest`.** A class's tests share its topic, and `DatabaseCleanUp`'s `TRUNCATE` restarts `AUTO_INCREMENT`. Order IDs, and so keys and eventIds, start again at 1 in every test, so reading from the earliest offset would pass earlier scenes' records off as this one's. Before each scene, the consumer is assigned every partition and placed at its end, and `position` fixes those offsets before the scene runs. Without the group protocol, the race that `earliest` guards against, an assignment that lands after the record, cannot happen. The group name `consumerProps` asks for is never used. `CODING_STANDARDS.md`, design section 8 and a dated note under spec 167's "기반" testing decision record this.
- **Waits:** 10 s for the records a scene expects, with the read returning as soon as they arrive, then a 1 s quiet period. A wrong record would follow a commit the call has already returned from, after one hand-off and one round trip to the local broker. A quiet period that is too short can only let a bug pass. It cannot make a correct scene fail. No scene sleeps or asserts elapsed time.
- **The scenes read the broker instead of recorded Spring events.** Issue 170 left this choice to 171, and spec 167's testing decisions say not to assert how many Spring events were published. Recorded events are a detail of the adapter, the port → Spring event hop, which week 7's outbox may change. The broker is what other services see. No test read `applicationEvents` any more, so `@RecordApplicationEvents` and the field left the committing base, following the rule against helpers that no test uses. The two bases now match `4779179`, and spec 167's "차례" section has a dated note saying so.
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

- Spec 167's seam 1 lists "포인트로 확정된 주문(fixture)에는 record가 없다". It is not among 171's criteria, and neither 172 nor 173 plans it. Confirming such an order is refused in TX A with `ORDER_ALREADY_CONFIRMED`, so it never reaches TX B. A dated note on the spec leaves the scene to the review that closes it.
- Whether `confirmedAt` should always carry six fraction digits. A `@JsonFormat` on `OrderConfirmedMessage.Payload.confirmedAt` would do it without touching the shared template.
- Issue 172 reads the broker too. By design section 8's rule ("같은 준비가 두 테스트에서 반복되면 그때 fixture에 도우미를 둔다"), that is when the reader leaves `OrderEventPublishingTest` for a shared helper.
- The relay's virtual threads have no name, so the WARN from its `catch` shows an empty thread name.

## Issue 172: a publish that fails or stops after TX B commits leaves the order confirmed and no record on the broker

Recorded 2026-10-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172#issuecomment-6442023152) of [issue 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172). The ticket names rules 5 and 7 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): the relay observes the publish and never throws, and the list of confirmations that publish nothing. The gap is the case rule 7 does not list, a confirmation that should publish and does not. The decisions are "이음매 2" of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167) and section 6 of the [order events design](../design/order-events.md). One run's values are in [W7 handoff: unpublished case](#w7-handoff-unpublished-case) below. The code is:

- [`OrderEventPublishGapTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventPublishGapTest.kt): the two scenes, with the relay's `KafkaTemplate` replaced by a `@MockkBean(relaxUnitFun = true)`
- [`TopicReader`](../../apps/commerce-api/src/test/kotlin/com/loopers/support/TopicReader.kt): the broker reader, moved out of `OrderEventPublishingTest` now that two classes read the broker
- [`OrderEventPublishingTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventPublishingTest.kt): reads the broker through `TopicReader`, with its scenes unchanged
- [`OrderConfirmedKafkaRelay`](../../apps/commerce-api/src/main/kotlin/com/loopers/adapter/integration/kafka/OrderConfirmedKafkaRelay.kt): KDoc only, with the corrected reason for `@Async`
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the publish-gap bullet under 도우미, next to the broker-reading bullet that now names `TopicReader`, and the `@MockkBean` field among what a subclass may add

### Landed

Three commits on `feat/order-events` on top of `215bdbe`, the tip after issue 171 landed:

- [`e492cf1`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/e492cf1) is the code, the tests, the standards bullets and the dated notes in ADR 0012, ADR 0032 and the design doc.
- [`ff6c238`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/ff6c238) records one run's values under "W7 handoff: unpublished case" in this writing-material file.
- [`9f2e070`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/9f2e070) records this comment in this writing-material file.

### Acceptance criteria

- [x] **Throwing scene: confirm answers confirmed; order CONFIRMED, payment SUCCESS, reservation consumed in a fresh transaction; no record for the eventId on the broker; exactly one WARN with eventId, orderId and cause; PG create count 1.** `send` throws `KafkaException("Send failed")` caused by Kafka's `TimeoutException` for a topic missing from the metadata. `confirm` answers `CONFIRMED` and `SUCCESS`. A fresh transaction finds the order `CONFIRMED` at the pinned instant, the payment `SUCCESS` under the PG's transaction key, and stock 3 and 4 of 5, which is what the reservation took, not released. The class's topic holds no record at all after the 1 s quiet period, which is stronger than none for that eventId. The PG received one create. The WARN is compared whole: `Could not publish the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, cause=org.springframework.kafka.KafkaException: Send failed`, with the order's ID in place of 1.
- [x] **Paused scene: the same DB facts are readable in a fresh transaction while the send is still paused and the broker has no record; after release the test ends cleanly (the record may or may not arrive; the assertion is about the gap, not the recovery).** The `send` stub stops on `Pause.hold()`. Once `confirm` has answered, `awaitHeld()` shows the relay reached `send`, and the scene reads the same facts in a fresh transaction and finds no record on the topic. Before releasing, it asserts that the relay has logged no WARN yet. That shows `send` was still stopped during the reads, since `Pause` goes on by itself after 10 s. Released, `send` throws as the producer does once `max.block.ms` has passed, so the record never arrives. The scene waits for the relay's one WARN, so the relay has finished before the next scene starts.
- [x] **Neither scene throws out of the confirm call or out of the relay.** `confirm` returns normally in both scenes. For the relay, the scenes' `ListAppender` also sits on the logger of `SimpleAsyncUncaughtExceptionHandler`, Spring's default handler for an exception that leaves an `@Async` method returning nothing. Both scenes see no ERROR there for 1 s after the WARN (Awaitility's `during`).
- [x] **The writing-material file carries the observed values, command and commit for the unpublished case.** [W7 handoff: unpublished case](#w7-handoff-unpublished-case) has a run at `e492cf1`, the exact Gradle command, the pinned clock, products and quantities, and both scenes' orderId, PG order ID, transaction key, eventId, confirmedAt, items, and order, payment, stock and broker state. It has no card values. Each scene logs these values in one INFO line.
- [x] **Nothing in `src/main` changes except KDoc, if any.** Only the relay's KDoc changed. It now gives the corrected reason for `@Async`, described below.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` passes with executed test counts checked.** 79 classes, **943 tests, 0 failures, 0 skipped**: 941 plus the two scenes. The recorded run of `OrderEventPublishGapTest` took 3.6 s and 2.5 s. The full run's output has no ERROR from the async handler.

### Red checks

The relay already existed, so both scenes passed on their first run. The relay was then mutated by hand, and the class was run against each mutation:

| Mutation | Where both scenes failed |
| --- | --- |
| `catch (e: Exception)` narrowed to `IllegalStateException`, so `send`'s exception leaves the relay | no WARN within 10 s |
| `throw e` after `logFailure` in the `catch` | `no exception left the relay`: the async handler logged an ERROR |
| `@Async` removed, with the `catch` narrowed as above | no WARN within 10 s, after `confirm` had answered normally |
| `@Async` removed, `AFTER_COMMIT` changed to `BEFORE_COMMIT`, with the `catch` narrowed | `confirm` threw the `KafkaException` |

Each mutation failed both scenes, and the file was restored byte for byte after each run. The third row is where ADR 0032 rule 4 turned out to be wrong.

### Decisions made while implementing

- **A mock, not a spy.** No record may reach the real broker, so the relay's `KafkaTemplate` is a springmockk `@MockkBean`, and the relay, the AFTER_COMMIT listener and the executor stay real. `KafkaTemplate` is a `SmartInitializingSingleton`, and Spring calls `afterSingletonsInstantiated()` on the mock that holds its bean name while the context starts. The first run failed with `MockKException: no answer found for KafkaTemplate(kafkaTemplate#1).afterSingletonsInstantiated()`. So the mock is `relaxUnitFun = true`: calls that return `Unit` are relaxed, and a `send` a scene did not stub still fails. It is the first mock among the fields a subclass may add, and ADR 0012 has a dated note.
- **The scenes confirm through the provided port `OrderConfirmer`, not HTTP.** The ticket says so. Spec 167's seam 2 says "확정은 200으로 답하고" and design section 6 says "실제 주문 API". The design's dated note records the port.
- **The stopped `send` throws once released.** `Pause` stands in for the producer waiting for the topic's metadata, and the throw is what `send` does once `max.block.ms` (60 s by default) has passed. The stopped scene is the middle of that wait, and the throwing scene is its end. Both throw the same failure, `KafkaException("Send failed")` caused by `TimeoutException("Topic order-events-publish-gap not present in metadata after 60000 ms.")`, so both log the same WARN. A mock cannot publish, so the record never arrives after release. The ticket allows either outcome, and waiting for the WARN ends the scene only after the relay has finished.
- **The class has a topic of its own, `order-events-publish-gap`, though it publishes nothing.** Otherwise a record that another class's relay publishes late could pass for this class's order, whose ID restarts at 1 like theirs.
- **The broker reader became `TopicReader` in `com.loopers.support`.** Design section 8 moves a helper out once two tests repeat the same setup, and issue 171 named this ticket. The reader stays in commerce-api's tests rather than `modules:kafka`'s testFixtures, because commerce-api is the only module that reads the broker. Design section 8 has a dated note saying so. The reader's waits are private: `OrderEventPublishingTest` had timed its INFO-line and thread waits with the reader's record wait, and it now has a `RELAY_WAIT` of its own.
- **ADR 0032 rule 4 was wrong about the exception.** Spring runs an AFTER_COMMIT listener in `TransactionalApplicationListenerSynchronization.afterCompletion`, and `TransactionSynchronizationUtils.invokeAfterCompletion` catches whatever it throws and logs `TransactionSynchronization.afterCompletion threw exception` at ERROR (spring-tx 7.0.9). So a synchronous relay's exception never leaves `commit()`, and the confirmation still answers. Only a hand-registered `afterCommit` synchronization propagates. The case for `@Async` stands on the other two reasons. `afterCompletion` also runs before `cleanupAfterCompletion`, so a synchronous publish holds TX B's connection. And a `send` waiting for metadata holds the confirming thread for up to `max.block.ms`. The relay's KDoc is corrected, and ADR 0032 rule 4 and design section 4 carry dated notes.

### Review

The two-axis review (standards and spec) ran on the working tree before the commit. It found no hard violation, no missing criterion and no scope creep. These findings were applied:

- The paused scene did not show that `send` was still stopped while it read. `Pause` goes on by itself after 10 s, and the empty broker read passes either way. The scene now asserts no WARN before release.
- The paused scene checked for an ERROR as soon as the WARN appeared, so an exception logged a moment later could slip through. Both scenes now watch for 1 s with Awaitility's `during`.
- The two scenes repeated their setup and their first assertions. These became `prepareTheOrder()` and `assertTheConfirmationCommitted()`, with the two products as fields.
- `TopicReader.RECORD_WAIT` also timed `OrderEventPublishingTest`'s log and thread waits. The reader's waits are now private, and each class has its own `RELAY_WAIT`.
- A line in the evidence log's raw string was 150 characters, over `.editorconfig`'s 130. ktlint does not check inside raw strings. The line was split.
- The relay's KDoc paragraph was re-wrapped, and the ADR 0032 note says 발행 where it said 전송, since `CONTEXT.md` lists 전송하다 under _Avoid_.

These findings were not applied:

- `BaseApplicationServiceTest`'s KDoc gained Korean text, though new comments are in English. The text extends an existing Korean list in the middle of a sentence.
- The list of what a subclass may add lives in three places: `CODING_STANDARDS.md`, that KDoc and ADR 0012's notes. The KDoc says `@MockkBean` without `relaxUnitFun = true`. The repo keeps the three in step by hand.
- Both broker-reading classes repeat the `TopicReader` open and close and the `ListAppender` capture. They join the copied helpers that issue 170's "Left for later" would move to `com.loopers.support` in one commit.
- The stopped scene asserts an outcome after release, one WARN, which the ticket says is not the point. It stays for the reason under the decisions above.

### Left for later

- Ticket 173 (docs) is the last of spec 167's tickets.
- Spec 167's seam 2 still says the confirmation answers 200, as over HTTP. Only design section 6's dated note says the scenes call the port, so the review that closes the spec could add a note there.
- The seam 1 scene for an order confirmed with points, and whether `confirmedAt` always carries six fraction digits, are still open from issue 171.

## Issue 173: the order-events documents match the code, and the week6 PR body is drafted

Recorded 2026-10-27 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/173#issuecomment-6442859997) of [issue 173](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/173), the last of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167)'s tickets. Nothing in the code changed. The documents are:

- the [order events design](../design/order-events.md): dated notes in sections 2, 4, 6, 7, 8 and 11, the ticket table (1.2), and the commands and counts (12)
- [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): dated notes on rules 4, 6 and 9
- [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167)'s body: dated notes on the sections whose placeholders were resolved
- this file: the [preface](#preface-what-this-material-is-evidence-for) and the [draft PR body](#draft-pr-body)

### Landed

Three commits on `feat/order-events` on top of `a58cc89`, not pushed:

- [`270070b`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/270070b) is the design doc and ADR 0032.
- [`3fedd5a`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/3fedd5a) is this file's preface and the draft PR body.
- [`6fc6b4f`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/6fc6b4f) records this comment in this file.

### Acceptance criteria

- [x] **Every placeholder name in spec 167 and the design doc is either the final name or carries a dated note with the final name.** All four of the spec's placeholders became the final names: `SpringEventOrderEventPublisher`, `OrderConfirmedKafkaRelay`, `OrderEventsKafkaConfig` and `OrderEventPublishingTest`. The spec's note under "브랜치와 단계" lists them, together with the names the spec never gave (`OrderEventsProperties`, `ClockConfig`, `OrderEventPublishGapTest`, `TopicReader` and the three topic and producer tests). The design doc had no placeholder names, only the unnamed slots of section 4's diagram. Its "이름" note names each slot.
- [x] **Design section 8 states the actual image tag and the actual context-import decision.** Both have been in section 8's first bullet since issue 168: `apache/kafka-native:4.3.2`, and every full context imports the fixture. Section 8 now also says that a full context with the test PG holds two `Clock` beans, and which scenes keep the port's three promises.
- [x] **The design doc's command block runs as written and the stated test count matches a fresh run.**
  - At `a58cc89`, `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check :modules:kafka:ktlintCheck` gave **79 classes, 943 tests, 0 failures, 0 skipped**. The shell had no `BOOTSTRAP_SERVERS`.
  - Each filter in section 12 was run as written. They gave 15, 2, 1 + 1 + 1, 5 + 26 and 63, each with `HexagonalArchitectureTest`'s 7.
  - The streamer's `ktlintCheck` and `check` pass.
  - The step added 26 tests (917 → 943).
- [x] **The writing material has the W7 handoff section with observed values and the draft PR body.** The W7 handoff section from issue 172 stays as recorded, with one dated note on the window. Design section 6 repeats the values and links it. The draft PR body is at the end of this file.
- [x] **`./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check :modules:kafka:ktlintCheck` passes.** It passed at `a58cc89`, the same code as this branch's tip. Only documents changed after it, and the pre-commit ktlint passed for both commits.

### Decisions made while implementing

- **ADR 0032 got three dated notes where the code deviated.**
  - Rule 4: its `afterCommit` reads as `afterCompletion(STATUS_COMMITTED)`.
  - Rule 6: `confirmedAt` goes out in `ISO_INSTANT`'s trimmed forms.
  - Rule 9: only the two broker-reading classes and `OverriddenOrderEventsTopicTest` override the topic. Every other relay publishes to the shared `order-events`, which nobody reads.

  The first draft added none, and the spec review found all three. ADR 0028's note is unchanged.
- **`CONTEXT.md` is unchanged.** 주문 확정 이벤트, 이벤트 ID, 누락 구간 and 발행하다 still describe the code. The definitions themselves use 알리다 and 보낸다, which 발행하다 lists under _Avoid_. That is inside the glossary, not a disagreement with the code, so it was left alone.
- **`CODING_STANDARDS.md` is unchanged.** The four techniques sit in an unbroken run of bullets under 도우미: the container, the topic and producer tests, the broker-reading tests (with the wait for an async publish) and the publish-gap test. Their claims were checked against both broker-reading classes.
- **The instructor's comments were read by block.** Chrome blocks clicks on the assignment page, so the threads could not be opened. The page's comment buttons still show which block each of the four comments sits on, so the preface reads the questions from those blocks.
- **Spec 167's seam 3 has no separate scene for a confirmation in the rolled-back base.** A confirmation runs only outside a transaction (ADR 0028 rule 2), so it cannot be called there. The rolled-back TX B scene of seam 1 is the overlap seam 3 allowed for.

### Review

A two-axis review (standards and spec) ran on the working tree before the commits. It found nothing missing and no scope creep. These findings were applied:

- The gap window said "up to `max.block.ms`". Once `send` has returned, the producer can go on trying for up to `delivery.timeout.ms` more. The preface and the PR body now say both, and the W7 handoff has a dated note.
- ADR 0032 rules 6 and 9, and design section 2's topic row, got the notes described above. So did spec 167's broker-reading note.
- The leftover `afterCommit` in design section 4 and ADR 0032 rule 4 got notes.
- Section 12 had said every command needs Docker. The fourth filter and the streamer check start no context.
- "Two `Clock` beans" was true only for full contexts. The gateway's light contexts hold only the `TestClock`. The design and the spec now say so.
- Ticket 171's row in section 1.2 had claimed that every scene reads the broker. The refusal outside a transaction reads nothing.
- New dated notes carry ticket links, and the new rows in section 11 are dated.
- Glossary words:
  - "loses the event" became "leaves no event".
  - "per send" became "per publish attempt".
  - 부르다 for an event became the port's name.
  - 사용자 left the spec note.
  - The outbox now "narrows" the gap rather than "closes" it.
- The PR body's startup line had said `BOOTSTRAP_SERVERS` "must resolve". Unresolved, the context still starts. Its container line now says one per JVM.
- The preface's first question had cited sections that answer only the outbox half. It now says the "why an event at all" half has little written evidence yet.
- Duplicated sentences were cut: the placeholder sentence in section 4, and the spec's seam 3 reasoning, which now points to section 8.

These findings were not applied:

- One run's values sit in the W7 handoff, in design section 6 and in the PR's Evidence. The ticket asks for them in the design doc, and the PR's Evidence needs them.
- "6주차 주문 이벤트 글감" names the document, as the design doc's own title (주문 이벤트 설계) does.
- `OrderEventsKafkaConfig`'s KDoc still says "an `afterCommit`". That is `src/main`, and this ticket changes documents only.

### Left for later

- **For the review that closes spec 167:**
  - seam 1's scene for an order confirmed with points
  - whether `confirmedAt` always carries six fraction digits
  - `OrderEventsKafkaConfig`'s KDoc wording
- **For step 2's grilling:** the argument for "why an event at all", which the assignment asks the design to show.
- **Before the PR opens:** `feat/order-events` must be pushed, so that the PR body's `blob/feat/order-events` links resolve.

## W7 handoff: unpublished case

Recorded 2026-10-27 from one run of [issue 172](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/172)'s reproduction, [`OrderEventPublishGapTest`](../../apps/commerce-api/src/test/kotlin/com/loopers/application/order/provided/OrderEventPublishGapTest.kt). This is the unpublished case of design section 6 that week 6 hands to week 7's outbox. The published-and-consumed case comes from step 2 (`feat/purchase-activity`). The ticket names rules 5 and 7 of [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md): the relay observes the publish and never throws, and the list of confirmations that publish nothing, which leaves this case out.

### How to run it

- **Commit:** [`e492cf1`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/e492cf1) on `feat/order-events`
- **Command:** `./gradlew :apps:commerce-api:test --tests 'com.loopers.application.order.provided.OrderEventPublishGapTest'`
- **Run:** 2026-10-27T11:15:22Z, 2 tests, 0 failures, 0 skipped (3.6 s and 2.5 s)
- **Containers:** MySQL, Redis and `apache/kafka-native:4.3.2` from the Testcontainers fixtures, one per JVM, stopped when the JVM ends. The test PG is WireMock inside the test JVM.

### Fixed inputs

| Input | Value |
| --- | --- |
| Clock | `TestClock` pinned at `2026-10-27T01:02:03.123456Z` |
| Products | two products with stock 5, priced 1,000 (ID 1) and 2,000 (ID 2) |
| Order | 1 × product 2 and 2 × product 1, listed in that order; total 4,000 |
| PG create | accepted as `SUCCESS` with transaction key `20261027:TR:9e3b71` |
| PG order ID | a fresh UUID per payment (`TestPgOrderIdGenerator`), so it differs between runs |
| Topic | `order-events-publish-gap`, the class's own (3 partitions) |
| Injected failure | the relay's `KafkaTemplate` is a springmockk `@MockkBean`. `send` throws `KafkaException("Send failed")` caused by `TimeoutException("Topic order-events-publish-gap not present in metadata after 60000 ms.")`, which is how `send` fails when the producer cannot reach the broker within `max.block.ms` |

Card values are left out.

### Observed

Each scene runs after the tables are truncated, so both get order ID 1.

| | Scene 1: the publish failed | Scene 2: the publish is stopped |
| --- | --- | --- |
| What `send` did | threw at once | stopped on `Pause` until the reads below were done, then threw |
| orderId | 1 | 1 |
| PG order ID | `2d50c75c-dc9f-4bf3-97c4-fd5186da94ce` | `7c92525b-8893-408e-9f7c-f348acc2efa8` |
| Transaction key | `20261027:TR:9e3b71` | `20261027:TR:9e3b71` |
| eventId (never published) | `order-confirmed:v1:1` | `order-confirmed:v1:1` |
| confirmedAt | `2026-10-27T01:02:03.123456Z` | `2026-10-27T01:02:03.123456Z` |
| items | `[{productId 1, quantity 2}, {productId 2, quantity 1}]` | same |
| Confirm's answer | `CONFIRMED` / `SUCCESS`, no exception | same, returned while `send` was stopped |
| Order (fresh transaction) | `CONFIRMED` at the pinned instant | same |
| Payment (fresh transaction) | `SUCCESS` under the transaction key | same |
| Stock (fresh transaction) | product 1: 3, product 2: 4 (reserved, not released) | same |
| PG create requests | 1 | 1 |
| Records on the topic | 0 after the 1 s quiet period | 0 while `send` was stopped |
| Relay log | one WARN at 20:15:38.624 on `virtual-75` | none while stopped. The evidence line was logged at 20:15:42.526 and the WARN at 20:15:42.530 on `virtual-86`, after release |
| Async handler ERROR | none for 1 s after the WARN | same |

The WARN line, identical in both scenes:

```
Could not publish the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, cause=org.springframework.kafka.KafkaException: Send failed
```

The test logs each scene's values in one INFO line, `W7 handoff, unpublished case: …`, from a fresh transaction after the assertions.

### What it shows and what it does not

- The database and the broker are not one transaction. TX B committed the confirmation, consumed the reservation and answered, and the event exists nowhere: not on the broker, not in a table. The only trace is one WARN line.
- Scene 2 is the window itself. Between TX B's commit and the end of `send`, a process that stopped would lose the event the same way, with no WARN at all. The relay runs after the commit on a virtual thread with no queue, and nothing waits for it when the context closes (design section 4), so the window is at least one hand-off wide. A broker that cannot be reached widens it to `max.block.ms` (60 s by default). (2026-10-27, issue 173: that is the wait inside `send` for the topic's metadata. Once `send` has returned, the producer can go on trying for up to `delivery.timeout.ms`, 120 s by default, so the window can be wider still.)
- It is a failure injected into one `send`, not an OS kill or a broker outage. The producer's own retries and `delivery.timeout.ms` are not exercised.
- Week 7 closes the window with an outbox row written inside TX B. ADR 0032's "다시 볼 조건" plans a `BEFORE_COMMIT` listener on the same Spring event, which leaves the saga step and the port unchanged.

### Corrections found on the way

- A red check made the relay synchronous and let the failure escape. The confirmation still answered normally. `@TransactionalEventListener(AFTER_COMMIT)` runs in `afterCompletion`, and Spring catches and logs what it throws (`TransactionSynchronization.afterCompletion threw exception`, spring-tx 7.0.9). ADR 0032 rule 4 and design section 4 had said the exception leaves `commit()` and turns the confirmation into a 500. Both now carry a dated correction, and the relay's KDoc is fixed. The case for `@Async` stands on the held connection and on `max.block.ms` holding the confirming thread.
- Issue 171's "Left for later" says the relay's WARN shows an empty thread name. The run shows `virtual-75` and `virtual-86`: logback prints an unnamed virtual thread as `virtual-<id>`.

## Draft PR body

Drafted 2026-10-27 by [issue 173](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/173), in the shape of PR 158 and PR 166 (Summary / Evidence / Merge Danger). The user opens the PR in the UI.

- **Base:** `week6` ← **head:** `feat/order-events`
- **Title:** `feat(commerce-api): publish an order's first PG confirmation to Kafka after TX B commits`
- The `blob/feat/order-events` links resolve once the branch is pushed.

````markdown
## Summary

This is week 6 step 1, the producer. When TX B first confirms an order paid through the PG, commerce-api now publishes `OrderConfirmed` v1 to `order-events` once TX B has committed. The consumer (`feat/purchase-activity`) and the delivery experiments (`feat/kafka-delivery`) come next, and both start from the contract in design §2.

- **Spec:** https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167, with tickets 168–173.
- **Decision:** [ADR 0032](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/order-events/docs/adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md).
- **Design:** [`docs/design/order-events.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/order-events/docs/design/order-events.md). §1.2 maps the tickets to what they built, and §12 has the commands and counts.
- **Writing material:** [`docs/research/week6-order-events-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/order-events/docs/research/week6-order-events-writing-material.md).

The step that confirms the order calls a required port inside TX B. The adapter hands the event to Spring, and an async AFTER_COMMIT relay publishes it:

```text
OrderPaymentSagaSteps.applyResult               # TX B, order locked; the only place an order becomes CONFIRMED
  order.completePayment(clock.instant())        # Clock bean; tests pin the @Primary TestClock
  orderRepository.save(order)
  if the payment changed to SUCCESS
    OrderEventPublisher.publish(OrderConfirmedEvent.from(order))   # required port; built from the locked order
      SpringEventOrderEventPublisher            # check(isActualTransactionActive()), then publishEvent
… TX B commits (a rolled-back TX B drops the event) …
OrderConfirmedKafkaRelay.relay                  # @TransactionalEventListener(AFTER_COMMIT) @Async("orderEventsExecutor"), a virtual thread per event
  OrderConfirmedMessage.from(event)             # outside the try: a bug here reaches Spring's async handler as an ERROR
  try kafkaTemplate.send(topic, orderId, message)
    whenComplete → INFO(eventId, topic, partition, offset) | WARN(eventId, orderId, cause)
  catch → WARN                                  # never throws, never awaits; delivery.timeout.ms is the only give-up
```

The record is keyed by the order ID string. The body is an envelope, with no Kafka headers and no amount, user, payment or PG identifier:

```json
{"eventId": "order-confirmed:v1:42", "eventType": "OrderConfirmed", "schemaVersion": 1,
 "payload": {"orderId": 42, "confirmedAt": "2026-10-27T03:14:15.123456Z",
             "items": [{"productId": 7, "quantity": 2}, {"productId": 8, "quantity": 1}]}}
```

- **Publishes nothing for:**
  - a pending, unknown, failed, rejected or conflicting outcome
  - a rolled-back TX B
  - a repeated application of a finished payment: a duplicate callback, a repeated reconcile, a late create answer
  - orders confirmed with points (no backfill)
- **Topic.** The app declares `order-events` with 3 partitions and 1 replica. The producer sets `acks: all`.
- **Tests.**
  - Every full test context imports `KafkaTestContainersConfig` (`apache/kafka-native:4.3.2`).
  - The `test` profile of `kafka.yml` has no broker address and turns on `spring.kafka.admin.fail-fast`. A context that forgets the import fails at once, as it does without MySQL.
  - The publishing tests read the broker through `TopicReader`.
- **Dependencies.** commerce-api now depends on `modules:kafka` (spring-kafka 4.1.1, kafka-clients 4.2.1). It also takes `spring-boot-starter-kafka-test`, for `KafkaTestUtils` only.
- **Also on this branch:**
  - `3aa9b91`, `4c930ce`, `82155e2`: the saga renames (`OrderPaymentSaga`, `OrderPaymentSagaSteps`) and the order info assembler, before step 1 began
  - a dated correction to ADR 0032 rule 4. A synchronous relay's exception would not leave `commit()`. The case for `@Async` stands on the held connection and on `max.block.ms`.

## Evidence

The mutations were run at ticket time and reverted ("Red checks" of issues 170–172 in the writing material).

- **Before:** with `@EventListener` in place of `@TransactionalEventListener(AFTER_COMMIT)`, `a rolled-back TX B publishes nothing, and the reconcile that then confirms the order publishes one record` finds a record for the rolled-back TX B.
  **After:** passes.
- **Before:** with `@Async` removed, `the record is published from a virtual thread, not from the thread that confirmed the order` sees `send` called on `Test worker`.
  **After:** passes.
- **Before:** publishing on every `Changed`, not only on a change to SUCCESS, fails the reconcile, callback, racing-duplicate, pending, failed and rejected scenes. Publishing whenever the saved order is CONFIRMED fails the late-create-answer and racing-duplicate scenes.
  **After:** all pass.
- **Before:** with `throw e` after the WARN in the relay's `catch`, both `OrderEventPublishGapTest` scenes fail, because Spring's async handler logs an ERROR.
  **After:** both pass, with one WARN and no ERROR for 1 s after it.
- **The unpublished case handed to week 7.** `OrderEventPublishGapTest` at `e492cf1`:
  - Confirm answers `CONFIRMED` and `SUCCESS`.
  - In a fresh transaction the order is CONFIRMED at the pinned `2026-10-27T01:02:03.123456Z` and the payment is SUCCESS.
  - Stock is 3 and 4 of 5, reserved and not released. The PG got 1 create.
  - The topic has 0 records, and the relay logs one WARN:
  ```text
  Could not publish the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, cause=org.springframework.kafka.KafkaException: Send failed
  ```
- **Whole check.** At `a58cc89`, `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check :modules:kafka:ktlintCheck` passes, with no `BOOTSTRAP_SERVERS` in the shell. That is 943 tests in 79 classes (917 before this branch), with 0 failures and 0 skips. `./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes too. The filters from design §12:
  ```bash
  ./gradlew :apps:commerce-api:test --tests '*OrderEventPublishingTest'   # 15: the publishing scenes, read from the broker
  ./gradlew :apps:commerce-api:test --tests '*OrderEventPublishGapTest'   # 2: the publish gap
  ./gradlew :apps:commerce-api:test --tests '*OrderEventsTopicTest' --tests '*OrderEventsProducerTest'   # 1 + 1 + 1: the topic and the producer config
  ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedEventTest' --tests '*.OrderTest'           # 5 + 26
  ./gradlew :apps:commerce-api:test --tests '*PaymentIntegrationTest'    # 63: week 5's scenes unchanged, plus the confirmation-time scene
  ```

## Merge Danger

**Door:** two-way

The schema doesn't change and there is no migration. A revert removes the port, the relay, the topic bean and the Kafka dependency, and confirmations go back to publishing nothing. Records already on `order-events` stay on the broker, and no consumer reads them yet.

**Blast Radius:** order confirmation

- **The publish gap remains.** A confirmation commits, consumes the reservation and answers even when its event never reaches the broker.
  - A failed publish leaves one WARN line, and nothing retries it.
  - A process that stops between the commit and the end of the publish leaves no event and no WARN.
  - The async relay widens the window by one hand-off. With the broker out of reach, `send` can wait up to `max.block.ms` (60 s) for metadata. Once it has returned, the producer can go on trying for up to `delivery.timeout.ms` (120 s).
  - Week 7's outbox narrows it (ADR 0032's 다시 볼 조건).
- **One broker.** Replication is 1 and `min.insync.replicas` is 1, so `acks=all` is no stronger than `acks=1` here. Nothing in this PR proves replication or durability.
- **Startup now involves Kafka.** Outside `local` and `test`, the broker address comes from `BOOTSTRAP_SERVERS`. Production keeps `fail-fast` off, so `KafkaAdmin` only logs when it cannot create the topic, and commerce-api still starts. Its publishes should then fail, each with the relay's WARN, since the relay catches whatever `send` throws (not tried against a real broker outage). In issue 168's probe, a context pointed at an address with no broker behind it waited out `KafkaAdmin`'s 30 s and 10 s, and only started after 61 s. A context left with the unresolved `${BOOTSTRAP_SERVERS}` started after 16 s.
- **The executor.** `orderEventsExecutor` is the context's only `Executor` bean, so Boot backs off from its `applicationTaskExecutor`. Nothing used that executor. Spring's default for an `@Async` without a qualifier is the context's unique `TaskExecutor`, so a future one would most likely run on the relay's virtual threads (not tried).
- **Logs only.** There are no metrics. MDC and tracing don't follow the relay's virtual thread, so each line carries its own eventId and orderId.
- **`confirmedAt` on the wire.** Jackson 3's `ISO_INSTANT` trims trailing zero groups (`…03.100Z`, `…03Z`), so a consumer must compare it as an instant. Whether to fix six digits with `@JsonFormat` is still open.
- **Docker for every full test context.** Each one needs the Kafka container as well as MySQL and Redis. The container starts once per JVM, and the first run pulls `apache/kafka-native:4.3.2`.
- **Left open:** spec 167's seam 1 scene for an order confirmed with points is for the review that closes the spec. Such an order is refused in TX A and never reaches TX B.
- **Out of scope:**
  - the consumer and its tables
  - the outbox
  - metrics
  - backfilling orders confirmed with points
  - the partition and redelivery experiments
````

## Spec 167: the closing review

Recorded 2026-10-28 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167#issuecomment-6457812800) of [spec 167](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/167). It closes the spec after a two-axis review of the step 1 branch, whose fixes landed on 2026-10-27.


Tickets 168–173 are closed, and the branch had a two-axis review of `origin/week6...b0fc848` (24 commits) on 2026-10-27. It checked the diff against the repo's standards (`CODING_STANDARDS.md`, `CONTEXT.md`, catalog design §5, ADRs 0009–0032) and against this spec, its six tickets and ADR 0032. Its four fixes are [`4e06cc3`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/4e06cc3), [`0cb8baa`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/0cb8baa), [`2e3cb5e`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/2e3cb5e) and [`f792658`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/f792658). `feat/order-events` is at [`f792658`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/f792658), the head of [PR 174](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/174) into `week6`. On that tree, `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:ktlintCheck :apps:commerce-api:check :modules:kafka:ktlintCheck` passes: 79 classes, 943 tests, 0 failures, 0 skipped. That is the count design §12 records. The streamer's `ktlintCheck` and `check` pass too.

The comment itself was drafted on 2026-10-27 and lost with that session, so this one was rebuilt on 2026-10-28 from the four commits and the documents they touched.

### Spec

All 27 user stories were checked. Nothing in production code goes beyond the spec, and no requirement was found implemented wrongly.

- **Stories 5 and 18: accepted by construction.** The pending, unknown, failed, rejected and conflicting outcomes have broker-reading scenes. A draft order and a restart have none, and need none: the port's only caller is `applyResult`, which runs inside TX B on a `Changed` result, and nothing is stored that a restart could replay.
- **Seam 1's scene for an order confirmed with points: not added.** Ticket 171's dated note left it to this review. Such an order is refused in TX A by `Order.ensureDraft` with `ORDER_ALREADY_CONFIRMED` and never reaches TX B, so a scene would test the week 3 guard, not the publish. The Testing Decisions line keeps a dated note.
- **`confirmedAt` on the wire: decided.** It stays Jackson 3's `ISO_INSTANT` form, micro precision with trailing zero groups trimmed (`…03.100Z`, `…03Z`), and no `@JsonFormat` fixes six digits. A consumer reads and compares it as an instant. Dated notes on design §2 and ADR 0032 rule 6 in [`f792658`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/f792658).
- **Kept: `applyResult`'s second SUCCESS check.** The `when` applies the outcome, the save follows, and the publish tests `result.to == SUCCESS` once more after it. The event is built from the saved order, so the check cannot move into the `when` branch without moving the save, and the repetition stays.

### Standards

- **Fixed: the glossary's publish wording.** `CONTEXT.md`'s context summary and its order confirmed event entry, ADR 0028's dated note and ADR 0032's changed-decision paragraph said the event 알린다 or called it an 알림, words the glossary keeps for the PG callback and lists under 발행하다's Avoid. They say 발행된다 now, and "다시 보내도" became "다시 발행해도". [`4e06cc3`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/4e06cc3)
- **Fixed: KDoc and names in the Kafka adapter and the saga.** KDoc says publish and unpublished instead of send, sent and lost, and "order confirmed event" instead of a bare "order event". `OrderPaymentSaga`'s lookup check is `checkLookupNeeded` again, the name spec 159's review kept: the saga rename had changed it to `isLookupNeeded` unannounced, leaving its KDoc and the payments design stale. `OrderPaymentSagaSteps` names both saved orders `savedOrder`. `OrderEventsKafkaConfig`'s executor KDoc no longer says "an `afterCommit`", since the listener runs in `afterCompletion` (ADR 0032 rule 4). `OrderConfirmedKafkaRelay` declares its `whenComplete` parameter types and reads the result through `checkNotNull`, so no inferred type carries the `@UnknownNullability` the IDE warns about. [`0cb8baa`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/0cb8baa)
- **Fixed: copied test helpers moved to support.** The three spy stubs `OrderEventPublishingTest` had copied from `OrderPaymentIntegrationTest` are extension functions in `com.loopers.support`'s `OrderPaymentSpies.kt`. Both broker-reading classes read their log appender through `LogLines.kt`'s `linesAt` and `awaitLinesAt`. `confirmPending(testPg, order)` joins `confirmOrder` on the committing base, and the fixtures gain `createPgTransactionOf` and `createOrderIn`. `createPgTransactionOf` has its own name because an overload of `createPgTransaction` would capture the existing `createPgTransaction(payment, status = …)` calls and take the payment's transaction key instead of drawing one. `CODING_STANDARDS.md` points at them. [`2e3cb5e`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/2e3cb5e)
- **Fixed: the payments design's ticket 151 row** names `OrderPaymentSteps` again, the bean's name at that ticket, which the saga rename had rewritten beside the older `OrderPaymentService`. [`f792658`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/f792658)

### Records

- The dated notes on this spec's body carry the label 닫는 검토.
- [`c0b2896`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/c0b2896) records this comment in `docs/research/week6-order-events-writing-material.md`.
