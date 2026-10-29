package com.loopers.support

import com.loopers.adapter.consumer.OrderEventsListenerConfig.Companion.ORDER_EVENTS_LISTENER
import com.loopers.support.test.BaseApplicationServiceTest
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.GroupState
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.KafkaAdmin
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The helper every step 3 experiment opens on the shared test broker. What it creates is read back from the broker with an
 * admin client of this test's own, on the same address, so a topic or group the helper only believes it made or removed
 * fails here. The tests of closing close the helper themselves to see what closing does, and the class's close after it
 * must then do nothing, so a test that fails before its own close still leaves the broker clean. The tests of the group
 * description leave the close to `@AfterEach`.
 */
class ExperimentBrokerTest(
    private val kafkaAdmin: KafkaAdmin,
    @Qualifier(ORDER_EVENTS_LISTENER)
    private val factory: ConcurrentKafkaListenerContainerFactory<*, *>,
) : BaseApplicationServiceTest() {
    companion object {
        private val ADMIN_WAIT: Duration = Duration.ofSeconds(10)

        /** How long a consumer polls until the broker assigns it the topic's partition. */
        private val ASSIGNMENT_WAIT: Duration = Duration.ofSeconds(10)

        /** How long one poll blocks, so the wait above checks the assignment often. */
        private val POLL_TIMEOUT: Duration = Duration.ofMillis(100)

        /** The client ID of the consumer that joins a group, which the broker names in the group's description. */
        private const val MEMBER = "experiment-broker-member"
    }

    private lateinit var broker: ExperimentBroker

    @BeforeEach
    fun openTheBroker() {
        broker = ExperimentBroker(kafkaAdmin)
    }

    @AfterEach
    fun closeTheBroker() {
        broker.close()
    }

    @Test
    fun `a topic the helper creates has the partitions asked for and a name of its own, and closing deletes it`() {
        val first = broker.createTopic("experiment-broker", partitions = 2)
        val second = broker.createTopic("experiment-broker", partitions = 2)

        assertThat(first).startsWith("experiment-broker-").isNotEqualTo(second)
        assertThat(partitionsOf(first)).isEqualTo(2)
        assertThat(partitionsOf(second)).isEqualTo(2)

        broker.close()

        assertThat(topicNames()).doesNotContain(first, second)
    }

    @Test
    fun `closing deletes a group the test named, once its consumer has left`() {
        val topic = broker.createTopic("experiment-broker", partitions = 1)
        val group = broker.nameGroup("experiment-broker")
        joinAndLeave(group, topic)
        assertThat(groupIds()).contains(group)

        broker.close()

        assertThat(groupIds()).doesNotContain(group)
    }

    @Test
    fun `the group description is the broker's, a stable group whose member holds the partitions it was assigned`() {
        val topic = broker.createTopic("experiment-broker", partitions = 2)
        val group = broker.nameGroup("experiment-broker")

        checkNotNull(factory.consumerFactory).createConsumer(group, MEMBER, null).use { consumer ->
            awaitAssignment(consumer, topic)

            val description = checkNotNull(broker.describeGroup(group))

            assertThat(description.groupState()).isEqualTo(GroupState.STABLE)
            assertThat(description.partitionsByMember()).isEqualTo(mapOf(MEMBER to listOf(0, 1)))
        }
    }

    @Test
    fun `a named group that no consumer has joined has no description yet`() {
        val group = broker.nameGroup("experiment-broker")

        assertThat(broker.describeGroup(group)).isNull()
    }

    @Test
    fun `closing skips a named group that no consumer joined`() {
        broker.nameGroup("experiment-broker")

        assertThatCode { broker.close() }.doesNotThrowAnyException()
    }

    @Test
    fun `closing again does nothing`() {
        broker.createTopic("experiment-broker", partitions = 1)
        broker.close()

        assertThatCode { broker.close() }.doesNotThrowAnyException()
    }

    /** Nothing checks a committed offset against the log, so the group commits 3 on a partition that holds no record. */
    @Test
    fun `the committed offset reader answers what a group committed on a partition, and null where it committed none`() {
        val topic = broker.createTopic("experiment-broker", partitions = 2)
        val group = broker.nameGroup("experiment-broker")
        val neverJoined = broker.nameGroup("experiment-broker")

        joinAndLeave(group, topic) { it.commitSync(mapOf(TopicPartition(topic, 1) to OffsetAndMetadata(3))) }

        assertThat(broker.committedOffset(group, TopicPartition(topic, 1))).isEqualTo(3)
        assertThat(broker.committedOffset(group, TopicPartition(topic, 0))).isNull()
        assertThat(broker.committedOffset(neverJoined, TopicPartition(topic, 1))).isNull()
    }

    /**
     * A consumer of the listener factory's consumer factory, in [group], subscribes to [topic], waits to be assigned its
     * partitions, runs [commit], which commits its position unless told otherwise, and leaves. The group then exists on the
     * broker with no member.
     */
    private fun joinAndLeave(group: String, topic: String, commit: (Consumer<*, *>) -> Unit = { it.commitSync() }) {
        checkNotNull(factory.consumerFactory).createConsumer(group, null).use { consumer ->
            awaitAssignment(consumer, topic)
            commit(consumer)
        }
    }

    /**
     * [consumer] subscribes to [topic] and polls until the broker has assigned it a partition: it is a member of its group. The
     * polls run on the test thread, so a wait that times out leaves no poll running as the caller closes the consumer.
     */
    private fun awaitAssignment(consumer: Consumer<*, *>, topic: String) {
        consumer.subscribe(listOf(topic))
        await().atMost(ASSIGNMENT_WAIT).pollInSameThread().until {
            consumer.poll(POLL_TIMEOUT)
            consumer.assignment().isNotEmpty()
        }
    }

    /** The partition count of [topic] as the broker describes it. */
    private fun partitionsOf(topic: String): Int {
        return withAdmin { admin ->
            admin.describeTopics(listOf(topic))
                .allTopicNames()
                .get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
                .getValue(topic)
                .partitions()
                .size
        }
    }

    /** Every group ID the broker lists. */
    private fun groupIds(): List<String> {
        return withAdmin { admin ->
            admin.listGroups().all().get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS).map { it.groupId() }
        }
    }

    /** Every topic name the broker lists. */
    private fun topicNames(): Set<String> {
        return withAdmin { admin ->
            admin.listTopics().names().get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
        }
    }

    /** Runs [read] on an admin client of this test's own, opened on the context's address and closed after. */
    private fun <T> withAdmin(read: (Admin) -> T): T {
        return Admin.create(kafkaAdmin.configurationProperties).use(read)
    }
}
