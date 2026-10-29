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

## Issue 186: a consumer group's assignment as members join one, two, then three

Recorded 2026-10-29 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/186#issuecomment-6491130512) of [issue 186](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/186). The decisions are the "공통 규칙" and "배정" sections of [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184) and seam A of its testing decisions. The step's run: `./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check`, **16 classes, 64 tests, 0 failures, 0 skipped**, up from 15 classes and 61 tests at `5ab77c0`. The code is:

- [`ConsumerGroupAssignmentTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/ConsumerGroupAssignmentTest.kt): the group assignment experiment
- [`ExperimentBroker`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/ExperimentBroker.kt): gains `describeGroup`, the group-description reader, and `partitionsByMember()`, which turns a description into the member → partitions table
- [`ExperimentBrokerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/ExperimentBrokerTest.kt): the reader checked against a consumer of the test's own
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the experiments bullet under 도우미 now describes this experiment, the reader and the table, and the base-class and constructor lists name the class

### Landed

Two commits on top of `5ab77c0`, issue 185's tip:

- [`38f20d3`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/38f20d3) is the experiment, the reader and its tests, and the standards sentences.
- The commit after it appends this comment to the writing material.

The branch is cut from `5ab77c0`, not from `feat/kafka-delivery`'s `0be006d`. Issue 185's two commits (`d7ca27e`, `5ab77c0`) were not on `feat/kafka-delivery`. Once `worktree-issue-185` was gone, no branch held them, and this ticket needs their helper. Merging `worktree-issue-186` therefore brings 185's two commits along.

### Acceptance criteria

- [x] **`ConsumerGroupAssignmentTest` passes at one, two and three members, with the broker's STABLE group description as the evidence of membership.** It is one test, 7.8 s in the full run, on `BaseApplicationServiceTest`'s shared context.
  - At each size it waits, at most 30 s, until `describeGroup` reports `Stable` with exactly that many members. The table comes from that description.
  - It then cross-checks each container's `assignedPartitions` against the table. That check is a bounded wait too: a member that is not the leader learns its assignment from its SyncGroup reply, which comes a little after the broker turns Stable.

  The three lines it logged in the run at `38f20d3`:
  ```
  E1 group assignment, 1 of 3 members
  topic=consumer-group-assignment-1c463270, partitions=2, group=consumer-group-assignment-f3b18162
  state=Stable, type=Classic, assignor=range
  member-1: partitions=[0, 1]
  probe partition=0, offset=0: read by [member-1]
  probe partition=1, offset=0: read by [member-1]

  E1 group assignment, 2 of 3 members
  topic=consumer-group-assignment-1c463270, partitions=2, group=consumer-group-assignment-f3b18162
  state=Stable, type=Classic, assignor=range
  member-1: partitions=[0]
  member-2: partitions=[1]
  probe partition=0, offset=1: read by [member-1]
  probe partition=1, offset=1: read by [member-2]

  E1 group assignment, 3 of 3 members
  topic=consumer-group-assignment-1c463270, partitions=2, group=consumer-group-assignment-f3b18162
  state=Stable, type=Classic, assignor=range
  member-1: partitions=[0]
  member-2: partitions=[1]
  member-3: partitions=[]
  probe partition=0, offset=2: read by [member-1]
  probe partition=1, offset=2: read by [member-2]
  ```
  The client log of the same run adds two things. Every member joined generation 1, 2 or 3 as the group reached that size, each time with `protocol='range'`. And at the second and third joins every member already in the group logged `partitions revoked` for all it held before `partitions assigned`: the eager protocol.
- [x] **Each partition has exactly one member at every size, and at three at least one member has none.**
  - At each size, `containsExactlyInAnyOrderElementsOf(0 until 2)` runs over the table's partitions flattened together.
  - At three, `anyMatch { it.isEmpty() }`.
  - Nothing asserts which member gets which partition.
