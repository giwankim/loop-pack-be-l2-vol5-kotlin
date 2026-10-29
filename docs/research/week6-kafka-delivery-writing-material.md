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
