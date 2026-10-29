# Week 6 Kafka delivery: writing material

Material for the technical writing quest, not the write-up itself. Each section is recorded from one step 3 ticket's closing comment, with five exceptions, all added by [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189), the step's documents ticket: the preface below, [one run of the four experiments](#one-run-of-the-four-experiments), [the counterexamples](#counterexamples-the-assignments-three-answered-by-a-test-or-a-red-check) with the three steps' cumulative verification table, [the two week 7 cases side by side](#w7-handoff-both-cases-side-by-side), and the [draft PR body](#draft-pr-body). The last section, [Spec 184: the closing review](#spec-184-the-closing-review), is recorded from the spec's own closing comment. The step is [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184), the third of week 6's three branches (`feat/kafka-delivery`, cut from `week6` at `5d5e567`), after [step 1's material](week6-order-events-writing-material.md) and [step 2's](week6-purchase-activity-writing-material.md). The rules it rests on are [ADR 0032](../adr/0032-order-confirmed-is-published-through-a-port-and-relayed-after-commit.md) and [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md), and the step's design is the [Kafka delivery design](../design/kafka-delivery.md).

## Preface: what this branch is and where its comments sit

Added 2026-10-29 by [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189).

### The branch

`feat/kafka-delivery` is step 3 of week 6's three, the delivery experiments. It was cut from `week6` at `5d5e567`, the squash of step 2's [PR 183](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/183) (the consumer), and it goes back into `week6` by its own PR.

- **What it adds.** Four experiments on the test broker, each one test class in commerce-streamer that runs in the regular `check`:
  - E1, a consumer group's assignment as members join one, two, then three, read from the broker's description of the group;
  - E2, every send of a key landing on one partition, read from the send metadata;
  - E3, a record recorded but not acknowledged, redelivered at its offset to the group's next consumer, writing nothing;
  - E4, a real lock wait failure after a partial write, which rolls back, stops the consumer, and is recorded after a restart.

  E1–E3 create and delete their own topic and group through `ExperimentBroker`. E4 uses its class topic, as step 2's consumer scenes do, because the real listener has to read it. The one production change is a failure line: the listener logs one ERROR line for any exception other than the two refusal types, worded to say that a restart is the remedy once the cause is gone.
- **What it does not change.** No code in commerce-api or `modules` changed, and commerce-api's check still gives 79 classes and 943 tests. There is no new ADR, table or production topic.
- **Before the tickets.** Five commits: the glossary's three changes (`9554a35`: 다시 발행하다 and 다시 전달되다, and a wider 멈추다), and four version bumps: liberica JDK 25.0.5 (`1618ee7`), Boot 4.1.2 with Jetty EE10 12.1.14 (`5ba74ca`), Gradle 9.8.2 with typesafe-conventions 0.12.1 (`2daf469`) and springdoc 3.2.0 (`0be006d`).
- **How the tickets landed.** Each ticket ran on a worktree branch. 185 and 186 reached `feat/kafka-delivery` as a fast-forward, since 186 was cut from 185's tip once 185's branch was gone. 187 merged as `342d605`, and 188, also cut from 185's tip, as `756f0b7`. Both merges kept both sides of their conflicts.
- **The design** is the [Kafka delivery design](../design/kafka-delivery.md): the four experiments (§2), their topics and groups (§3), the evidence rules (§4), the config and limits (§5), the assignment's names (§6), and the commands and counts (§7). The [draft PR body](#draft-pr-body) is at the end.

### Where each closing comment sits

| Issue | What it built | Closing comment | Section |
| --- | --- | --- | --- |
| [185](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/185) | E2, key → partition, and the experiment-broker helper | [comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/185#issuecomment-6490410714) | [Issue 185](#issue-185-the-same-key-lands-on-one-partition-shown-on-an-experiment-topic-the-test-creates-and-deletes) |
| [186](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/186) | E1, group assignment, and the group-description reader | [comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/186#issuecomment-6491130512) | [Issue 186](#issue-186-a-consumer-groups-assignment-as-members-join-one-two-then-three) |
| [187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187) | E4, the real listener failure, and the failure line | [comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187#issuecomment-6490587755) | [Issue 187](#issue-187-a-transient-db-failure-after-a-partial-write-rolls-back-stops-the-consumer-with-a-line-naming-the-record-and-is-recorded-after-a-restart) |
| [188](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/188) | E3, redelivery after commit, the committed-offset reader, and step 2's republish scene renamed | [comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/188#issuecomment-6491392437) | [Issue 188](#issue-188-a-record-recorded-but-not-acknowledged-is-redelivered-to-the-groups-next-consumer-and-writes-nothing) |
| [189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189) | the documents: the design, this preface, one run, the counterexamples, both week 7 cases, the draft PR body and the dated notes | [comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189#issuecomment-6492297157) | [Issue 189](#issue-189-step-3s-documents-match-the-code-with-the-limits-the-counterexamples-both-week-7-cases-the-commands-and-counts-and-a-draft-pr-body) |
| [184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184) | the spec's closing review: four fixes, and the test broker moved to `apache/kafka-native:4.4.0` | [comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184#issuecomment-6496099667) | [Spec 184](#spec-184-the-closing-review) |

Each section starts with a "Recorded …" line that links its comment. The comments themselves carry full blob URLs where these sections link files relatively.

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

## Issue 187: a transient DB failure after a partial write rolls back, stops the consumer with a line naming the record, and is recorded after a restart

Recorded 2026-10-29 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187#issuecomment-6490587755) of [issue 187](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/187). The rule is rule 5 of [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) (an unprocessable event stops the consumer, provisionally; its dated note of this ticket widens the stop to a transient failure), the scene is the "일시 장애(DB 연결, 잠금 대기)" row of section 6 of the [purchase activity design](../design/purchase-activity.md), and the decisions are spec 184's "실제 리스너의 실패" and "리스너의 ERROR 줄" at seam C. The code is:

- [`OrderEventsListener`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventsListener.kt): the step's one production change, a failure line for every exception that leaves the listener other than the two refusal types
- [`OrderEventsListenerConfig`](../../apps/commerce-streamer/src/main/kotlin/com/loopers/adapter/consumer/OrderEventsListenerConfig.kt): its KDoc names the two lines
- [`OrderEventsListenerFailureTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/OrderEventsListenerFailureTest.kt): the scene, on a class topic of its own
- [`BaseConsumerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseConsumerTest.kt): `assertOnlyTheRowsOf`, moved here from [`OrderEventsListenerStopTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/OrderEventsListenerStopTest.kt) and widened to several events
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the failure scene under 도우미; [ADR 0033](../adr/0033-purchase-activity-is-recorded-by-reading-first-and-an-unprocessable-event-stops-the-consumer.md) rule 5 and the [purchase activity design](../design/purchase-activity.md) sections 6 and 9: dated notes

### Landed

Two commits for `feat/kafka-delivery`, on top of `0be006d`, made on the issue worktree's branch:

- [`009afee`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/009afee) is the code, the test, the standards and the dated notes.
- The commit after it adds this section, and creates this file: the key-partitioning ticket ([185](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/185)) had not landed.

### Acceptance criteria

- [x] **Every exception that leaves the listener, other than the two refusal types, logs the new ERROR line once and is rethrown; the refusal line is unchanged.** A third catch arm, `Exception`, follows the two refusal arms, logs `Could not record an order confirmed event; the consumer stops until it is restarted. eventId=…, orderId=…, partition=…, offset=…, cause=…` and rethrows. The order ID is the record's key, as in the refusal line, and the cause is the exception's `toString`. A second unique-key violation inside `recordSettlingARace` propagates out of it into that arm, so it gets the line too; it has no scene of its own (issue 180's leftover). A `ConversionException` still never reaches the listener: the stop class's not-JSON scene still asserts no listener line, and passed in the full run. The five refusal scenes passed unchanged.
- [x] **The scene shows a real lock-wait failure after a partial write.** Both tables empty for the failing order: only the normal event's rows stood (one confirmation, five activities in the run below). The failure line quotes the statement that failed, an activity insert, so the confirmation and the first item had been written and rolled back with it. The committed offset was still n, 1 in the run. The container had stopped abnormally (`awaitStopped`, `isInExpectedState` false). Exactly one new ERROR line, naming the record (`partition=0, offset=1`). **Red check:** the first run, before the production change, failed on exactly that assertion, `java.util.NoSuchElementException: List is empty.` from `listenerLines(Level.ERROR).single()`; the stop, the abnormal flag, the uncommitted offset and the rows had already held.
- [x] **After the blocking connection rolls back and the container restarts in the same group, the same record is recorded and the committed offset is n+1.** `start()` on the stopped container, no seek, no emptied topic. The restarted consumer's line is `Recorded the order confirmed event. … partition=0, offset=1, result=RECORDED`, the same partition and offset as the failure line; the tables then held two confirmations and seven activities, and the committed offset was 2.
- [x] **The translated exception type and the measured wait.** Spring translates MySQL 1205 into `org.springframework.dao.CannotAcquireLockException`, not a `DataIntegrityViolationException`, so `recordSettlingARace` did not call the recorder again. Its message is Hibernate's with the SQL appended: `could not execute statement [Lock wait timeout exceeded; try restarting transaction] [insert into purchase_activity (confirmed_at,created_at,deleted_at,order_id,product_id,quantity,updated_at) values (?,?,?,?,?,?,?)]; SQL [insert into purchase_activity (…) values (?,?,?,?,?,?,?)]`. The cause chain, read from spring-kafka's `Error handler threw an exception` line of the first red run: `KafkaException: Stopped container` → `ListenerExecutionFailedException` → `CannotAcquireLockException` → `org.hibernate.exception.LockTimeoutException` → `com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException: Lock wait timeout exceeded; try restarting transaction`, which is `ER_LOCK_WAIT_TIMEOUT`, 1205. The wait: the container had stopped **3229 ms** after the failing record was published (asserted only from below, at least 3 s; an earlier run of the day gave 3471 ms). In the log, Hibernate's WARN for the failed statement came 3.12 s after the normal event's RECORDED line (`16:19:27.410` → `16:19:30.527`), the failure line 19 ms after the WARN, and the restarted consumer's RECORDED line 0.26 s after the failure line.
- [x] **Both red checks went red** ([below](#red-checks-and-probes)).
- [x] **Dated notes** on ADR 0033 rule 5, design section 6 (the table's row, and the way to flow again) and section 9. `scripts/check-doc-notes` passes on ADR 0033 and the design (insert-only), and `scripts/check-doc-links` on those two, the standards and this file. The standards are edited in place, which the notes check does not govern.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes with executed test counts checked.** From `cleanTest`: **14 classes, 57 tests, 0 failures, 0 skipped**, up from 13 classes and 56 tests at spec 176's closing review. The new test is `OrderEventsListenerFailureTest`'s 1; the stop class still runs 6 and the listener class 6. The first run of the day failed `ktlintTestSourceSetCheck` on a test name and a wrapped assertion over 130 columns, fixed before this run. The counts come from the JUnit XML reports. `BOOTSTRAP_SERVERS` was unset.

### One run

Commit [`009afee`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/009afee), `./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:test --tests 'com.loopers.adapter.consumer.OrderEventsListenerFailureTest' --tests 'com.loopers.adapter.consumer.OrderEventConsumedCaseTest'`, 2026-10-29 16:19 KST, the scene's own INFO line with the drawn IDs as they came:

```
Real listener failure, a lock wait timeout after a partial write
topic=order-events-listener-failure, partition=0, normal record offset=0, failing record offset=1
committed offset of commerce-streamer on partition 0: before=1, during the stop=1, after the restart=2
the container had stopped 3229 ms after the failing record was published (innodb_lock_wait_timeout=3)
failure line: Could not record an order confirmed event; the consumer stops until it is restarted. eventId=order-confirmed:v1:2235366049729120206, orderId=2235366049729120206, partition=0, offset=1, cause=org.springframework.dao.CannotAcquireLockException: could not execute statement [Lock wait timeout exceeded; try restarting transaction] [insert into purchase_activity (confirmed_at,created_at,deleted_at,order_id,product_id,quantity,updated_at) values (?,?,?,?,?,?,?)]; SQL [insert into purchase_activity (confirmed_at,created_at,deleted_at,order_id,product_id,quantity,updated_at) values (?,?,?,?,?,?,?)]
rows during the stop
processed_order_confirmation: id=1, event_id=order-confirmed:v1:6356396606550300347, order_id=6356396606550300347, confirmed_at=1972-02-24T06:12:06.264362Z
purchase_activity: id=1, order_id=6356396606550300347, product_id=608386730030075030, quantity=4, confirmed_at=1972-02-24T06:12:06.264362Z; id=2, order_id=6356396606550300347, product_id=4115514270266794041, quantity=6, confirmed_at=1972-02-24T06:12:06.264362Z; id=3, order_id=6356396606550300347, product_id=1682702682672518999, quantity=10, confirmed_at=1972-02-24T06:12:06.264362Z; id=4, order_id=6356396606550300347, product_id=3493383134076385628, quantity=7, confirmed_at=1972-02-24T06:12:06.264362Z; id=5, order_id=6356396606550300347, product_id=7729114344715894439, quantity=4, confirmed_at=1972-02-24T06:12:06.264362Z
rows after the restart
processed_order_confirmation: id=1, event_id=order-confirmed:v1:6356396606550300347, order_id=6356396606550300347, confirmed_at=1972-02-24T06:12:06.264362Z; id=3, event_id=order-confirmed:v1:2235366049729120206, order_id=2235366049729120206, confirmed_at=1970-09-25T17:36:25.201364Z
purchase_activity: id=1, order_id=6356396606550300347, product_id=608386730030075030, quantity=4, confirmed_at=1972-02-24T06:12:06.264362Z; id=2, order_id=6356396606550300347, product_id=4115514270266794041, quantity=6, confirmed_at=1972-02-24T06:12:06.264362Z; id=3, order_id=6356396606550300347, product_id=1682702682672518999, quantity=10, confirmed_at=1972-02-24T06:12:06.264362Z; id=4, order_id=6356396606550300347, product_id=3493383134076385628, quantity=7, confirmed_at=1972-02-24T06:12:06.264362Z; id=5, order_id=6356396606550300347, product_id=7729114344715894439, quantity=4, confirmed_at=1972-02-24T06:12:06.264362Z; id=9, order_id=2235366049729120206, product_id=1, quantity=2, confirmed_at=1970-09-25T17:36:25.201364Z; id=10, order_id=2235366049729120206, product_id=2, quantity=1, confirmed_at=1970-09-25T17:36:25.201364Z
```

The listener's three lines of the run, in order: `Recorded … orderId=6356396606550300347, partition=0, offset=0, result=RECORDED` on consumer thread `er-events-0-C-1` at `16:19:27.410`; the failure line on the same thread at `16:19:30.546`; `Recorded … orderId=2235366049729120206, partition=0, offset=1, result=RECORDED` on `er-events-0-C-2`, a consumer of the restarted container, at `16:19:30.808`.

The id gaps are the partial write's trace. The confirmations go 1, 3: id 2 went to the confirmation the listener inserted and rolled back. The activities go 1 to 5, then 9, 10: id 6 went to the row the blocking connection held and rolled back, 7 to the first item the listener inserted and rolled back, and 8 to the second item's insert, which took its auto-increment value before it waited and failed. InnoDB does not give auto-increment values back on rollback. The failure line's statement, `insert into purchase_activity`, says the same from the other side: the confirmation's insert had already run.

### Red checks and probes

Each mutant was a local edit, run once and restored byte for byte before the commit.

- **A listener that catches the failure and acknowledges anyway** (the third arm's `throw e` replaced by `acknowledgment.acknowledge(); return`): the scene failed in `awaitStopped` after 10 s (`ConditionTimeoutException: Condition with Lambda expression in com.loopers.support.test.BaseConsumerTest was not fulfilled within 10 seconds.`). The log had the failure line once, one RECORDED line (the normal event's), and no `Stopped container`: the record was skipped, acknowledged with no rows, and the container went on.
- **The scene without the blocking connection** (`holdTheRowOf` removed): the scene failed in the same wait. Two RECORDED lines, no failure line, no lock wait: the record was recorded with no stop.
- **The first red run** is the acceptance criteria's: before the production change the one failing assertion was the empty ERROR list.
- **From the sources.** Hibernate 7.4 (`MySQLDialect.buildSQLExceptionConversionDelegate`): 1205 `ER_LOCK_WAIT_TIMEOUT` → `LockTimeoutException`; 3572, 1207 and 1206 → `LockAcquisitionException`; 1062 → `ConstraintViolationException`. `LockTimeoutException extends LockAcquisitionException extends PessimisticLockException extends JDBCException`. Spring 7.0 (`org.springframework.orm.jpa.hibernate.HibernateExceptionTranslator.convertHibernateAccessException`): `LockAcquisitionException` → `CannotAcquireLockException(message + "; SQL [" + sql + "]")`, checked before `PessimisticLockException` → `PessimisticLockingFailureException`. The classpath has hibernate-core 7.4.13 and spring-orm 7.0.10; the sources read were the nearest cached ones, 7.4.5 and 7.0.9, and the run agrees with them. ADR 0018 rule 8 already names the family (`PessimisticLockingFailureException` and its subtypes) for commerce-api's 409.
- **Hikari and the session variable.** The blocking connection comes from the same `HikariDataSource` as the listener's, so it carries `jpa.yml`'s `sessionVariables: innodb_lock_wait_timeout=3`; only the waiting side's value matters, and that is the listener's. The pool is 10 in the test profile; the scene holds one connection.

### Decisions made while implementing

- **The catch-all is `Exception`, after the two refusal arms.** An `Error` stays with spring-kafka. The line's cause is the exception's `toString`, as the refusal line's and step 1's relay WARN; spring-kafka's own line carries the stack trace.
- **The failure line is asserted in two parts.** The Hibernate message carries the SQL statement with its column list, which is not the scene's to pin, so the scene compares the line up to and including the translated type with `startsWith`, and MySQL's 1205 text with `contains`. The refusal scenes still compare whole lines.
- **The wait is asserted from below only.** The house rule asserts times by generous upper bounds; a lower bound cannot fail from a slow machine, and it is what "the wait ends after the session's 3 s" means: the container cannot stop before the lock wait has run out. The base's 10 s stop wait is the upper bound.
- **The numeric 1205 is not in any log line.** Logback's `IThrowableProxy` keeps class names and messages, not a `SQLException`'s vendor code, so an appender on the container's logger as in the not-JSON scene would show the same `MySQLTransactionRollbackException` message the listener's line already carries. The chain was read once from the first red run and is recorded above.
- **The blocking row is raw JDBC on a connection from the `DataSource`,** taken in the constructor as the Redis failure tests take `StoppableRedis`. Not `JdbcTemplate`, which returns its connection after each call, and not a `Pause` on a spied bean, which spec 184 ruled out: the failure is a real lock wait, not a stub.
- **The connection is released twice on purpose:** in the scene, to remove the failure before the restart, and in the class's `@AfterEach`, which runs before the base's (JUnit runs a subclass's after-each methods first), so a failing assertion leaves no lock for the base's `TRUNCATE` to wait on (`lock_wait_timeout=10` on the test container).
- **Both records go to partition 0 by `publish`'s partition argument**, so one partition's committed offset tells the whole story; the key would otherwise choose, and the two orders' keys differ.
- **`assertOnlyTheRowsOf` moved to the base** and takes several events; the scene needs it for one event during the stop and two after the restart, and the stop test used it for one. The stop test keeps `assertNoRows`.
- **The scene logs what it observed** as the consumed case does, rows during the stop and after the restart included, so the writing material copies one run rather than a reconstruction. The two row lines are the base's `storedConfirmationsLine` and `storedActivitiesLine`, which the consumed case now uses too.
- **`CODING_STANDARDS.md` is edited beyond the ticket's docs list:** a bullet for the failure scene under 도우미, the consumer-scene bullet naming the fourth class, and the constructor sentence, which already contradicted the stop test at `0be006d` (it takes `KafkaProperties` and `OrderEventsProperties` for its byte producer).

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards:** no hard violation in the code. Fixed: the standards' consumer-scene bullet still named three classes; the material claimed the notes check ran on the standards, named the worktree branch where step 2 named the integration branch, and spoke of the step's design document as if it existed; the scene's `rows()` repeated the consumed case's two row formats and leaned on the caller's `trimMargin` (now the base's `storedConfirmationsLine` and `storedActivitiesLine`, which the consumed case uses too); `logTheScene` took the three offsets as a list read by index (now named parameters); `LOCK_WAIT_TIMEOUT` beside `LOCK_WAIT` (now `LOCK_WAIT_TIMEOUT_MESSAGE`); `FAILURE_TYPE` as a spelled-out class name (now `CannotAcquireLockException::class.java.name`); the log heading's unexplained "E4" (now spelled out, which is why the recorded run above is the day's third). Kept: `logRefusal` and `logFailure` as two functions that differ in their first sentence, and `recordedLine` built by hand as the other two consumer classes build theirs; a shared builder would touch `OrderEventsListenerTest`, where issue 188 renames a test.
- **Spec:** nothing missing. Fixed: the partial write was shown only by the id gaps, so the scene now asserts that the failed statement is an activity insert; the listener's KDoc said any other exception leaving the listener is a failure to record, where an exception from acknowledging comes after the commit and gets no line. Noted, and recorded under the decisions above: the standards are edited beyond the ticket's docs list.

### Left for later

- **The second violation in `recordSettlingARace`** gets the failure line by construction only; the race itself still has no permanent scene (issue 180's leftover).
- **The restart is `start()` on the container**, not a process restart: spec 184 lists killing the process as out of scope, and the design document (issue 189) will list it as a limit.
- **A policy that retries transient failures** (`DefaultErrorHandler` with backoff, a DLT for the unprocessable) stays ADR 0033 rule 5's production shape; the two lines' wording is what such a handler would classify by.
- **Issue 189** writes `docs/design/kafka-delivery.md`, this file's preface and the cumulative tables.

## Issue 188: a record recorded but not acknowledged is redelivered to the group's next consumer and writes nothing

Recorded 2026-10-29 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/188#issuecomment-6491392437) of [issue 188](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/188). The decisions are the "공통 규칙" and "커밋 뒤 다시 전달" sections of [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184) and seam B of its testing decisions; the words are 다시 전달되다 and 다시 발행하다 in the streamer's `CONTEXT.md`. The step's run: `./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check`, **16 classes, 63 tests, 0 failures, 0 skipped**, up from 15 classes and 61 tests at `5ab77c0`. The code is:

- [`RedeliveryAfterCommitTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/RedeliveryAfterCommitTest.kt): the redelivery experiment, on `BaseConsumerTest`
- [`ExperimentBroker`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/ExperimentBroker.kt): gains the committed-offset reader `committedOffset(group, TopicPartition)`
- [`ExperimentBrokerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/ExperimentBrokerTest.kt): the reader read back; `joinAndLeave` takes the commit and polls on the test thread
- [`OrderEventsListenerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/adapter/consumer/OrderEventsListenerTest.kt): step 2's republish scene renamed
- [`BaseConsumerTest`](../../apps/commerce-streamer/src/test/kotlin/com/loopers/support/test/BaseConsumerTest.kt): a KDoc paragraph on the experiment that extends it without a container
- [`CODING_STANDARDS.md`](../../CODING_STANDARDS.md): the experiment in the step 3 bullet under 도우미, what it takes in its constructor, and a clause on the base's line
- The [purchase activity design](../design/purchase-activity.md) section 7 and [step 2's writing material](week6-purchase-activity-writing-material.md): dated notes where they paraphrase the old name

### Landed

Two commits on top of `5ab77c0`:

- [`7874b75`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/7874b75) is the experiment, the reader, the rename, the standards and the dated notes.
- The commit after it appends this comment to the writing material.

### Acceptance criteria

- [x] **`RedeliveryAfterCommitTest` passes the six steps.** 1 test, 1.8 s, in the run at `7874b75` (2026-10-29T08:05Z). The line it logged:
  ```
  E3 redelivery after commit
  topic=redelivery-after-commit-f8675d85, partitions=1, group=redelivery-after-commit-cfe86bde
  earlier record of another order: partition=0, offset=0
  the event's record: partition=0, offset=1
  redelivery-first (member redelivery-first-0241ab92-4907-4f7c-adab-e8efd1306090): given partition=0, offset=1
  redelivery-second (member redelivery-second-0408461d-9d01-4f51-91a9-ec20787c1554): given partition=0, offset=1
  position of redelivery-first before its close=2
  committed offset of the group on partition 0: before the first read=1, after the first read=1, after the first close=1, after the second read=2
  listener: Recorded the order confirmed event. eventId=order-confirmed:v1:1927879484222047426, orderId=1927879484222047426, partition=0, offset=1, result=RECORDED
  listener: Recorded the order confirmed event. eventId=order-confirmed:v1:1927879484222047426, orderId=1927879484222047426, partition=0, offset=1, result=ALREADY_RECORDED
  processed_order_confirmation id=1, event_id=order-confirmed:v1:1927879484222047426, order_id=1927879484222047426, confirmed_at=2005-05-15T14:31:41.323515Z, updated_at=2026-10-29T08:05:37.782523Z
  purchase_activity id=1, order_id=1927879484222047426, product_id=5936611326122195325, quantity=3, confirmed_at=2005-05-15T14:31:41.323515Z, updated_at=2026-10-29T08:05:37.784319Z
  ```
  - [x] **Both deliveries are at the same partition and offset n.** n = 1: partition 0, offset 1 for `redelivery-first` and again for `redelivery-second`, asserted as `(partition, offset)` pairs against the send metadata. The topic has one partition, so the offset carries the evidence (decisions below).
  - [x] **The broker's committed offsets go n → n → n+1.** Read from the broker for this group after each step: 1 before the first read, 1 after it, 1 after the first close, 2 after the second read.
  - [x] **The rows are unchanged after the second delivery.** Both tables are read as lines with each row's ID and `updated_at` after the first delivery and again at the end, and the two reads are equal. The first is also checked to be the event's: its event ID, and its products and quantities.
  - [x] **The listener logs `RECORDED` then `ALREADY_RECORDED`.** Exactly those two INFO lines, both at partition 0, offset 1.
- [x] **Nothing seeks, resets or rewinds, and the first consumer's close is shown to commit nothing.** The scene calls no seek and moves no group offset. The first consumer commits n inside `onPartitionsAssigned`, before the client fetches the committed offsets it reads from, and the second reads the group's committed offset. A record of another order sits at offset 0, so a consumer that reset to the earliest offset or rewound would be given that record instead (probe (c)). The client's log has no `Resetting offset` line for the experiment's topic. The close: the first consumer's position was 2 before `close(CloseOptions.timeout(10 s))`, and the broker still answered 1 after it. kafka-clients 4.2.2's `ConsumerCoordinator.close` commits only through `maybeAutoCommitOffsetsSync`, which does nothing with `enable.auto.commit=false`, and the scene leaves no async commit pending.
- [x] **The real converter and the real listener method run, and production code is unchanged.** The shared `ByteArrayJacksonJsonMessageConverter` bean's `toMessage(record, acknowledgment, consumer, OrderEventMessage)` converts the record, as `MessagingMessageListenerAdapter` does, and the message's headers give the key, partition and offset the scene passes to the `OrderEventsListener` bean's `listen`. `git diff 5ab77c0 7874b75 -- apps/commerce-streamer/src/main` is empty.
- [x] **Step 2's test is renamed, and nothing quotes the old name.** It is now `the same event published twice leaves one set of rows and commits both offsets`, with a KDoc that calls it a republish. A grep finds the old name nowhere. Two paraphrases keep their text as the history they are: purchase activity design section 7's "같은 record를 두 번 넣으면" and step 2's writing material. Each carries a dated note naming the new test and `RedeliveryAfterCommitTest`.
- [x] **The experiment's topic and group no longer exist on the broker after the test.** `@AfterEach` closes both consumers (a second close does nothing) and then `ExperimentBroker.close()`. That deletes the group, which throws if a member is left, and the topic, and it waits until `describeTopics` no longer knows the topic. `ExperimentBrokerTest` shows both from outside.
- [x] **The red check and one run's values are in the closing comment, and the comment is appended to the writing material.** Here, and in the writing material's section for this issue.
- [x] **`./gradlew :apps:commerce-streamer:ktlintCheck :apps:commerce-streamer:check` passes, and the executed test counts are checked.** Run with `cleanTest` first: **16 classes, 63 tests, 0 failures, 0 errors, 0 skipped**, read by `scripts/test-counts --results apps/commerce-streamer/build/test-results/test`. The two new tests are `RedeliveryAfterCommitTest`'s scene and `ExperimentBrokerTest`'s reader test. `BOOTSTRAP_SERVERS` was unset. The pre-commit hook's tree-wide `ktlintCheck` passed.

### Red checks and probes

Each mutant was a local edit, run and reverted; none is committed. (a) and (b) ran again on the final tree just before the commit.

- **(a) A commit at the first consumer's close: the ticket's red check.** The first consumer was created with `enable.auto.commit=true`: the run's config dump shows `true` for `redelivery-first` and `false` for `redelivery-second`. So its real `close()` committed its position, 2, through `maybeAutoCommitOffsetsSync`. The scene went red at step 5: `ConditionTimeoutException: Condition with alias 'redelivery-second given a record' didn't complete within 10 seconds`. The second consumer missed n.
- **(b) A commit before the close.** The first `Acknowledgment` committed n + 1, as the container's `MANUAL_IMMEDIATE` would. Same failure: `redelivery-second given a record` timed out.
- **(c) A rewind.** The second consumer called `seekToBeginning` on its assignment and was given the other order's record: `Expecting actual: [(0, 1), (0, 0)] to contain exactly (and in same order): [(0, 1), (0, 1)]`. That is what the earlier record is for. It ran before the review's renames, on the same scene logic.
- **The reader's TDD red.** `Unresolved reference 'committedOffset'` until the helper had it. On this broker (`apache/kafka-native:4.3.2`), a group no consumer joined answers an empty map rather than `GroupIdNotFoundException`, so the reader returns null for it.
- **The first green run** had n = 0 on a 3-partition topic: both deliveries at offset 0, offsets 0 → 0 → 0 → 1. With n = 0 the earliest offset and the committed offset are the same number, and the first consumer reset partition 0 to the earliest offset before the test committed n. That run led to the earlier record, the commit on assignment and the single partition.

### Decisions made while implementing

- **A record of another order goes in first, so n = 1 and the earliest offset is not n.** The six steps did not ask for it. Without it, "the second consumer was given n" could equally be `auto.offset.reset=earliest`. With it, a reset or a rewind gives a different record, and the delivery assertion fails (probe (c)).
- **The first consumer commits n in `ConsumerRebalanceListener.onPartitionsAssigned`.** The client calls it on the polling thread as the join completes, before it fetches the committed offsets of the new partitions. So the first consumer reads from n because the group committed n, which is the step's "commits n, and reads from n" with no seek. Committing after the assignment poll returned would leave the consumer at the earliest offset.
- **The experiment's topic has one partition.** On a partition where the group has committed nothing, a consumer resets to the earliest offset. With one partition no consumer resets anywhere, and the partition half of "the same partition and offset" holds trivially.
- **The records each poll returns are kept until the scene takes one.** One `poll(100 ms)` can complete the join, fetch the committed offset and return the record, so the assignment wait must not drop what it fetched.
- **Every poll runs on the test thread (`pollInSameThread`).** Awaitility otherwise evaluates a condition on its own thread. A timed-out wait could leave that thread inside `poll` while `@AfterEach` closes the consumer, and the consumer's lock would throw `ConcurrentModificationException` over the real failure. The review applied the same to `ExperimentBrokerTest.joinAndLeave`.
- **The record reaches the listener through `toMessage` and its headers**, not through `record.key()`, `partition()` and `offset()`, so the arguments are the ones the adapter's `@Header` parameters would get.
- **The first `Acknowledgment` only notes the call, as `acknowledgeCalled`.** The listener did call `acknowledge()`, but the record was not acknowledged in the glossary's sense (읽었다고 표시하다): nothing reached the broker. The review renamed the local from `firstAcknowledged`.
- **The position is context, not evidence.** 2 before the close shows the close had a position past n to commit; the broker's committed offset is the evidence. The member IDs are the client's copy of what the broker assigned, logged only.
- **The reader takes a `TopicPartition` and reads any group**, as the ticket says. The scene's wrapper is `experimentCommittedOffset`, because the base's `committedOffset(partition)` reads the streamer's group on the class topic, and an overload would hide that.
- **The rows are compared as lines with IDs and `updated_at`**, so an update that rewrote the same values would still show.
- **The class names a topic of its own for the base** (`order-events-redelivery-after-commit`), which the base's container reads and waits on. The experiment's record never reaches it.
- **Dated notes, not rewrites,** where the design document and step 2's material paraphrase the old name: both record what was decided and run at the time.

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards:** no hard violation. Fixed: `ExperimentBrokerTest`'s joins now poll on the test thread, the rule this change writes into `CODING_STANDARDS.md`, and its two joins became one `joinAndLeave` that takes the commit; `BaseConsumerTest`'s KDoc and the standards' line on the base now say the experiment extends it without a container; the overload became `experimentCommittedOffset`; `firstAcknowledged` became `acknowledgeCalled`; the reader takes a `TopicPartition` and its KDoc lost a speculative sentence; `consumer.consumer` became `consumer.client`; `logTheScene` reads the field instead of a parameter that shadowed it. Kept: `taken` and `memberId` on each consumer (what each was given, which the line reports); the base's `KafkaTestUtils` reader beside the helper's admin reader (the base is not this ticket's code, and the helper opens on `KafkaAdmin` on purpose); the inheritance from `BaseConsumerTest` (documented, and the recording has to commit).
- **Spec:** nothing missing or wrong; the four additions above serve the spec. Fixed: the dated note in step 2's material now also covers the "redelivery" in that criterion's red check. Noted in the decisions above: the single partition makes the partition half trivial, and the member ID is the client's copy.

### Left for later

- **Merging with issue 186.** This branch and `worktree-issue-186` both start at `5ab77c0`. They meet in `ExperimentBroker`, `ExperimentBrokerTest`, the step 3 bullet of `CODING_STANDARDS.md` and the writing material, beside the group-description reader; keep both sides.
- **Issue 187's `BaseConsumerTest` helpers** (`storedConfirmationsLine`, `storedActivitiesLine`, `assertOnlyTheRowsOf`) are not in this base. Once they land, the scene's `storedRows()` and row checks can lean on them. 187 adds to the end of `BaseConsumerTest` and this change to its class KDoc, so the two do not overlap.
- **The design document** (issue 189) can take the facts above: the classic protocol is kafka-clients 4.2.2's default, a close commits only for auto-commit, and the experiment needs the earlier record and the commit on assignment.
- **The blob and commit links above** resolve once this branch is merged into `feat/kafka-delivery` and pushed.

## Issue 189: step 3's documents match the code, with the limits, the counterexamples, both week 7 cases, the commands and counts, and a draft PR body

Recorded 2026-10-29 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189#issuecomment-6492297157) of [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189). The decisions are spec 184's "문서" and "반례 셋" sections. The preface above, the four sections after this one and the dated notes are what it wrote.

Implemented on `feat/kafka-delivery` in [`33b851d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/33b851d), on top of `756f0b7`. Documents only; this is the last of [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184)'s tickets, and the spec stays open for its closing review. At `756f0b7`, `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-streamer:ktlintCheck :modules:kafka:ktlintCheck` passed; `./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:check`: **18 classes, 67 tests, 0 failures, 0 errors, 0 skipped**; `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:check`: **79 classes, 943 tests, 0 failures, 0 errors, 0 skipped**.

The documents are:

- the [Kafka delivery design](../design/kafka-delivery.md), new, in Korean
- the [week 6 Kafka delivery writing material](week6-kafka-delivery-writing-material.md): the preface, [one run of the four experiments](#one-run-of-the-four-experiments), [the counterexamples](#counterexamples-the-assignments-three-answered-by-a-test-or-a-red-check), [both week 7 cases](#w7-handoff-both-cases-side-by-side) and the [draft PR body](#draft-pr-body)
- the [order events design](../design/order-events.md): step 3's status in 1.1 and a dated note on section 10's 실험(3단계)
- [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184)'s body: eleven dated notes, each starting `2026-10-29 ([issue 189]`

### Landed

Two commits on `feat/kafka-delivery`, on top of `756f0b7`:

- [`33b851d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/33b851d) is the design, the order events notes and the writing material's new sections.
- The commit after it appends this comment to the writing material and adds this ticket's row to the preface's table.

### Acceptance criteria

- [x] **`docs/design/kafka-delivery.md` exists, matches the code, and has every limit listed.** It has seven sections:
  1. background and scope, with the per-ticket table in 1.2
  2. the four experiments, E1–E4, with what each asserts and the facts confirmed from source
  3. topics and groups, and `ExperimentBroker`'s methods
  4. the evidence rules
  5. config and limits
  6. the assignment's names against this repo's
  7. the commands and counts

  Section 5 has all eight limits:
  - `acks: all` on one broker is the leader alone
  - the endpoint `KafkaTestContainersConfig` injects, Ryuk at JVM exit, and E1–E3 deleting their own topics and groups
  - local's `localhost:19092` against compose's advertised `kafka:9092`, and the unused admin line
  - `fetch.min.bytes` and `fetch.max.wait.ms` at the client defaults, 1 byte and 500 ms (`ConsumerConfig` in kafka-clients 4.2.2)
  - the classic protocol with `[RangeAssignor, CooperativeStickyAssignor]`, eager rebalances and `range`
  - the DB → broker gap, linked to ADR 0032 and order events design 4 and 6, handed to week 7's outbox
  - controlled stops only, with `session.timeout.ms` 45 s by default for a killed consumer
  - the stitched consumed case

  A standards reviewer checked every code name against the code.
- [x] **The writing material has the preface, one run of all four experiments at one commit, the counterexample section with the cumulative table, both week 7 cases with the representative rows, and the draft PR body.**
  - **The run** is the streamer's half of the final check at `756f0b7`, started 2026-10-29T08:31:50Z. Its INFO lines were copied from the JUnit XML reports' `system-out`:
    - E1's three tables: `member-1` takes [0, 1], then [0] with `member-2` on [1], then `member-3` on nothing. The group was Stable / Classic / range at every size.
    - E2's key → partition table, the same as issue 185's run.
    - E3's committed offsets: 1 → 1 → 1 → 2.
    - E4's offsets (1 → 1 → 2), the stop 3319 ms after publish, and the rows during the stop and after the restart.
  - **The counterexamples** are C1 to E3, C2 to E4's red check and C3 to step 2's conflict tests. Each comes with its guard, its test and its red check, and a 14-row table covers the three steps.
  - **The two week 7 cases** come from their recorded runs (`e492cf1`, `b054b4f`), with the stitch and the consumed case's three rows as the representative order.
  - **The PR body** has Summary, Evidence and Merge Danger.
- [x] **Dated notes are on order events design 1.1 and 10 and on spec 184.**
  - In order events design 1.1, row 3's status cell now reads 인터뷰 끝남 / 구현 끝남. It was replaced in place, as rows 1 and 2 were.
  - Section 10 has a dated note.
  - Spec 184 has eleven notes:
    - the glossary commit `9554a35`
    - the two documents
    - the four names kept final, plus `ExperimentBroker`
    - the INFO line headings
    - kafka-clients 4.2.2 and its defaults
    - the redelivery scene's three additions (an earlier record so n = 1, the commit in `onPartitionsAssigned`, one partition)
    - `CannotAcquireLockException`
    - where the red checks are recorded
    - the fifth place for notes (order events design 10)
    - the final counts
    - E4 on the class topic rather than `ExperimentBroker`
- [x] **The ktlint checks and both apps' `check` pass.** The design records the executed test counts. Nothing was zero, skipped or unable to reach the broker.
  - The runs were at `756f0b7` with `BOOTSTRAP_SERVERS` unset. The streamer took 56 s and commerce-api 3 min 21 s.
  - Each filter in design section 7 was then run on its own: 1, 1, 1, 1 and 7 tests, all passing.
  - Only documents changed after `756f0b7`, and the pre-commit hook's tree-wide `ktlintCheck` passed.

### Decisions made while implementing

- **One run of all four is the final check's streamer run.** All four ran in one JVM against one broker. Their lines are in that run's JUnit XML, so the counts and the recorded lines come from the same execution. Each ticket's section keeps its own run at its own commit.
- **The consumed case was not rerun on purpose.** The whole check ran it as one of the 67 tests, and its line held step 2's values, as its fixed inputs make it. The writing material still cites step 2's run (`b054b4f`) and says this.
- **E4 does not own its broker state.** Spec 184's 공통 규칙 says every experiment deletes its own topic and group. Its 실제 리스너의 실패 section puts E4 on the base's class topic and the registry's container, and the code follows that section. The design (sections 3 and 5), the preface and the PR body say E1–E3, and a dated note on the common rule records it.
- **The cumulative table cites recorded red checks only.** No new mutant was run for this ticket. Every row's red check is quoted from a closing comment of steps 1–3 or from spec 176's review.
- **The ticket names order events design section 4 for the gap.** Section 4 is the mechanism and section 6 is 누락 구간, so the design links both.
- **The design and the writing material landed in one commit.** They link each other's headings, and the pre-commit link check reads the index, so neither could land alone.
- **The PR title is `feat(commerce-streamer): …`.** The listener's failure line is a production change, however small.

### Review

A two-axis review ran on the uncommitted tree before the commit.

- **Standards:** no hard violation; both doc checks passed. Fixed:
  - Design section 3 said `close()` deletes groups one by one. It sends one `deleteConsumerGroups` and reads each result.
  - The preface's version list had dropped liberica and typesafe-conventions.
  - E3's offsets were written n → n → n+1 in some places and as four reads in others. The design now says the assignment's three reads are before the first read, after the first close and after the second read, and that the scene reads once more.
  - Design section 4's red-check table repeated the writing material's. It is now a pointer.
  - Order events 1.1 row 3 kept a stale "다음 인터뷰" ahead of the note. The cell is now replaced in place, as the precedent rows are.
  - The counterexamples' provenance line now names its sources.

  Kept:
  - the counterexample table, the cumulative table and the PR body's red lines, which the ticket asks for separately, and the PR body has to stand alone
  - "offset을 커밋한다", Kafka's own term for the offset, not the 읽었다고 표시하다 the glossary keeps apart
  - "이음매 C = 2단계의 이음매 2", spec 184's own labels
- **Spec:** nothing missing but this comment. Fixed:
  - E4's cleanup was overclaimed in three places, as above.
  - "파티션 1" read as partition 1 where it meant one partition, in the design and in spec 184's note.

  Refuted: the reviewer read the ktlint command as lacking `:modules:kafka:ktlintCheck` from zsh's truncated `time` line. The log's task list shows `:modules:kafka:ktlintCheck` ran.

### Left for later

- **Push `feat/kafka-delivery`.** The PR body's `blob/feat/kafka-delivery` links and this comment's links resolve once `33b851d` and the commit after it are on the remote. The user opens the PR into `week6`.
- **Spec 184's closing review**, as steps 1 and 2 had.
- **After the merge into `week6`:** the week 6 blog (`docs/blog/week6.md`), then the upstream `[volume-6]` PR.
- **Carried over from step 2:** the race retry `recordSettlingARace` still has no permanent scene (issue 180), and its second violation gets the failure line by construction only (issue 187).

## One run of the four experiments

Recorded 2026-10-29 by [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189) from one run of all four experiments at one commit. Each ticket's section above holds its own run at its own commit. This run puts the four side by side, at the commit where all four had landed.

### How it ran

- **Commit:** [`756f0b7`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/756f0b7) on `feat/kafka-delivery`, the merge that brought the last of 185–188 in
- **Command:** `./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:check`, the streamer's half of the step's final check ([design](../design/kafka-delivery.md) §7)
- **Run:** started 2026-10-29T08:31:50Z, 56 s. 18 classes, 67 tests, 0 failures, 0 errors and 0 skipped, read by `scripts/test-counts --results apps/commerce-streamer/build/test-results/test`. `BOOTSTRAP_SERVERS` was unset.
- **Order and time:** E1 at 08:32:08Z (8.4 s), E2 at 08:32:17Z (0.5 s), E4 at 08:32:22Z (5.2 s) and E3 at 08:32:38Z (3.0 s), in one JVM against one broker. E1 and E2 share the rollback base's cached context. E3 and E4 each start their own, for their class topics.
- **Containers:** MySQL 8.4 and `apache/kafka-native:4.3.2` from the Testcontainers fixtures, one per JVM, removed by Ryuk when the JVM ends
- **The lines** are copied as logged, after each scene's assertions, from the `system-out` of the run's JUnit XML reports. The event and product IDs in E3 and E4 are drawn by the fixtures, so they differ between runs.

### E1: group assignment

```
E1 group assignment, 1 of 3 members
topic=consumer-group-assignment-fb3fbfe8, partitions=2, group=consumer-group-assignment-03c09edf
state=Stable, type=Classic, assignor=range
member-1: partitions=[0, 1]
probe partition=0, offset=0: read by [member-1]
probe partition=1, offset=0: read by [member-1]

E1 group assignment, 2 of 3 members
topic=consumer-group-assignment-fb3fbfe8, partitions=2, group=consumer-group-assignment-03c09edf
state=Stable, type=Classic, assignor=range
member-1: partitions=[0]
member-2: partitions=[1]
probe partition=0, offset=1: read by [member-1]
probe partition=1, offset=1: read by [member-2]

E1 group assignment, 3 of 3 members
topic=consumer-group-assignment-fb3fbfe8, partitions=2, group=consumer-group-assignment-03c09edf
state=Stable, type=Classic, assignor=range
member-1: partitions=[0]
member-2: partitions=[1]
member-3: partitions=[]
probe partition=0, offset=2: read by [member-1]
probe partition=1, offset=2: read by [member-2]
```

| Members | `member-1` | `member-2` | `member-3` | Probe on partition 0 read by | Probe on partition 1 read by |
| --- | --- | --- | --- | --- | --- |
| 1 | 0, 1 | | | `member-1` (offset 0) | `member-1` (offset 0) |
| 2 | 0 | 1 | | `member-1` (offset 1) | `member-2` (offset 1) |
| 3 | 0 | 1 | none | `member-1` (offset 2) | `member-2` (offset 2) |

At every size the broker described the group as `Stable`, of type `Classic`, with assignor `range`. The table is the same as issue 186's run at `38f20d3`: range deals partitions by member ID, and the member ID starts with the client ID.

### E2: key → partition

```
E2 key → partition
topic=key-partitioning-10b8706c, partitions=3, sends per key=3
key=1: partition=0, offsets=[0, 1, 2]
key=2: partition=2, offsets=[0, 1, 2]
key=42: partition=1, offsets=[0, 1, 2]
key=1000: partition=1, offsets=[3, 4, 5]
key=123456789: partition=0, offsets=[3, 4, 5]
```

| Key | Partition | Offsets of its three sends |
| --- | --- | --- |
| `1` | 0 | 0, 1, 2 |
| `2` | 2 | 0, 1, 2 |
| `42` | 1 | 0, 1, 2 |
| `1000` | 1 | 3, 4, 5 |
| `123456789` | 0 | 3, 4, 5 |

The table matches issue 185's run at `d7ca27e` key for key, as it must: the partition is murmur2 of the key's bytes modulo the partition count. Two pairs of keys share a partition, which is why nothing asserts a spread.

### E3: redelivery after commit

```
E3 redelivery after commit
topic=redelivery-after-commit-1452a17f, partitions=1, group=redelivery-after-commit-55f87357
earlier record of another order: partition=0, offset=0
the event's record: partition=0, offset=1
redelivery-first (member redelivery-first-0b3f06c3-d043-422c-8fe3-c7173e8f96e1): given partition=0, offset=1
redelivery-second (member redelivery-second-7ad578aa-f124-44a9-b23d-ca5cb9d1d196): given partition=0, offset=1
position of redelivery-first before its close=2
committed offset of the group on partition 0: before the first read=1, after the first read=1, after the first close=1, after the second read=2
listener: Recorded the order confirmed event. eventId=order-confirmed:v1:6190984331424076010, orderId=6190984331424076010, partition=0, offset=1, result=RECORDED
listener: Recorded the order confirmed event. eventId=order-confirmed:v1:6190984331424076010, orderId=6190984331424076010, partition=0, offset=1, result=ALREADY_RECORDED
processed_order_confirmation id=1, event_id=order-confirmed:v1:6190984331424076010, order_id=6190984331424076010, confirmed_at=1993-05-21T07:06:09.176780Z, updated_at=2026-10-29T08:32:40.368568Z
purchase_activity id=1, order_id=6190984331424076010, product_id=936314317140507397, quantity=5, confirmed_at=1993-05-21T07:06:09.176780Z, updated_at=2026-10-29T08:32:40.371965Z
purchase_activity id=2, order_id=6190984331424076010, product_id=1408337261201094095, quantity=9, confirmed_at=1993-05-21T07:06:09.176780Z, updated_at=2026-10-29T08:32:40.373845Z
purchase_activity id=3, order_id=6190984331424076010, product_id=314668332910751450, quantity=3, confirmed_at=1993-05-21T07:06:09.176780Z, updated_at=2026-10-29T08:32:40.375885Z
```

n = 1. The record of another order sits at offset 0, so a reset or a rewind would have handed that one over.

| Step | Group's committed offset on partition 0, from the broker | What happened |
| --- | --- | --- |
| Before the first read | 1 (n) | `redelivery-first` committed n as it was assigned the partition |
| After the first read | 1 (n) | given (0, 1), `RECORDED` at 08:32:40.382Z, the `Acknowledgment` only noted the call |
| After the first close | 1 (n) | the consumer's own position was 2 before `close`, and the close committed nothing |
| After the second read | 2 (n+1) | `redelivery-second` given (0, 1) again, `ALREADY_RECORDED` at 08:32:41.023Z, and the `Acknowledgment` committed n+1 |

The four rows above, IDs and `updated_at` included, were read after the first delivery and again at the end, and the two reads were equal.

### E4: real listener failure

```
Real listener failure, a lock wait timeout after a partial write
topic=order-events-listener-failure, partition=0, normal record offset=0, failing record offset=1
committed offset of commerce-streamer on partition 0: before=1, during the stop=1, after the restart=2
the container had stopped 3319 ms after the failing record was published (innodb_lock_wait_timeout=3)
failure line: Could not record an order confirmed event; the consumer stops until it is restarted. eventId=order-confirmed:v1:6528876687823705029, orderId=6528876687823705029, partition=0, offset=1, cause=org.springframework.dao.CannotAcquireLockException: could not execute statement [Lock wait timeout exceeded; try restarting transaction] [insert into purchase_activity (confirmed_at,created_at,deleted_at,order_id,product_id,quantity,updated_at) values (?,?,?,?,?,?,?)]; SQL [insert into purchase_activity (confirmed_at,created_at,deleted_at,order_id,product_id,quantity,updated_at) values (?,?,?,?,?,?,?)]
rows during the stop
processed_order_confirmation: id=1, event_id=order-confirmed:v1:2384763225657083949, order_id=2384763225657083949, confirmed_at=1978-04-29T12:23:01.339574Z
purchase_activity: id=1, order_id=2384763225657083949, product_id=3034232887047408800, quantity=1, confirmed_at=1978-04-29T12:23:01.339574Z; id=2, order_id=2384763225657083949, product_id=1592607632369364806, quantity=1, confirmed_at=1978-04-29T12:23:01.339574Z; id=3, order_id=2384763225657083949, product_id=1111402851457735210, quantity=3, confirmed_at=1978-04-29T12:23:01.339574Z
rows after the restart
processed_order_confirmation: id=1, event_id=order-confirmed:v1:2384763225657083949, order_id=2384763225657083949, confirmed_at=1978-04-29T12:23:01.339574Z; id=3, event_id=order-confirmed:v1:6528876687823705029, order_id=6528876687823705029, confirmed_at=2002-03-29T12:05:45.912758Z
purchase_activity: id=1, order_id=2384763225657083949, product_id=3034232887047408800, quantity=1, confirmed_at=1978-04-29T12:23:01.339574Z; id=2, order_id=2384763225657083949, product_id=1592607632369364806, quantity=1, confirmed_at=1978-04-29T12:23:01.339574Z; id=3, order_id=2384763225657083949, product_id=1111402851457735210, quantity=3, confirmed_at=1978-04-29T12:23:01.339574Z; id=7, order_id=6528876687823705029, product_id=1, quantity=2, confirmed_at=2002-03-29T12:05:45.912758Z; id=8, order_id=6528876687823705029, product_id=2, quantity=1, confirmed_at=2002-03-29T12:05:45.912758Z
```

| Moment | Committed offset of `commerce-streamer` on partition 0 | Rows of the failing order |
| --- | --- | --- |
| Before: the normal record (offset 0) recorded | 1 (n) | none yet |
| During the stop | 1 (n) | none in either table |
| After the restart | 2 (n+1) | confirmation id 3, activities ids 7 and 8 |

The log lines of the scene, in order:

| Time (UTC) | Thread | Line |
| --- | --- | --- |
| 08:32:24.199 | `er-events-0-C-1` | `Recorded … partition=0, offset=0, result=RECORDED`, the normal event |
| 08:32:27.357 | `er-events-0-C-1` | the failure line above, `partition=0, offset=1` |
| 08:32:27.466 | `er-events-0-C-1` | spring-kafka's `Error handler threw an exception`, caused by `KafkaException: Stopped container` |
| 08:32:27.700 | `er-events-0-C-2` | `Recorded … partition=0, offset=1, result=RECORDED`, read by a consumer of the restarted container |

The id gaps are the partial write's trace, as in issue 187's run:

- The confirmations go 1, 3. Id 2 went to the confirmation the listener inserted and rolled back.
- The activities go 1–3 (the normal event's three items), then 7, 8. Id 4 went to the row the blocking connection held, 5 to the first item the listener inserted and rolled back, and 6 to the second item's insert, which took its value before it waited and failed.

InnoDB does not give auto-increment values back on rollback.

## Counterexamples: the assignment's three, answered by a test or a red check

Recorded 2026-10-29 by [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189) from the red checks the closing comments of steps 1–3 record: issues 170–172 in [step 1's material](week6-order-events-writing-material.md), issues 177–181 and spec 176's review in [step 2's](week6-purchase-activity-writing-material.md), and issues 185–188 above. The assignment's AI review gives three counterexamples to try against the consumer. Spec 184's "반례 셋" assigns each one to a test. Each is answered by a test that shows the guard, and by a red check, a mutant of the guard that turned the test red. Every red check was a local edit, run and reverted. None is committed.

| Counterexample | What would go wrong | The guard | The test | The red check |
| --- | --- | --- | --- | --- |
| **C1. DB 저장 뒤 commit 전에 멈춤.** The consumer stops after the recording commits and before the offset is committed. | The record comes again. An offset committed before the recording would lose it, and recording it again could write a second set of rows. | The listener acknowledges only after the recorder has returned, and `MANUAL_IMMEDIATE` commits there (ADR 0033 rule 6). The recorder reads first, so the same fact writes nothing (rule 3). | E3, `RedeliveryAfterCommitTest`: given (0, 1) twice, `RECORDED` then `ALREADY_RECORDED`, rows unchanged, committed offset 1 → 1 → 1 → 2 ([one run](#e3-redelivery-after-commit)) | Two mutants made the first consumer's offset move past n: auto-commit on, so that its `close` committed 2, and an `Acknowledgment` that committed n+1. Both turned the scene red at step 5: `redelivery-second given a record` timed out after 10 s, so the second consumer never got n. A `seekToBeginning` on the second consumer was handed the other order's record, `(0, 0)` (issue 188). In step 2, with no read by event ID, recording the same request again failed with `DataIntegrityViolationException` on `UK_PROCESSED_ORDER_CONFIRMATION_ON_EVENT_ID` (issue 179). |
| **C2. handler가 예외를 삼킴.** The handler catches the failure and acknowledges anyway. | The record is marked read with nothing written. The event is lost without a trace, and the partition moves on. | The listener logs its line and rethrows every exception. The stopping error handler stops all three consumers with the offset uncommitted (ADR 0033 rule 5). | E4, `OrderEventsListenerFailureTest`: offset 1 during the stop, container stopped abnormally, no rows for the order, one failure line, recorded after the restart ([one run](#e4-real-listener-failure)) | The third catch arm's `throw e` was replaced by `acknowledgment.acknowledge(); return`. The scene went red in `awaitStopped` after 10 s. The log had the failure line once, one `RECORDED` (the normal event's) and no `Stopped container`, so the record was skipped with no rows and the container went on (issue 187). |
| **C3. 같은 ID로 다른 수량 수신.** The same event ID arrives with another quantity. | The stored fact is overwritten, or the record is silently marked read, and a producer bug goes unseen. | The recorder compares the stored fact with the incoming one: the order, the time cut to microseconds, and the product → quantity map. Any difference throws `PurchaseActivityConflictException`. The listener refuses with one ERROR line, and the container stops. | Step 2: `PurchaseActivityRecorderTest`'s `the same event id with another quantity conflicts and changes no row`, and `OrderEventsListenerStopTest`'s `a record conflicting with the recorded one stops the container, commits no offset and leaves the recorded rows`, with its restart scene | Each conflict test failed with "nothing was thrown" until its comparison existed (issue 179). The conflict scene failed only on the ERROR line before the refusal line existed, `Expecting actual: []`; the stop, the uncommitted offset and the rows already held (issue 181). |

C1 and C2 prove different things. C1 stops after the recording committed: the same fact comes again and writes nothing. C2 fails before the recording commits: nothing partial stays, and the offset does not move. A unit test that calls the handler twice shows neither, since neither the broker nor the commit is in it.

### The three steps' verification, cumulative

What week 6 shows, step by step, with the red check that proves each test would catch the guard's absence. Each step's tests still run in its app's `check`. Step 1's are in commerce-api, steps 2 and 3's in commerce-streamer.

| # | Step | What is shown | Test | Red check |
| --- | --- | --- | --- | --- |
| 1 | 1 | A first PG confirmation publishes one record under the order's key, only after TX B commits. A rolled-back TX B publishes nothing. | `OrderEventPublishingTest` | `@EventListener` in place of the AFTER_COMMIT listener: the rolled-back TX B scene found a record (issue 171) |
| 2 | 1 | A pending, failed or rejected payment publishes nothing. | `OrderEventPublishingTest` | publish on any `Changed`: those scenes and the first-confirming ones failed (issue 170) |
| 3 | 1 | A duplicate callback, a racing duplicate or a late create answer publishes no second record. | `OrderEventPublishingTest` | publish whenever the saved order is CONFIRMED: the late create answer and the racing duplicate failed (issue 170) |
| 4 | 1 | The relay publishes off the confirming thread. | `OrderEventPublishingTest` | `@Async` removed: published from `Test worker` (issue 171) |
| 5 | 1 | A publish that fails or stops after TX B commits leaves the order confirmed and no record: the publish gap | `OrderEventPublishGapTest` | the relay's `catch` narrowed so the exception leaves it: no WARN within 10 s (issue 172) |
| 6 | 2 | The same event published twice leaves one set of rows and commits both offsets: a republish. | `OrderEventsListenerTest`, `PurchaseActivityRecorderTest` | no read by event ID: `DataIntegrityViolationException` on the event ID's unique key (issue 179) |
| 7 | 2 | The same event ID with another quantity, product set, time or order is a conflict, and the stored fact stands. (C3) | `PurchaseActivityRecorderTest` | each failed with "nothing was thrown" until its comparison existed (issue 179) |
| 8 | 2 | A conflict, a payload without `orderId`, schema version 2, empty items and a value that is not JSON stop the consumer with the offset uncommitted. (C3) | `OrderEventsListenerStopTest` | conflict scene: an empty ERROR list before the refusal line (issue 181); no `ConstraintViolationException` catch: the empty-items scene logged no line (issue 181); `MessageConversionException` asserted for the not-JSON chain: failed, it is `ConversionException` (spec 176's review) |
| 9 | 2 | An unknown event type is skipped: acknowledged with no row and one INFO line. | `OrderEventsListenerTest` | without the skip, an `OrderCancelled` record stopped the container (issue 180) |
| 10 | 2 | Auto-commit is off in the listener factory's consumer configuration. | `OrderEventsListenerConfigTest` | before the factory set it, the configuration held only the misspelt `enable-auto-commit` (issue 177) |
| 11 | 3 | E1: at every size each partition has exactly one member, and at three a member has none. The probes are read by their partition's member. | `ConsumerGroupAssignmentTest` | member 3 in another group: the wait for three timed out after 30 s; probes not acknowledged: `member-1` read size 1's probe three times (issue 186) |
| 12 | 3 | E2: every send of a key lands on one partition. | `KeyPartitioningTest` | key 42's third send forced to another partition: `[1, 1, 2]` (issue 185) |
| 13 | 3 | E3: a record recorded but not acknowledged is redelivered at its offset and writes nothing, and the committed offsets read n → n → n → n+1. (C1) | `RedeliveryAfterCommitTest` | a close that commits, or an acknowledgment of n+1: the second consumer is never given n; a rewind: given `(0, 0)` (issue 188) |
| 14 | 3 | E4: a transient failure after a partial write rolls everything back, stops the consumer with the offset unmoved and a failure line, and a restart records the record. (C2) | `OrderEventsListenerFailureTest` | swallow and acknowledge: `awaitStopped` timed out; no blocking connection: the same; before the production change, an empty ERROR list (issue 187) |

Rows 6 and 13 are the two ways an event comes again. Row 6 is a republish, a new record of the same event at the next offset. Row 13 is a redelivery, the same record at the same offset, which the streamer's `CONTEXT.md` now names apart (다시 발행하다, 다시 전달되다).

## W7 handoff: both cases side by side

Recorded 2026-10-29 by [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189) from the two recorded runs, not rerun. Week 6 hands two cases to week 7's outbox. Step 1 recorded the unpublished case, in [step 1's material](week6-order-events-writing-material.md#w7-handoff-unpublished-case). Step 2 recorded the consumed case, in [step 2's material](week6-purchase-activity-writing-material.md#w7-handoff-consumed-case). Both are the same order confirmed at the same instant.

| | Unpublished case | Consumed case |
| --- | --- | --- |
| Test | `OrderEventPublishGapTest`, commerce-api | `OrderEventConsumedCaseTest`, commerce-streamer |
| Commit, run | [`e492cf1`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/e492cf1), 2026-10-27T11:15:22Z | [`b054b4f`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/b054b4f), 2026-10-28T13:09:29Z |
| Command | `./gradlew :apps:commerce-api:test --tests 'com.loopers.application.order.provided.OrderEventPublishGapTest'` | `./gradlew :apps:commerce-streamer:test --tests 'com.loopers.adapter.consumer.OrderEventConsumedCaseTest'` |
| orderId | 1 | 1 |
| eventId | `order-confirmed:v1:1`, never published | `order-confirmed:v1:1` |
| confirmedAt | `2026-10-27T01:02:03.123456Z`, the pinned `TestClock` | `2026-10-27T01:02:03.123456Z`, in the hand-written record |
| items | `[{productId 1, quantity 2}, {productId 2, quantity 1}]` | the same |
| PG order ID | `2d50c75c-dc9f-4bf3-97c4-fd5186da94ce` (the publish failed), `7c92525b-8893-408e-9f7c-f348acc2efa8` (the publish stopped): a fresh UUID per payment | none: the streamer knows no PG value. Stitched from the unpublished case |
| Transaction key | `20261027:TR:9e3b71` | none. Stitched as above |
| Order | `CONFIRMED` at the pinned instant, read in a fresh transaction | not run: no commerce-api in this case |
| Payment | `SUCCESS` under the transaction key | not run |
| Stock | product 1: 3, product 2: 4 (reserved from 5, not released) | not run |
| Broker | 0 records on `order-events-publish-gap` | 1 record on `order-events-consumed-case`, partition 0, offset 0. Group `commerce-streamer` committed 1 |
| Log | relay WARN: `Could not publish the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, cause=org.springframework.kafka.KafkaException: Send failed` | listener INFO: `Recorded the order confirmed event. eventId=order-confirmed:v1:1, orderId=1, partition=0, offset=0, result=RECORDED` |
| Streamer's tables | nothing to read | one processed confirmation and two activities, below |

### The representative order's rows

The consumed case's recorded rows are the representative order the assignment asks the PR to show. That is order 1, two products, at its confirmation time to the microsecond.

| Table | id | Values |
| --- | --- | --- |
| `processed_order_confirmation` | 1 | event_id `order-confirmed:v1:1`, order_id 1, confirmed_at `2026-10-27T01:02:03.123456Z` |
| `purchase_activity` | 1 | order_id 1, product_id 1, quantity 2, confirmed_at `2026-10-27T01:02:03.123456Z` |
| `purchase_activity` | 2 | order_id 1, product_id 2, quantity 1, confirmed_at `2026-10-27T01:02:03.123456Z` |

This step's whole check ran the consumed case too, at `756f0b7`, as one of the streamer's 67 tests. Its line held the same values, as its fixed inputs make it. The record above stays step 2's run.

### The stitch

No single run spans both apps. The repo has no PG simulator: commerce-api's `pg.base-url` comes from `PG_BASE_URL`, and the tests' PG is WireMock inside commerce-api's JVM. So the two cases are two runs, one per app. They are joined by the order ID, the event ID, the confirmation instant and the items. They are also joined by the record's bytes: commerce-api's `OrderEventPublishingTest` publishes the same order at the same instant and pins the envelope the consumed case writes by hand. The commerce-side values of the consumed case (the PG order IDs, the transaction key, the order, payment and stock) are the unpublished case's. Both scenes of that run confirm order 1 at the same instant, so either one's event is this record. The [design](../design/kafka-delivery.md) §5 lists this among the limits.

### What week 7 starts from

In the first case the order is confirmed, and its event exists nowhere but in one WARN line. In the second, the same event, once on the broker, becomes one confirmation and two activities, and the group commits past it. An outbox row written inside TX B carries the first case to the second. ADR 0032's 다시 볼 조건 plans a `BEFORE_COMMIT` listener on the same Spring event, which leaves the saga step and the port unchanged. Step 3 did not narrow that gap. It showed what the consumer does once a record is on the broker.

## Draft PR body

Drafted 2026-10-29 by [issue 189](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/189), in the shape of [PR 174](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/174) and [PR 183](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/pull/183) (Summary / Evidence / Merge Danger). The user opens the PR in the UI.

- **Base:** `week6` ← **head:** `feat/kafka-delivery`
- **Title:** `feat(commerce-streamer): show Kafka delivery in four experiments on the test broker, and log a failure line when the listener cannot record`
- The `blob/feat/kafka-delivery` links resolve once the branch is pushed with this commit.

````markdown
## Summary

This is week 6 step 3, the delivery experiments. Step 2's tests showed that a record becomes rows, that the offset is committed after the recording, and that an unprocessable event stops the consumer. Four experiments on the test broker now show what they did not: how a group deals partitions as members join, where a key's records land, a redelivery after the recording committed, and a real failure after a partial write. The one production change is a failure line in the listener.

- **Spec:** https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184, with tickets 185–189, closed after a two-axis review ([closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184#issuecomment-6496099667)). No new ADR: nothing here is hard to reverse.
- **Design:** [`docs/design/kafka-delivery.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/kafka-delivery/docs/design/kafka-delivery.md). §2 covers the experiments, §4 the evidence rules, §5 the config and the limits, and §7 the commands and counts.
- **Glossary:** the streamer's [`CONTEXT.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/kafka-delivery/apps/commerce-streamer/CONTEXT.md) separates 다시 발행하다 (republish: a new record of the same event) from 다시 전달되다 (redeliver: the same record at the same offset), and widens 멈추다 to every failure to record.
- **Writing material:** [`docs/research/week6-kafka-delivery-writing-material.md`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/blob/feat/kafka-delivery/docs/research/week6-kafka-delivery-writing-material.md). It holds one run of all four experiments, the assignment's three counterexamples with the three steps' cumulative verification table, and both week 7 cases side by side.

```text
E1 ConsumerGroupAssignmentTest     2 partitions, members join 1 → 2 → 3    broker's group description: Stable, member → partitions
E2 KeyPartitioningTest             3 partitions, 5 keys × 3 sends          SendResult metadata: one partition per key
E3 RedeliveryAfterCommitTest       1 partition, two plain consumers        same (partition, offset) twice; committed n → n → n → n+1
E4 OrderEventsListenerFailureTest  the class topic, the real container     MySQL 1205 after a partial write → rollback, stop, restart
```

```text
OrderEventsListener.listen
  record → INFO "Recorded… result=…", acknowledge()
  catch UnprocessableOrderEventException | ConstraintViolationException → ERROR "Refused…", rethrow   # a restart refuses again
  catch Exception → ERROR "Could not record…; the consumer stops until it is restarted.", rethrow     # new: a restart records once the cause is gone
```

- **E1–E3 own their broker state.** `ExperimentBroker` creates each one's topic and names its group under a random suffix, and deletes both after the test, so their records reach no listener's topic. E4 runs the real container on its class topic, unique to the class as in step 2, and leaves that topic and group `commerce-streamer`'s offsets behind. No experiment record reaches `order-events`. The broker address is only the one the Testcontainers fixture injects.
- **Evidence is what the broker and the DB say.** That means the broker's group description, committed offsets read from the broker, send metadata, the two tables' rows and the listener's lines. Consumer objects, poll positions and container fields are never counted. Every wait is bounded.
- **E3 runs no container.** Plain consumers from the listener factory's consumer factory poll, close and commit under the test's control. The shared converter and the real `OrderEventsListener.listen` record what they read. A record of another order goes first, so a reset or rewind would show.
- **E4 injects a real failure.** A second JDBC connection holds an uncommitted `purchase_activity` row for the event's second item, so the listener's transaction waits out `innodb_lock_wait_timeout=3`. Spring translates MySQL 1205 into `CannotAcquireLockException`, which `recordSettlingARace` does not retry.
- **Step 2's republish scene is renamed** `the same event published twice leaves one set of rows and commits both offsets`.
- **Also on this branch:**
  - `9554a35`: the glossary, before the tickets
  - `1618ee7`, `5ba74ca`, `2daf469`, `0be006d`: JDK 25.0.5, Boot 4.1.2 with Jetty EE10 12.1.14, Gradle 9.8.2 with typesafe-conventions 0.12.1, springdoc 3.2.0
  - `3aa0b1d`, `98cd1b9`, `33c58da`, `5bfe295`: the spec's closing review. Steps 1 and 2's ADRs and designs take the glossary's new words, each experiment gets its own standards bullet, E3 closes its broker even when a consumer's close throws and checks its rows with the base's helper, and E4's evidence for the partial write is worded as what it shows
  - `414e300`: the test broker `apache/kafka-native` 4.3.2 → 4.4.0

## Evidence

The values are from one run of all four experiments at `756f0b7` (2026-10-29), on `apache/kafka-native:4.3.2`. The rerun at `414e300` on 4.4.0 printed the same E1–E3 values, and E4 read 1 → 1 → 2 and stopped after 3306 ms. The mutants were run at ticket time and reverted ("Red checks" of issues 185–188 in the writing material).

- **E1, group assignment.** At each size the broker described the group as `Stable`, `Classic`, `range`:

  | Members | member-1 | member-2 | member-3 | probes p0 / p1 read by |
  | --- | --- | --- | --- | --- |
  | 1 | 0, 1 | | | member-1 / member-1 |
  | 2 | 0 | 1 | | member-1 / member-2 |
  | 3 | 0 | 1 | none | member-1 / member-2 |

  **Red:** a third member started in another group never joined, and the wait for three timed out after 30 s. With the probe listener not acknowledging, each eager rebalance put `member-1` back at the earliest offset, and it read size 1's probe three times.
- **E2, key → partition.** Keys `1` → 0, `2` → 2, `42` → 1, `1000` → 1, `123456789` → 0, three sends each, all on one partition per key. Two pairs share a partition, so nothing asserts a spread. The default partitioner is `murmur2(key) % partitions`, so this holds only while the partition count stays the same.
  **Red:** key 42's third send forced elsewhere read `[1, 1, 2]`.
- **E3, redelivery after commit.** n = 1. Both consumers were given partition 0, offset 1. The broker's committed offset read 1 before the first read, 1 after it, 1 after the first close (whose position was 2), and 2 after the second read. The listener logged `result=RECORDED` and then `result=ALREADY_RECORDED` at the same partition and offset. Both tables, IDs and `updated_at` included, were unchanged by the second delivery.
  **Red:** a first consumer whose close commits (auto-commit on), or an acknowledgment that commits n+1, and the second consumer is never given n. A rewind hands over the other order's record `(0, 0)`.
- **E4, a real listener failure.** The container stopped 3319 ms after the failing record was published. During the stop the failing order had no row in either table, and the committed offset stayed at 1. The listener logged one line:
  ```text
  Could not record an order confirmed event; the consumer stops until it is restarted. eventId=order-confirmed:v1:6528876687823705029, orderId=6528876687823705029, partition=0, offset=1, cause=org.springframework.dao.CannotAcquireLockException: could not execute statement [Lock wait timeout exceeded; try restarting transaction] [insert into purchase_activity (…) values (…)]; SQL […]
  ```
  The failed statement is an activity insert, so the confirmation had been written and rolled back with it. The run's id gaps show the first item was written too; the scene asserts neither, since the message names no key. After the blocking connection rolled back and the container restarted, the same record was recorded and the committed offset read 2.
  **Before:** before the failure line existed, the scene failed only on the empty ERROR list. **Red:** a listener that swallows the failure and acknowledges it skips the record with no rows, and the container never stops.
- **The assignment's counterexamples.** C1, a stop after the recording commits and before the offset commit, is E3. C2, a handler that swallows the exception, is E4's red check. C3, the same ID with another quantity, is step 2's conflict tests. The writing material's "Counterexamples" section has each with its red check, and a 14-row table of what the three steps show.
- **The representative order's rows** are the consumed case's, recorded at step 2 (`b054b4f`). Order 1 has one processed confirmation (`order-confirmed:v1:1`) and two activities: product 1 × 2 and product 2 × 1, all at `2026-10-27T01:02:03.123456Z`. The writing material sets that case beside the unpublished case of step 1 (`e492cf1`) and states the stitch between the two runs.
- **Whole check.** At `756f0b7`, `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-streamer:ktlintCheck :modules:kafka:ktlintCheck` passes. `./gradlew :apps:commerce-streamer:cleanTest :apps:commerce-streamer:check` passes in 56 s, and `scripts/test-counts --results apps/commerce-streamer/build/test-results/test` reads 18 classes and 67 tests (13 and 56 before this branch), with 0 failures and 0 skips. `./gradlew :apps:commerce-api:cleanTest :apps:commerce-api:check` passes in 3 min 21 s, and `scripts/test-counts` reads 79 classes and 943 tests, as before. `BOOTSTRAP_SERVERS` was unset. After the closing review and the broker bump, the same commands pass at `414e300` on `apache/kafka-native:4.4.0`, with the same counts and every context on the 4.4.0 container.
  ```bash
  ./gradlew :apps:commerce-streamer:test --tests '*ConsumerGroupAssignmentTest'     # 1: E1
  ./gradlew :apps:commerce-streamer:test --tests '*KeyPartitioningTest'             # 1: E2
  ./gradlew :apps:commerce-streamer:test --tests '*RedeliveryAfterCommitTest'       # 1: E3
  ./gradlew :apps:commerce-streamer:test --tests '*OrderEventsListenerFailureTest'  # 1: E4
  ./gradlew :apps:commerce-streamer:test --tests '*ExperimentBrokerTest'            # 7: the helper
  ```

## Merge Danger

**Door:** two-way

One production change: a log line on a path that already stopped the consumer. The rest is tests and documents. A revert removes the line, the four experiments and the helper, and nothing on a broker or in a table depends on them.

**Blast Radius:** both apps

- **The failure line changes what a stop logs, not what it does.** Every exception other than the two refusal types now logs `Could not record an order confirmed event; the consumer stops until it is restarted. …` before it leaves the listener. That covers a transient failure, a second unique-key violation in the race retry, and an entity's `IllegalArgumentException`. The refusal line is unchanged. A body the converter cannot read still logs only spring-kafka's line.
- **A transient failure still stops all three consumers** until a person restarts the container or the process (ADR 0033 rule 5, purchase activity design §9). E4 shows that the restart alone recovers it. The production shape, a `DefaultErrorHandler` with backoff and a dead-letter topic, stays out of scope.
- **What one broker cannot show.** `acks: all` on one broker is the leader alone, there is one replica, and failover was not tested.
- **Only controlled stops.** E3's consumer leaves by a bounded `close`, and E4's container is stopped by the error handler and restarted by `start()`. No process was killed. A killed consumer leaves the group only after `session.timeout.ms` (45 s by default).
- **The DB → broker gap remains.** A crash after TX B commits and before the relay's `send` completes loses the event (ADR 0032, order-events design §4 and §6). Week 7's outbox closes it.
- **Test time.** The streamer's check takes 56 s, up from 39 s at step 2's end. E1 waits for three rebalances (8.4 s), E4 for a 3 s lock wait (5.2 s), and E3 and E4 start one Spring context each, for six in all.
- **Versions.** Boot 4.1.2 (kafka-clients 4.2.2), Jetty EE10 12.1.14, springdoc 3.2.0, Gradle 9.8.2, JDK 25.0.5 and the test broker `apache/kafka-native:4.4.0` reach both apps. commerce-api's check gives the same 943 tests.
- **`local` keeps an unused admin address.** `kafka.yml`'s `admin.properties.bootstrap.servers: kafka:9092` is rewritten by Boot to the connection's address, and it is left as the template wrote it (design §5).
- **Out of scope:**
  - killing a process mid-record
  - several brokers and replication
  - `DefaultErrorHandler` and the dead-letter topic
  - tuning `fetch.min.bytes` and `fetch.max.wait.ms`, which are at the client defaults (1 byte, 500 ms)
  - the new group protocol (KIP-848)
  - the outbox
  - rerunning the two week 7 cases
````

## Spec 184: the closing review

Recorded 2026-10-29 from the [closing comment](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184#issuecomment-6496099667) of [spec 184](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/issues/184). It closes the spec after a two-axis review of this branch.

Tickets 185–189 are closed, and the branch had a two-axis review of `week6...dd3850a` (`9554a35...dd3850a`, 17 commits) on 2026-10-29. One axis checked the diff against the repo's standards: `CODING_STANDARDS.md`, the two glossaries and `CONTEXT-MAP.md`, `docs/agents/domain.md`, catalog design §5, ADRs 0010, 0012, 0013, 0016, 0018 and 0033, and a baseline of Fowler's code smells. The other checked it against this spec, its five tickets and their closing comments, the Kafka delivery design and ADR 0033. Every claim about kafka-clients was checked against its 4.2.2 sources. The platform bumps `1618ee7`, `5ba74ca`, `2daf469` and `0be006d` came before the tickets and were left out of both axes. The fixes are [`3aa0b1d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/3aa0b1d), [`98cd1b9`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/98cd1b9), [`33c58da`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/33c58da) and [`5bfe295`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/5bfe295). After them, [`414e300`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/414e300) moves the test broker from `apache/kafka-native:4.3.2` to `4.4.0`.

With [`414e300`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/414e300)'s code, `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-streamer:ktlintCheck :modules:kafka:ktlintCheck :apps:commerce-streamer:cleanTest :apps:commerce-api:cleanTest :apps:commerce-streamer:check :apps:commerce-api:check` passes with no `BOOTSTRAP_SERVERS` in the shell. `scripts/test-counts` reads streamer 18 classes and 67 tests, and commerce-api 79 classes and 943 tests, with 0 failures, 0 errors and 0 skipped, the same as on 4.3.2 at `756f0b7`. Every context in both apps started the 4.4.0 container. E1–E3 printed the same values as the recorded run, and E4 read 1 → 1 → 2 and stopped after 3306 ms. The PR into `week6` is not open yet. Its draft body, updated in [`aa007f3`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/aa007f3), is at the end of the writing material.

### Spec

The user stories and the five tickets' acceptance criteria were checked against the code, not against the closing comments. E3 redelivers the same partition and offset rather than republishing, and the earlier record at offset 0 would show a reset or a rewind. E4 restarts after the cause is removed, with no topic emptying. E1 counts members from the broker's STABLE group description. No experiment record reaches `order-events`. The counts, offsets and timings agree across the design, the writing material and the draft PR body, and the kafka-clients facts (`classic`, `[Range, CooperativeSticky]`, the fetch defaults, `session.timeout.ms`, `close` committing only under auto-commit, `close(Duration)` deprecated since 4.1) match the 4.2.2 sources.

- **Fixed: E4 claimed more of the partial write than it shows.** Story 9 and ticket 187 have the listener's transaction insert the confirmation and the first item before it waits. The scene asserts that the failed statement is an activity insert. The recorder saves the confirmation before the activities, so that shows the confirmation was written, but MySQL's message names no key, so it cannot show which item failed. The test's KDoc and the draft PR body said it showed the first item too. They now say that part follows from the insert order and the held key, which the run's id gaps also show, and the failure-scene bullet of `CODING_STANDARDS.md` names the check. [`5bfe295`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/5bfe295)
- **Left as tickets 187 and 189 say:** the second violation in `recordSettlingARace` logs the failure line by construction, with no scene. E4 reads its class topic rather than a topic of its own (story 22), which the dated note under 공통 규칙 accepts, so the documents say E1–E3 delete their own topic and group.
- **Not scope creep, each recorded in a closing comment:** `ExperimentBrokerTest`, the step 3 bullets of `CODING_STANDARDS.md`, the base's `storedConfirmationsLine`, `storedActivitiesLine` and `assertOnlyTheRowsOf`, and E3's three additions (a record of another order first, the commit in `onPartitionsAssigned`, a one-partition topic), which a dated note covers.
- **Noted:** E3's "before the first read = n" is the test reading back its own commit from `onPartitionsAssigned`. The writing material's table says so.

### Standards

No hard violations. Block bodies, backticked test names, no `@DisplayName`, `@Nested`, Mockito or sleeps, English comments, subclass annotations within `CODING_STANDARDS.md`'s list, constructor dependencies as it describes them, and the helpers in `com.loopers.support` all hold.

- **Fixed: the rename stopped at the glossary.** `9554a35` split 재발행·재전달 into 다시 발행하다 and 다시 전달되다, but ADRs 0032 and 0033 and the order events and purchase activity designs kept the old words. The two designs' name tables also disagreed on what the assignment's 재전달 maps to: the purchase activity design said 같은 사실, the Kafka delivery design 다시 전달되다 and 다시 발행하다. Both now say the delivery words split by record position and both arrive as the same fact. Each table's first column keeps the assignment's words, and the order events design's step 3 note keeps 재전달 where it explains that word. [`3aa0b1d`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/3aa0b1d)
- **Fixed: the step 3 standards paragraph.** One bullet held the helper and three experiments, and repeated which base each experiment extends, which the two base bullets already say. Each experiment now has its own bullet. [`98cd1b9`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/98cd1b9)
- **Fixed: E3's teardown and row check.** E3 closed its consumers and then the broker in sequence, so a close that threw left the experiment's topic and group on the broker. It now closes the consumers inside `broker.use`, as E1 does. Its hand-written row check compared event IDs and quantities only. It now calls the base's `assertOnlyTheRowsOf`, which also compares order IDs and confirmation times. `storedRows()` stays, since it shows that IDs and `updated_at` do not change between the two reads. [`33c58da`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/33c58da)
- **Kept** (judgement calls the review raised and the user left as they are):
  - the subscribe-and-poll loop in `ExperimentBrokerTest` and `RedeliveryAfterCommitTest`, and the `POLL_TIMEOUT` and `ADMIN_WAIT` constants declared twice
  - the send-and-wait-for-metadata call in four classes
  - `logRefusal` and `logFailure`, which differ in prefix and parameter type
  - the paired stored-row line helpers and `logTheScene`'s eight parameters
  - `GroupConsumer.commit` and `position`, which delegate to a public `client`
  - the failure line's wording, quoted in the code, the tests, ADR 0033 and two designs
  - the unbraced `?: return` in the failure scene, a form `OrderPaymentSaga` also uses
- **Not flagged, because `CODING_STANDARDS.md` documents them:** E3 extending `BaseConsumerTest` without using its container, `experimentCommittedOffset`, and `ExperimentBrokerTest` reading the broker through an admin client of its own.

### Also on the branch

- [`414e300`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/414e300), at the user's request during the review: the test broker moves to `apache/kafka-native:4.4.0`, the newest stable tag (2026-10-22). The image still publishes no `4.4` or `4` tag (404), so the pin stays full. The order events design §8 had picked 4.3.2 when 4.4.0 was five days old. Dated notes there and in the Kafka delivery design §5 and §7 record the change.

### Records

- [`51d53f7`](https://github.com/giwankim/loop-pack-be-l2-vol5-kotlin/commit/51d53f7) records this comment in `docs/research/week6-kafka-delivery-writing-material.md`.