- [x] **Each probe record was read by the member that owns its partition.** Probes are sent to a partition by number, with no key.
  - Each probe's readers must be exactly `[the member the table gives that partition]`.
  - They are counted after the third member joined. A probe that a later join handed to a second member would show two readers, as the second red check below does.
- [x] **The client's default group protocol and assignor are confirmed from source and recorded.** The source is kafka-clients **4.2.2**, the version the Boot 4.1.2 line resolves (spec 184 says 4.2.1).
  - **Group protocol.** `ConsumerConfig.java:115` sets `DEFAULT_GROUP_PROTOCOL = GroupProtocol.CLASSIC.name().toLowerCase(…)`, which `group.protocol` takes as its default at `:653`–`658`.
  - **Assignors.** `:446`–`448` give `partition.assignment.strategy` the default `List.of(RangeAssignor.class, CooperativeStickyAssignor.class)`.
  - **Eager.** `ConsumerCoordinator.java:256`–`270` makes a client's rebalance protocol the newest one every assignor in its list supports. `RangeAssignor` supports only EAGER, so the group rebalances eagerly whichever assignor the coordinator picks.
  - **Range.** Every member lists `range` first (`RangeAssignor.RANGE_ASSIGNOR_NAME`), so the coordinator picks it. The broker's own description agrees: `type=Classic, assignor=range`.
  - **Member order.** Range orders members by member ID (`AbstractPartitionAssignor.MemberInfo.compareTo`, with no static membership). The broker builds the member ID from the client ID plus a UUID (`member-1-ce26db2b-…`), so here `member-1` always takes partition 0. That is the assignor's choice, not the group's promise, and it is not asserted.

  Both defaults are in the experiment's KDoc for the design document to copy.
- [x] **The experiment's topic and group no longer exist on the broker after the test.**
  - `@AfterEach` stops every container. `stop()` waits up to the 10 s shutdown timeout for the consumer to close, which sends LeaveGroup.
  - It then closes the helper in `broker.use {}`, and the helper deletes the group and the topic.
  - A temporary probe after the close listed the broker's groups and topics with an admin client of its own: `group consumer-group-assignment-dcd9005f listed=false, topic consumer-group-assignment-a8a6f1f2 listed=false`.
- [x] **The red check and one run's tables are in the closing comment, and the comment is appended to the writing material.** Above and below, and in the writing material's issue 186 section.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes, and the executed test counts are checked.** It was run with `cleanTest` first: **16 classes, 64 tests, 0 failures, 0 errors, 0 skipped**, read by `scripts/test-counts --results apps/commerce-streamer/build/test-results/test`.
  - The three new tests are `ConsumerGroupAssignmentTest`'s 1 and `ExperimentBrokerTest`'s 2.
  - `BOOTSTRAP_SERVERS` was unset.
  - The pre-commit hook's tree-wide `ktlintCheck` passed.

### Red checks and probes

Each mutant was temporary: run, then reverted before the commit.

- **The ticket's red check.** Member 3 was started in another group (`setGroupId(if (member == "member-3") broker.nameGroup("another-group") else group)`). The scene went red in the bounded wait for three members, not in an assertion: `ConditionTimeoutException` after 30 s at `awaitStableGroupOf`. The broker's last description was `groupState=Stable` with two members, `member-1` on partition 0 and `member-2` on partition 1. No E1 line was logged, since the lines are logged after the assertions.
- **The review's red check.** The probe listener was made to never acknowledge. The scene failed with `[the members that read the probe on partition 0 at 1] Expecting actual: ["member-1", "member-1", "member-1"] to contain exactly (and in same order): ["member-1"]`. With nothing committed, each eager rebalance put `member-1` back at `earliest` on partition 0, and it read size 1's probe again at sizes 2 and 3. Before the review the readers were counted at each size, right after the probe was read, and this mutant would have passed.
- **The helper's TDD reds.** First `Unresolved reference 'describeGroup'`. Then, for a group nobody joined, `ExecutionException` caused by `GroupIdNotFoundException`. In `DescribeConsumerGroupsHandler`, the new describe's GROUP_ID_NOT_FOUND is retried on the classic API, and the 4.3 test broker answers that with GROUP_ID_NOT_FOUND too. So `describeGroup` returns null for a group the broker does not know, as `describedPartitions` does for a topic: the experiment's first wait polls before member 1's JoinGroup reaches the broker.
- **The cleanup probe**, above.

