package com.loopers.adapter.consumer

import com.loopers.adapter.consumer.OrderEventsListenerConfig.Companion.ORDER_EVENTS_LISTENER
import com.loopers.support.ExperimentBroker
import com.loopers.support.partitionsByMember
import com.loopers.support.test.BaseApplicationServiceTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.admin.ConsumerGroupDescription
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.GroupState
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.ContainerProperties.AckMode
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Step 3's group assignment experiment (issue 184): members join one group on a 2-partition topic one, two, then three at a
 * time, and at each size the broker's description of the group says which member holds which partition. Each partition has
 * exactly one member at every size, and the third member, with no partition left, holds none: members past the partition
 * count add no parallelism. The topic and the group are the experiment's own, which [ExperimentBroker] creates and names
 * before the scene and deletes after it, so no record reaches a listener's topic and no table is touched.
 *
 * Each member is a single-consumer container with a client ID of its own, built from the consumer factory of the streamer's
 * listener factory, so it joins with the app's consumer settings (purchase activity design 5). That consumer factory is built
 * inside the listener factory rather than declared as a bean, so it is taken from there. The group protocol and the assignor
 * are the client's defaults, which the factory leaves alone: in kafka-clients 4.2.2 `group.protocol` is `classic` and
 * `partition.assignment.strategy` is `[RangeAssignor, CooperativeStickyAssignor]`. A client rebalances by the newest
 * protocol every assignor in its list supports, and the range assignor supports only the eager one, so each join revokes
 * every member's partitions and deals them out again. Every member lists `range` first, so the coordinator picks it. Range
 * orders the members by member ID, which the broker makes from the client ID and a random suffix
 * (`member-1-ce26db2b-…`), so here `member-1` takes partition 0 and `member-3` is left with none. That is the assignor's
 * choice, not the group's promise, and another assignor may deal otherwise, so the scene never asserts which member gets
 * which partition.
 *
 * At each size the scene waits until the broker describes the group as stable with exactly that many members, and records the
 * member → partitions table from that description. Membership is counted from the broker, never from the container or
 * consumer objects. The table is then cross-checked against each container's assigned partitions, which follow a little
 * after the broker, so that check waits too. Then a probe record is sent to each partition by number, and the scene waits
 * until a member has read each. Each was read by its partition's member, and by no other: the readers are counted once the
 * third member has joined. The probe listener acknowledges a record before it notes the read, so the offset of a read the
 * scene has seen is already committed, and the next member's join does not hand the record to another.
 *
 * The scene logs one INFO line per size with the table and the probe reads, as `KeyPartitioningTest` logs its table. Every
 * wait is bounded, and the members are stopped before the topic and the group are deleted: the broker refuses to delete a
 * group with a member. Run it with
 * `./gradlew :apps:commerce-streamer:test --tests 'com.loopers.adapter.consumer.ConsumerGroupAssignmentTest'`.
 */
