package com.loopers.adapter.consumer

import com.loopers.support.ExperimentBroker
import com.loopers.support.test.BaseApplicationServiceTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.producer.RecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Step 3's key → partition experiment (issue 184): every send of a key lands on one partition. The template is the context's,
 * which `modules:kafka`'s `KafkaConfig` builds, so the producer configuration and the default partitioner are the ones
 * commerce-api's relay publishes with. The topic is the experiment's own, with the production count of 3 partitions, which
 * [ExperimentBroker] creates before the scene and deletes after it. Nothing reads the topic, and no table is touched.
 *
 * The keys are order IDs as the contract writes them in the key (order-events design 2). Each is sent a few times, and the
 * partition of each send is read from its `SendResult`'s metadata. The only claim is that each key's sends went to one
 * partition. With a key and no partition argument, the default partitioner hashes the key's bytes over the topic's partition
 * count, so the claim holds only while that count stays the same. Nothing says different keys spread out: five keys over
 * three partitions can share one, and ordering by order does not rest on the spread.
 *
 * The scene logs the key → partition table in one INFO line, as `OrderEventConsumedCaseTest` logs what it saw. Run it with
 * `./gradlew :apps:commerce-streamer:test --tests 'com.loopers.adapter.consumer.KeyPartitioningTest'`.
 */
class KeyPartitioningTest(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val kafkaAdmin: KafkaAdmin,
) : BaseApplicationServiceTest() {
    companion object {
        /** The production count: commerce-api declares `order-events` with 3 (order-events design 7). */
        private const val PARTITIONS = 3

        /** Order IDs as the contract writes them in the key, order-events design 2's example order 42 among them. */
        private val KEYS = listOf("1", "2", "42", "1000", "123456789")

        private const val SENDS_PER_KEY = 3

        /** How long a send waits for the broker to take the record. */
        private val SEND_WAIT: Duration = Duration.ofSeconds(10)
    }

    private lateinit var broker: ExperimentBroker

    private lateinit var topic: String

    @BeforeEach
    fun createTheTopic() {
        broker = ExperimentBroker(kafkaAdmin)
        topic = broker.createTopic("key-partitioning", PARTITIONS)
    }

    @AfterEach
    fun closeTheBroker() {
        broker.close()
    }

    @Test
    fun `every send of a key lands on the partition its first send did`() {
        val sends = KEYS.associateWith { key -> (1..SENDS_PER_KEY).map { send(key, it) } }

        sends.forEach { (key, metadata) ->
            assertThat(metadata.map { it.partition() })
                .describedAs("the partitions of key $key's sends")
                .containsOnly(metadata.first().partition())
        }
        logTheTable(sends)
    }

    /** Sends the [n]th record of [key] with the key choosing the partition, and returns where the broker put it. */
    private fun send(key: String, n: Int): RecordMetadata {
        return kafkaTemplate.send(topic, key, "send $n of key $key")
            .get(SEND_WAIT.toSeconds(), TimeUnit.SECONDS)
            .recordMetadata
    }

    /** Logs the key → partition table: for each key, the one partition and the offsets of its sends in order. */
    private fun logTheTable(sends: Map<String, List<RecordMetadata>>) {
        val rows = sends.entries.joinToString("\n") { (key, metadata) ->
            "|key=$key: partition=${metadata.first().partition()}, offsets=${metadata.map { it.offset() }}"
        }

        log.info {
            """
            |E2 key → partition
            |topic=$topic, partitions=$PARTITIONS, sends per key=$SENDS_PER_KEY
            $rows
            """.trimMargin()
        }
    }
}
