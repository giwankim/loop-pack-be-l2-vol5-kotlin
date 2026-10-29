package com.loopers.support.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.loopers.adapter.consumer.OrderEventsProperties
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.PurchaseActivity
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.TestOrderEventsTopicConfig
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.RecordsToDelete
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Import
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The base of the streamer's consumer tests: a test puts a record on the broker, and a listener container records it on its
 * own thread (purchase activity design 7). It extends [BaseApplicationServiceTest] but leaves the test transaction, because
 * the listener's transaction commits outside it; each test therefore empties every table when it ends.
 *
 * The broker is one per JVM, so each subclass names a topic of its own with
 * `@TestPropertySource(properties = ["order-events.topic=…"])`, which also gives it a context of its own.
 * [TestOrderEventsTopicConfig] declares that topic, since the streamer never creates it.
 *
 * Before each test the base waits until every consumer of the listener containers on that topic holds a partition, so the
 * group has settled. A scene's wait then measures the listener rather than the group joining, a scene that expects nothing to
 * happen starts from consumers that are reading, and no rebalance falls inside a scene. A context with no container on the
 * topic fails here instead of waiting out a scene's timeout.
 *
 * Holding all the partitions is not enough. The test broker forms a group's first generation as soon as one member joins
 * (Testcontainers sets `group.initial.rebalance.delay.ms` to 0, where a broker's default waits 3 s), so the first of three
 * consumers can hold all three partitions until the next generation brings in the other two, about a heartbeat later.
 * spring-kafka-test's `ContainerTestUtils.waitForAssignment` adds up the consumers' partitions and returns at that first
 * generation.
 *
 * Every context's order events listener joins the one group `commerce-streamer`, each on its own topic. Only the running test's
 * consumers are in it: Spring Framework 7 pauses a cached context that is not in use (`spring.test.context.cache.pause`), and
 * its stopped containers leave the group.
 *
 * A scene drives the listener as commerce-api's relay would: it [publish]es with the shared [KafkaTemplate], waits until the
 * group has committed past the record ([awaitCommittedPast]), and then reads the two tables and the listener's log lines.
 * A scene whose record stops the container waits for the stop instead ([awaitStopped]). It leaves the container stopped, and
 * the base restarts it on a topic emptied of what the scene left there, so the next test starts from a running container
 * ([restartAStoppedContainer]).
 *
 * Step 3's redelivery experiment, `RedeliveryAfterCommitTest`, extends the base for the committed recording, the listener's
 * log lines and the clean-up, but no container records its record. Plain consumers read it from a topic of the experiment's
 * own, and the test calls the listener itself (issue 184, seam B). It still names a class topic, which the base's container
 * reads and the base waits on.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TestOrderEventsTopicConfig::class)
abstract class BaseConsumerTest : BaseApplicationServiceTest() {
    companion object {
        /** As long as `ContainerTestUtils.waitForAssignment` waits. */
        private val ASSIGNMENT_WAIT: Duration = Duration.ofSeconds(60)

        /** The streamer's group, as `application.yml` names it. A literal, so a listener that joined another group fails here. */
        private const val GROUP = "commerce-streamer"

        /**
         * How long a scene waits for the broker to take a record, for the group to commit past it, and for the container to
         * stop on it.
         */
        private val RECORD_WAIT: Duration = Duration.ofSeconds(10)

        /** `RecordsToDelete.beforeOffset` truncates a partition to its high watermark at this offset: every record goes. */
        private const val HIGH_WATERMARK = -1L
    }

    @Autowired
    private lateinit var registry: KafkaListenerEndpointRegistry

    @Autowired
    private lateinit var orderEventsProperties: OrderEventsProperties

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

    @Autowired
    private lateinit var kafkaProperties: KafkaProperties

    @Autowired
    private lateinit var kafkaAdmin: KafkaAdmin

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    /** Logs from the consumer adapter, where the listener logs each record it records, skips or refuses. */
    private val listenerLogs = ListAppender<ILoggingEvent>()