class ConsumerGroupAssignmentTest(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val kafkaAdmin: KafkaAdmin,
    @Qualifier(ORDER_EVENTS_LISTENER)
    private val factory: ConcurrentKafkaListenerContainerFactory<String, ByteArray>,
) : BaseApplicationServiceTest() {
    companion object {
        /** Two partitions, so the third member finds none to hold. */
        private const val PARTITIONS = 2

        /** The group grows to this many members, one at a time. */
        private const val MEMBERS = 3

        /**
         * How long the broker gets to settle the group after a member joins. The members already in it rejoin once a heartbeat
         * tells them of the rebalance, every 3 s by the client's default, so a join takes a few seconds.
         */
        private val GROUP_WAIT: Duration = Duration.ofSeconds(30)

        /** How long the broker gets to take a probe, and the members to read it. */
        private val RECORD_WAIT: Duration = Duration.ofSeconds(10)
    }

    private lateinit var broker: ExperimentBroker

    private lateinit var topic: String

    private lateinit var group: String

    /** The members' containers, in the order they joined. */
    private val members = mutableListOf<KafkaMessageListenerContainer<String, ByteArray>>()

    /** Every record a member has read. The members read on threads of their own. */
    private val reads = ConcurrentLinkedQueue<Read>()

    @BeforeEach
    fun createTheTopicAndNameTheGroup() {
        broker = ExperimentBroker(kafkaAdmin)
        topic = broker.createTopic("consumer-group-assignment", PARTITIONS)
        group = broker.nameGroup("consumer-group-assignment")
    }

    /** Stops every member before the broker deletes the group, which it refuses while the group has a member. */
    @AfterEach
    fun stopTheMembersAndCloseTheBroker() {
        broker.use {
            members.forEach { it.stop() }
        }
    }

    @Test
    fun `as members join one, two, then three, each partition has exactly one member, and at three a member has none`() {
        val snapshots = (1..MEMBERS).map { joinOneMore() }

        snapshots.forEach { snapshot ->
            assertThat(snapshot.table.values.flatten())
                .describedAs("the partitions the members hold at ${snapshot.table.size}")
                .containsExactlyInAnyOrderElementsOf(0 until PARTITIONS)
            snapshot.probes.forEach { probe ->
                assertThat(readersOf(probe))
                    .describedAs("the members that read the probe on partition ${probe.partition()} at ${snapshot.table.size}")
                    .containsExactly(snapshot.memberHolding(probe.partition()))
            }
        }
        assertThat(snapshots.last().table.values)
            .describedAs("the partitions of each member at $MEMBERS")
            .anyMatch { it.isEmpty() }
        snapshots.forEach { logTheSnapshot(it) }
    }

    /**
     * Starts one more member, waits until the broker describes the group as stable with every member started so far, and
     * cross-checks the containers against it. Then it sends a probe to each partition and waits until each has been read.
     */
    private fun joinOneMore(): GroupSnapshot {
        members += startMember("member-${members.size + 1}")

        val description = awaitStableGroupOf(members.size)
        val table = description.partitionsByMember()
        awaitContainersHolding(table)
        val probes = (0 until PARTITIONS).map { sendProbe(it) }
        awaitRead(probes)

        return GroupSnapshot(description, table, probes)
    }

    /**
     * A single-consumer container of the listener factory's consumer factory, in the experiment's group with [member] as its
     * client ID, started. Its listener acknowledges each record and then notes which member read it, at which partition and
     * offset.
     */
    private fun startMember(member: String): KafkaMessageListenerContainer<String, ByteArray> {
        val properties = ContainerProperties(topic).apply {
            setGroupId(group)
            clientId = member
            ackMode = AckMode.MANUAL_IMMEDIATE
            setMessageListener(
                AcknowledgingMessageListener<String, ByteArray> { record, acknowledgment ->
                    checkNotNull(acknowledgment).acknowledge()
                    reads += Read(member, record.partition(), record.offset())
                },
            )
        }
        val container = KafkaMessageListenerContainer(checkNotNull(factory.consumerFactory), properties)
        container.beanName = member
        container.start()

        return container
    }

    /** Waits until the broker describes the group as stable with exactly [size] members, and returns that description. */
    private fun awaitStableGroupOf(size: Int): ConsumerGroupDescription {
        val description = await().atMost(GROUP_WAIT).until({ broker.describeGroup(group) }) {
            it != null && it.groupState() == GroupState.STABLE && it.members().size == size
        }

        return checkNotNull(description)
    }

    /** Waits until each member's container holds the partitions [table] gives its client ID, and no other member is in it. */
    private fun awaitContainersHolding(table: Map<String, List<Int>>) {
        await().atMost(GROUP_WAIT).untilAsserted {
            val held = members.associate { container ->
                container.containerProperties.clientId to container.assignedPartitions.orEmpty().map { it.partition() }.sorted()
            }

            assertThat(held).describedAs("each container's assigned partitions").isEqualTo(table)
        }
    }

    /** Sends a probe to [partition] by number, so the key plays no part, and returns where the broker put it. */
    private fun sendProbe(partition: Int): RecordMetadata {
        return kafkaTemplate.send(topic, partition, null, "probe at ${members.size} members on partition $partition")
            .get(RECORD_WAIT.toSeconds(), TimeUnit.SECONDS)
            .recordMetadata
    }

    private fun awaitRead(probes: List<RecordMetadata>) {
        await().atMost(RECORD_WAIT).until { probes.all { readersOf(it).isNotEmpty() } }
    }

    /** The members that have read [probe], in the order they read it. */
    private fun readersOf(probe: RecordMetadata): List<String> {
        return reads.filter { it.partition == probe.partition() && it.offset == probe.offset() }.map { it.member }
    }

    /** Logs one size: the group as the broker described it, the member → partitions table, and who read each probe. */
    private fun logTheSnapshot(snapshot: GroupSnapshot) {
        val rows = snapshot.table.toSortedMap().entries.joinToString("\n") { (member, partitions) ->
            "|$member: partitions=$partitions"
        }
        val description = snapshot.description
        val probes = snapshot.probes.joinToString("\n") { probe ->
            "|probe partition=${probe.partition()}, offset=${probe.offset()}: read by ${readersOf(probe)}"
        }

        log.info {
            """
            |E1 group assignment, ${snapshot.table.size} of $MEMBERS members
            |topic=$topic, partitions=$PARTITIONS, group=$group
            |state=${description.groupState()}, type=${description.type()}, assignor=${description.partitionAssignor()}
            $rows
            $probes
            """.trimMargin()
        }
    }

    /** One record a member read. */
    private data class Read(val member: String, val partition: Int, val offset: Long)

    /** The group settled at one size: the broker's description, its member → partitions table, and a probe per partition. */
    private data class GroupSnapshot(
        val description: ConsumerGroupDescription,
        val table: Map<String, List<Int>>,
        val probes: List<RecordMetadata>,
    ) {
        /** The one member the table gives [partition]. */
        fun memberHolding(partition: Int): String {
            return table.entries.single { partition in it.value }.key
        }
    }
}
