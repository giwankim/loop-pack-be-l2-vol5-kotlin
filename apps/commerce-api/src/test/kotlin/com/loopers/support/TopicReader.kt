package com.loopers.support

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration

/**
 * Reads what reaches a Kafka topic of the test broker from the moment it is opened, so a scene sees only what it published.
 * A test opens one before each scene and closes it after.
 *
 * It starts at the topic's end rather than its earliest offset. A test class names a topic of its own, since the broker is one
 * per JVM, and its scenes share that topic. `DatabaseCleanUp` truncates the tables after every committing test, so order IDs,
 * and the keys and eventIds derived from them, start again at 1, and an earlier scene's records would pass for this one's.
 *
 * The consumer is assigned every partition of the topic and placed at its end, and `position` makes it take those offsets now
 * rather than at the first read, when the scene's records are already there. It joins no group and commits nothing, so the
 * group name `KafkaTestUtils` asks for is never used, and no group assignment can land after the records it should read.
 */
class TopicReader(
    bootstrapServers: List<String>,
    topic: String,
) : AutoCloseable {
    companion object {
        /** How long a scene waits for the records it expects. Reading returns as soon as they are there. */
        private val RECORD_WAIT: Duration = Duration.ofSeconds(10)

        /**
         * How long a scene reads on after the records it expects, so that a record it should not have published has time to
         * arrive. Such a record would follow a commit the call has already returned from, after one hand-off to a virtual
         * thread and one round trip to the broker in the same machine.
         */
        private val QUIET_WAIT: Duration = Duration.ofSeconds(1)
    }

    private val consumer: Consumer<String, String>

    init {
        val properties = KafkaTestUtils.consumerProps(bootstrapServers.joinToString(","), topic)
        consumer = DefaultKafkaConsumerFactory(properties, StringDeserializer(), StringDeserializer()).createConsumer()
        val partitions = consumer.partitionsFor(topic).map { TopicPartition(it.topic(), it.partition()) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        partitions.forEach { consumer.position(it) }
    }

    /**
     * Every record published on the topic since it was opened, which a scene that is right has exactly [expected] of. Waits up
     * to [RECORD_WAIT] for that many, since the relay publishes after the call returned, then reads on for [QUIET_WAIT] so that
     * a record the scene should not publish has time to arrive. A scene that expects none waits only for the quiet period.
     */
    fun publishedRecords(expected: Int): List<ConsumerRecord<String, String>> {
        val awaited = if (expected == 0) emptyList() else KafkaTestUtils.getRecords(consumer, RECORD_WAIT, expected).toList()
        val further = KafkaTestUtils.getRecords(consumer, QUIET_WAIT, 1).toList()

        return awaited + further
    }

    override fun close() {
        consumer.close()
    }
}