### Decisions made while implementing

- **The base is 185's tip, `5ab77c0`.** See Landed.
- **Each member is a `KafkaMessageListenerContainer`.** That is the single-consumer container, the one `ConcurrentMessageListenerContainer` creates per unit of concurrency.
  - It is built as `KafkaMessageListenerContainer(factory.consumerFactory, ContainerProperties(topic))`. The consumer therefore gets the listener factory's consumer settings (`ByteArrayDeserializer`, auto-commit off, `earliest`) and none of its container settings (concurrency 3, the stopping error handler, the converter).
  - `ContainerProperties.clientId` is a prefix that replaces `spring.kafka.client-id` (`commerce-streamer`). A standalone container adds no suffix, so the broker names the member exactly `member-N` (`DefaultKafkaConsumerFactory.createKafkaConsumer`).
- **The reader returns kafka-clients' `ConsumerGroupDescription` as it is.** The member → partitions table is a top-level `partitionsByMember()` extension beside the helper. Two classes use it, so it lives in `com.loopers.support`.
- **The probe listener acknowledges, then notes the read.** Under `MANUAL_IMMEDIATE` the acknowledgment commits on the consumer thread before the read is noted. The offset of every read the scene sees is therefore already committed, and the next join's revoke-all does not hand the record to another member.
- **One test walks the three sizes and asserts after the last**, from a `GroupSnapshot` per size.
  - The INFO lines are logged after the assertions, as `KeyPartitioningTest` logs its table.
  - The review suggested logging before asserting, so a red run keeps its table. That was not taken, so the two experiments read alike. The red check's message carries the broker's last description anyway.
- **`type` and `assignor` are logged, not asserted.** They are the client's defaults, which the ticket asks to verify and the design document to record. Asserting them would pin a default.
- **The group wait is 30 s.** Members already in the group learn of a rebalance from a heartbeat, every 3 s by the client default. The joins took about 1.5 to 2.5 s in the runs.

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards.** Two hard findings, both fixed:
  - `partitionsHeldBy` was private in two classes. It is now `partitionsByMember()` in `com.loopers.support`, per CODING_STANDARDS line 74.
  - `ExperimentBrokerTest`'s class KDoc said that each test closes the helper itself. It now says which tests close it and which leave it to `@AfterEach`.

  Judgement calls fixed:
  - The KDoc and the standards sentence used 커밋하다 for 읽었다고 표시하다, which CONTEXT.md lists under _Avoid_. They now say "the offset … is already committed".
  - `GroupSize` became `GroupSnapshot`.
  - "consumer factory" became "consumer 팩토리" in the standards.

  Kept: the table stays a `Map<String, List<Int>>`, named by its producer `partitionsByMember()` rather than given a type of its own.
- **Spec.** No gap and no scope creep. Fixed:
  - The readers were counted at each size, which was weaker than the KDoc's claim that a later join hands no probe to another member. They are now counted after the third join, and the review's red check shows the difference.
  - The KDoc merged the reason the rebalance is eager (`RangeAssignor` is in the list at all) with the reason the coordinator picks `range` (it is first).

  Not taken: logging before asserting (above). Noted for this comment: the 4.2.2 version, and that the red check went red in the bounded wait.

### Left for later

- **The design document** (issue 189) records the two defaults, the eager rebalance and the range order from the sources above. It also notes that spec 184 names 4.2.1.
- **The committed-offset reader** joins the helper in issue 188.
- **The commit link above** resolves once `worktree-issue-186`, with 185's two commits under it, is merged into `feat/kafka-delivery` and pushed.