    private val listenerLogger = LoggerFactory.getLogger("com.loopers.adapter.consumer") as Logger

    @BeforeEach
    fun captureListenerLogs() {
        listenerLogs.start()
        listenerLogger.addAppender(listenerLogs)
    }

    @BeforeEach
    fun awaitPartitionAssignment() {
        listenerContainers().forEach { awaitEveryConsumerAssigned(it) }
    }

    /**
     * Waits until each consumer of [container] holds a partition, and together they hold all of the topic's. A stopped container
     * never gets there, so one left stopped by an earlier test fails here at once.
     */
    private fun awaitEveryConsumerAssigned(container: ConcurrentMessageListenerContainer<*, *>) {
        check(container.isRunning) { "The listener container ${container.listenerId} is not running." }

        await().atMost(ASSIGNMENT_WAIT).until {
            val assignments = container.containers.map { it.assignedPartitions.orEmpty() }

            assignments.all { it.isNotEmpty() } && assignments.sumOf { it.size } == TestOrderEventsTopicConfig.PARTITIONS
        }
    }

    @AfterEach
    fun releaseListenerLogs() {
        listenerLogger.detachAppender(listenerLogs)
        listenerLogs.stop()
    }

    /**
     * Restarts a container the test left stopped, on an emptied topic. The record it stopped on was never committed, so a
     * restart alone would read that record again and stop again, as the restart scene shows. Emptying the topic is to the
     * broker what [cleanUpTables] is to the database. The group's committed offsets stay where they were, behind the emptied
     * log, so the restarted consumers start from its end (`auto.offset.reset` is `earliest`, and the earliest is the end).
     */
    @AfterEach
    fun restartAStoppedContainer() {
        val stopped = listenerContainers().filterNot { it.isRunning }
        if (stopped.isEmpty()) {
            return
        }

        stopped.forEach { awaitConsumersClosed(it) }
        emptyTheTopic()
        stopped.forEach { it.start() }
    }

    @AfterEach
    fun cleanUpTables() {
        databaseCleanUp.truncateAllTables()
    }

    /** The listener containers on the class's topic: the order events listener's, in a context that has one. */
    private fun listenerContainers(): List<ConcurrentMessageListenerContainer<*, *>> {
        val topic = orderEventsProperties.topic
        val containers = registry.listenerContainers.filter { topic in it.containerProperties.topics.orEmpty() }
        check(containers.isNotEmpty()) { "No listener container listens to $topic." }

        return containers.map { it as ConcurrentMessageListenerContainer<*, *> }
    }

    /**
     * Publishes [value] keyed by [orderId], as commerce-api's relay does, and returns where the broker put it. Without a
     * [partition] the key chooses it.
     */
    protected fun publish(orderId: Long, value: JsonNode, partition: Int? = null): RecordMetadata {
        val record = ProducerRecord<Any, Any>(orderEventsProperties.topic, partition, orderId.toString(), value)

        return kafkaTemplate.send(record)
            .get(RECORD_WAIT.toSeconds(), TimeUnit.SECONDS)
            .recordMetadata
    }

    /** Waits until the streamer's group has committed [record]'s partition one past it: the record is done with. */
    protected fun awaitCommittedPast(record: RecordMetadata) {
        await().atMost(RECORD_WAIT).untilAsserted {
            assertThat(committedOffset(record.partition())).isEqualTo(record.offset() + 1)
        }
    }

    /** The streamer's group's committed offset on [partition] of the class's topic, or null if it has committed none there. */
    protected fun committedOffset(partition: Int): Long? {
        val brokers = kafkaProperties.bootstrapServers.joinToString(",")

        return KafkaTestUtils.getCurrentOffset(brokers, GROUP, orderEventsProperties.topic, partition)?.offset()
    }

