# Week 6 Kafka delivery: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 3 ticket's closing comment. The step is [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184), the third of week 6's three branches (`feat/kafka-delivery`, cut from `week6` at `5d5e567`), after [step 1's material](week6-order-events-writing-material.md) and [step 2's](week6-purchase-activity-writing-material.md). The rules it rests on are [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) and [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md); the step's design document, `docs/design/kafka-delivery.md`, is written by its documents ticket.

## Preface

A placeholder. [Issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189), the step's documents ticket, writes the preface: what the branch is and where each closing comment sits, the assignment's tables and offsets from one run, the red checks, the three counterexamples with the three steps' cumulative verification table, the two week 7 cases side by side, and the draft PR body.

## Issue 185: the same key lands on one partition, shown on an experiment topic the test creates and deletes

Recorded 2026-10-29 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/185#issuecomment-6490410714) of [issue 185](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/185). The decisions are the "공통 규칙" and "key → 파티션" sections of [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184) and seam A of its testing decisions. The step's run: `./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check`, **15 classes, 61 tests, 0 failures, 0 skipped**, up from 13 classes and 56 tests at `0be006d`. The code is:

- [`ExperimentBroker`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/ExperimentBroker.kt): the experiment-broker helper in `com.loopers.support`, which both bases' subclasses can open
- [`ExperimentBrokerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/ExperimentBrokerTest.kt): the helper read back from the broker
- [`KeyPartitioningTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/KeyPartitioningTest.kt): the key → partition experiment
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): one sub-bullet under 도우미, in the streamer's bullet, for the experiments and the helper

### Landed

Two commits on top of `0be006d`:

- [`d7ca27e`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/d7ca27e) is the helper, its test, the experiment and the standards bullet.
- The commit after it creates the writing material with its title, a placeholder preface for issue 189, and this comment as its first section.

### Acceptance criteria

- [x] **`KeyPartitioningTest` passes, and its INFO line holds the key → partition table.** One test on `BaseApplicationServiceTest`'s shared context, 1 of 1 passed in 1.2 s on the cached context. It creates a topic of its own with 3 partitions, sends five order ID strings (`1`, `2`, `42`, `1000`, `123456789`) three times each through the context's `KafkaTemplate`, reads each send's partition and offset from the `SendResult` metadata, and asserts per key that the partitions are the first send's. The line it logged in the run at `d7ca27e`:
  ```
  E2 key → partition
  topic=key-partitioning-995f4918, partitions=3, sends per key=3
  key=1: partition=0, offsets=[0, 1, 2]
  key=2: partition=2, offsets=[0, 1, 2]
  key=42: partition=1, offsets=[0, 1, 2]
  key=1000: partition=1, offsets=[3, 4, 5]
  key=123456789: partition=0, offsets=[3, 4, 5]
  ```
  The offsets are consecutive per key because every send is awaited before the next. Two pairs of keys share a partition (1 and 123456789 on 0, 42 and 1000 on 1): the run itself shows why the spread is not asserted.
- [x] **Nothing asserts that different keys spread across partitions.** The only assertion is `containsOnly(first send's partition)` per key. A `hasSize(3)` that stood before it was removed in the review: the size is by construction, and the ticket says "asserts only". The KDoc says why: five keys over three partitions can share one, and ordering by order does not rest on the spread.
- [x] **The experiment's topic no longer exists on the broker after the test.** `ExperimentBroker.close()` in `@AfterEach` deletes the topics it created and waits, at most 10 s, until `describeTopics` answers `UnknownTopicOrPartitionException` for each. `ExperimentBrokerTest` shows it from outside: an admin client of the test's own lists the topics after `close()` and finds neither of the two the helper made.
- [x] **The broker address comes only from the fixture.** The helper's constructor takes the context's `KafkaAdmin` and opens `Admin.create(kafkaAdmin.configurationProperties)`, as `BaseConsumerTest.emptyTheTopic` does. Those properties are `KafkaProperties.buildAdminProperties()`, whose `bootstrap.servers` is the `spring.kafka.bootstrap-servers` that `KafkaTestContainersConfig` registers. No constructor, method or constant of the helper or the two tests takes a string address.
- [x] **The red check and one run's table are in the closing comment, and the comment is appended to the writing material.** Below, and in the writing material's first section.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes, and the executed test counts are checked.** Run with `cleanTest` first so no older report could leak in: **15 classes, 61 tests, 0 failures, 0 errors, 0 skipped**, read by `scripts/test-counts --results apps/commerce-streamer/build/test-results/test`. The five new tests are `ExperimentBrokerTest`'s 4 and `KeyPartitioningTest`'s 1. `BOOTSTRAP_SERVERS` was unset. The pre-commit hook's tree-wide `ktlintCheck` passed.

### Red checks and probes

Each mutant was temporary, run and reverted before the commit.

