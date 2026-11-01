package com.loopers.support

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.ConsumerGroupDescription
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.GroupIdNotFoundException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.awaitility.Awaitility.await
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The topics and groups one step 3 experiment uses on the shared test broker, created for the scene and removed after it
 * (issue 184's common rules). The broker is one per JVM, and every context's order events listener reads its own topic on it in the
 * one group `commerce-streamer`, so an experiment touches neither: it creates a topic of its own here and names the groups
 * its consumers join, and [close] deletes both, so the next test finds the broker as this one did.
 *
 * The admin client is opened on the address the context's [KafkaAdmin] holds, which `KafkaTestContainersConfig` put into
 * `spring.kafka.bootstrap-servers`. It takes no address of its own, so an experiment cannot mix the local profile's
 * `localhost:19092` or compose's `kafka:9092` with the test broker.
 *
 * A test opens one in `@BeforeEach` and closes it in `@AfterEach`, after it has closed its consumers: the broker refuses to
 * delete a group that still has a member. A group nobody joined is skipped, so a scene that failed before its consumer joined
 * still cleans up. Closing again does nothing, so a test that closes it itself can still leave the close to `@AfterEach`.
 */
class ExperimentBroker(kafkaAdmin: KafkaAdmin) : AutoCloseable {
    companion object {
        /** How long the broker gets to create or delete a topic, to describe it so, and to delete a group. */
        private val ADMIN_WAIT: Duration = Duration.ofSeconds(10)

        /** The test broker is a single node, so a partition has one replica (order-events design 7). */
        private const val REPLICAS = 1
    }

    private val admin: Admin = Admin.create(kafkaAdmin.configurationProperties)

    private val topics = mutableListOf<String>()

    private val groups = mutableListOf<String>()

    private var closed = false

    /**
     * Creates a topic named [prefix] plus a suffix unique to this call, with [partitions] partitions and one replica, and
     * returns the name once the broker describes it with that many.
     */
    fun createTopic(prefix: String, partitions: Int): String {
        val topic = uniqueName(prefix)
        val newTopic = TopicBuilder.name(topic).partitions(partitions).replicas(REPLICAS).build()

        admin.createTopics(listOf(newTopic)).all().get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
        topics += topic
        await().atMost(ADMIN_WAIT).until { describedPartitions(topic) == partitions }

        return topic
    }

    /** Names a group this experiment's consumers join, [prefix] plus a suffix unique to this call. [close] deletes it. */
    fun nameGroup(prefix: String): String {
        val group = uniqueName(prefix)
        groups += group

        return group
    }

    /**
     * [group] as the broker describes it now: its state, its members by client ID, and the partitions each holds. A group's
     * membership is read here, never counted from consumer or container objects, which can hold an assignment the broker has
     * already moved on from. The description is one moment's, so a test waits on it until the group has settled.
     *
     * Null while the broker does not know the group, as before its first consumer joins: a 4.x broker answers an unknown group
     * with `GroupIdNotFoundException`, where an older one described it as `Dead` with no members.
     */
    fun describeGroup(group: String): ConsumerGroupDescription? {
        return try {
            admin.describeConsumerGroups(listOf(group))
                .describedGroups()
                .getValue(group)
                .get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            if (e.cause is GroupIdNotFoundException) null else throw e
        }
    }

    /**
     * The offset [group] has committed on [partition], as the broker answers it, or null where the group has committed none, a
     * group no consumer joined included. It is the broker's word on where the group reads next, which a consumer's own
     * position is not.
     */
    fun committedOffset(group: String, partition: TopicPartition): Long? {
        val offsets = admin.listConsumerGroupOffsets(group)
            .partitionsToOffsetAndMetadata()
            .get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)

        return offsets[partition]?.offset()
    }

    /**
     * Deletes every group this experiment named and every topic it created, waits until the broker no longer describes the
     * topics, and closes the client. The test closes its consumers first: the broker refuses to delete a group with a member.
     */
    override fun close() {
        if (closed) {
            return
        }

        closed = true
        admin.use {
            deleteGroups()
            deleteTopics()
        }
    }

    /** A named group that no consumer joined is unknown to the broker, as after a scene that failed before its consumer joined. */
    private fun deleteGroups() {
        if (groups.isEmpty()) {
            return
        }

        admin.deleteConsumerGroups(groups).deletedGroups().forEach { (group, deletion) ->
            try {
                deletion.get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                if (e.cause !is GroupIdNotFoundException) {
                    throw e
                }
            }
        }
    }

    private fun deleteTopics() {
        if (topics.isEmpty()) {
            return
        }

        admin.deleteTopics(topics).all().get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
        await().atMost(ADMIN_WAIT).until { topics.all { describedPartitions(it) == null } }
    }

    /** The partition count of [topic] as the broker describes it, or null while the broker does not know the topic. */
    private fun describedPartitions(topic: String): Int? {
        return try {
            admin.describeTopics(listOf(topic))
                .allTopicNames()
                .get(ADMIN_WAIT.toSeconds(), TimeUnit.SECONDS)
                .getValue(topic)
                .partitions()
                .size
        } catch (e: ExecutionException) {
            if (e.cause is UnknownTopicOrPartitionException) null else throw e
        }
    }

    private fun uniqueName(prefix: String): String {
        return "$prefix-${UUID.randomUUID().toString().take(8)}"
    }
}

/**
 * The member → partitions table of a group's description: each member by client ID, with the numbers of the partitions the
 * broker says it holds, in order. A member that holds none has an empty list. An experiment gives each member a client ID
 * of its own, since two members with one client ID would share a row.
 */
fun ConsumerGroupDescription.partitionsByMember(): Map<String, List<Int>> {
    return members().associate { member ->
        member.clientId() to member.assignment().topicPartitions().map { it.partition() }.sorted()
    }
}