    /**
     * Waits until the order events listener's container has stopped and each of its consumers has closed, and returns it. The
     * stopping error handler stops the container from a thread of its own. The container is not running from the moment the
     * stop begins, and its consumers close after that, so a scene waits for both: once the last consumer has closed, nothing
     * more happens to the record it stopped on.
     */
    protected fun awaitStopped(): ConcurrentMessageListenerContainer<*, *> {
        val container = listenerContainers().single()
        await().atMost(RECORD_WAIT).until { !container.isRunning }
        awaitConsumersClosed(container)

        return container
    }

    /**
     * Waits until every consumer of [container] has closed. `isChildRunning` counts a consumer from its start until its thread
     * has closed it and published `ConsumerStoppedEvent`.
     */
    private fun awaitConsumersClosed(container: ConcurrentMessageListenerContainer<*, *>) {
        await().atMost(RECORD_WAIT).until { !container.isChildRunning }
    }

    /** Deletes every record of the class's topic, on each partition up to its high watermark. Offsets go on from there. */
    private fun emptyTheTopic() {
        val partitions = (0 until TestOrderEventsTopicConfig.PARTITIONS).map { TopicPartition(orderEventsProperties.topic, it) }

        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            admin.deleteRecords(partitions.associateWith { RecordsToDelete.beforeOffset(HIGH_WATERMARK) })
                .all()
                .get(RECORD_WAIT.toSeconds(), TimeUnit.SECONDS)
        }
    }

    /**
     * The listener's lines at [level] in this test. It logs before it acknowledges, so once the offset is committed every line of
     * the record is there, and it logs a refusal before the container stops. A line is added on the listener's thread, under
     * the appender's own lock, so the list is read under it.
     */
    protected fun listenerLines(level: Level): List<String> {
        return synchronized(listenerLogs) {
            listenerLogs.list.filter { it.level == level }.map { it.formattedMessage }
        }
    }

    /** Every confirmation in the database. The listener's transaction has committed, so the read needs none of its own. */
    protected fun storedConfirmations(): List<ProcessedOrderConfirmation> {
        return entityManager
            .createQuery("select c from ProcessedOrderConfirmation c order by c.id", ProcessedOrderConfirmation::class.java)
            .resultList
    }

    protected fun storedActivities(): List<PurchaseActivity> {
        return entityManager
            .createQuery("select a from PurchaseActivity a order by a.id", PurchaseActivity::class.java)
            .resultList
    }

    /** The confirmations as stored, on one line, for a scene that logs what it observed. */
    protected fun storedConfirmationsLine(): String {
        return "processed_order_confirmation: " + storedConfirmations().joinToString("; ") {
            "id=${it.id}, event_id=${it.eventId}, order_id=${it.orderId}, confirmed_at=${it.confirmedAt}"
        }
    }

    /** The activities as stored, on one line. */
    protected fun storedActivitiesLine(): String {
        return "purchase_activity: " + storedActivities().joinToString("; ") {
            "id=${it.id}, order_id=${it.orderId}, product_id=${it.productId}, quantity=${it.quantity}, " +
                "confirmed_at=${it.confirmedAt}"
        }
    }

    /** The tables hold the confirmation and the activities of each of [events], and nothing else. */
    protected fun assertOnlyTheRowsOf(vararg events: PurchaseActivityRecordRequest) {
        assertThat(storedConfirmations().map { Triple(it.eventId, it.orderId, it.confirmedAt) })
            .containsExactlyInAnyOrderElementsOf(events.map { Triple(it.eventId, it.orderId, it.confirmedAt) })
        assertThat(storedActivities().map { listOf(it.orderId, it.productId, it.quantity, it.confirmedAt) })
            .containsExactlyInAnyOrderElementsOf(
                events.flatMap { event ->
                    event.items.map { listOf(event.orderId, it.productId, it.quantity, event.confirmedAt) }
                },
            )
    }
}