- **The ticket's red check.** The third send of key 42 was forced onto `(first send's partition + 1) % 3` with the record's partition argument, in place of the key-chosen send. The scene failed on key 42 alone: `[the partitions of key 42's sends] Expecting ArrayList: [1, 1, 2] to contain only: [1] but the following element(s) were unexpected: [2]`. No INFO line was logged, since the table is logged after the assertions.
- **The helper's TDD reds.** Each slice of the helper started from its test's failure: `Unresolved reference 'ExperimentBroker'`, then `'nameGroup'`, then, for a named group no consumer joined, `ExecutionException: org.apache.kafka.common.errors.GroupIdNotFoundException: The group id does not exist.` from `deleteConsumerGroups`. The last is the reason the helper deletes groups one by one and skips that exception. The review's slice, closing in `@AfterEach` as well as in the test, failed all four helper tests with `IllegalStateException: Cannot accept new calls when AdminClient is closing.` until `close()` ran its deletes once.
- **From the sources.** kafka-clients 4.2.2 (the Boot 4.1.2 line; spec 184 says 4.2.1): `ProducerConfig` defines `partitioner.class` with default `null`; `KafkaProducer.partition` returns the record's partition when it has one, else, with no partitioner plugin and `partitioner.ignore.keys` false, `BuiltInPartitioner.partitionForKey(serializedKey, cluster.partitionsForTopic(topic).size())`, which is `Utils.toPositive(Utils.murmur2(serializedKey)) % numPartitions`. So the same key goes to the same partition only while the topic's partition count stays the same, which is what the design document (issue 189) should state.

### Decisions made while implementing

- **The helper is `ExperimentBroker`, a plain class opened per test, not a bean.** It takes the context's `KafkaAdmin` in its constructor, so a subclass of either base opens it in `@BeforeEach` and closes it in `@AfterEach` without an `@Import` that would split the cached contexts.
- **It names groups now, though this experiment joins none.** The ticket lists deleting "any consumer groups the test names" among the helper's duties, and issues 186 and 188 need it. `nameGroup(prefix)` returns a unique name and `close()` deletes it before the topics; the test closes its consumers first, because the broker refuses to delete a group with a member. A group nobody joined is skipped, so a scene that fails before its consumer joins still cleans up. The group-description reader and the committed-offset reader are not here, as the ticket says.
- **Closing again does nothing.** The helper's own tests close it mid-test to see what closing does, and the review asked that a test failing before that close still clean up. So every test closes in `@AfterEach` too, and `close()` runs its deletes once; the fourth helper test pins that. `AutoCloseable` asks for that anyway.
- **Creation and deletion are both waited for through one read**, `describeTopics`, which returns the partition count or null on `UnknownTopicOrPartitionException`. `createTopics().all().get()` alone would trust the controller's answer; the wait makes "the broker describes it with that many partitions" the fact.
- **The helper has a test of its own**, as the fixtures do (`PurchaseActivityFixturesTest`), reading the broker with an admin client of the test's own (`withAdmin`). The group test uses a consumer from the order events listener factory's consumer factory, which subscribes, waits to be assigned, commits its position and leaves, so the group exists on the broker without a member.
- **The experiment sits in `adapter.consumer`** beside `OrderEventConsumedCaseTest` and `OrderEventsListenerConfigTest`, the streamer's other Kafka-facing tests. A package of its own for four experiment classes was not opened; issue 189 can move them if the design document wants a name for the set.
- **The value sent is a plain string** (`send 3 of key 42`): the value plays no part in the partition, and the template's `JacksonJsonSerializer` writes it as a JSON string.
- **The table logs the offsets too.** They cost nothing and show each key's sends landing in sequence on its partition.

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards:** no hard violation. Block bodies, braced guards, backticked names, the helper in `com.loopers.support`, no subclass annotations, English comments, and the new bullet's claims against the code all held. Of the judgement calls, fixed: the helper test now closes in `@AfterEach` and `close()` is idempotent (above); `CODING_STANDARDS.md` line 95 lists the two new classes among those on `BaseApplicationServiceTest`, and line 105 says what each takes in its constructor; the bullet no longer says every experiment names a group; the KDocs cite "issue 184" rather than a Korean heading; `POLL` is `POLL_TIMEOUT`, `deleteTheTopic` is `closeTheBroker`, the three `Admin.create(...).use` reads share `withAdmin`, and "42 among them" says it is order-events design 2's example order. Kept: `nameGroup` ahead of an experiment that names a group (the ticket lists it, and issues 186 and 188 need it); `Admin.create` where `BaseConsumerTest` has `AdminClient.create` (`Admin` is the interface kafka-clients recommends, and the base is not this ticket's code); the `describeTopics` chain in both the helper and its test (the test reads the broker on purpose with a client of its own).
- **Spec:** nothing missing or wrong. Fixed: the `hasSize` before `containsOnly` (above). Checked and sound: the address can only be the fixture's (`KafkaAdmin.configurationProperties`, and `kafka.yml` overrides the admin address only under `local`); every wait is bounded (10 s gets and awaits, the helper test's poll inside an await); nothing asserts a spread; the helper holds no reader; both classes fail without a broker, so a run that could not reach one cannot pass. Noted: the standards bullet and `ExperimentBrokerTest` are beyond the ticket's list; the former follows the repo's habit for test conventions, the latter is the only place that shows the topic gone from outside.

### Left for later

- **The group-description reader** (issue 186) and **the committed-offset reader** (issue 188) join the helper when those tickets need them.
- **The design document** (issue 189) records the partitioner default from the source above, the partition-count caveat, and why the experiment runs in the streamer rather than commerce-api (the shared template).
- **Spec 184 names kafka-clients 4.2.1**; the test classpath resolves 4.2.2 since the Boot 4.1.2 upgrade. A dated note for issue 189.
- **The blob and commit links above** resolve once `worktree-issue-185` is merged into `feat/kafka-delivery` and pushed.
