package com.loopers.support.test

import com.loopers.adapter.consumer.OrderEventsProperties
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.TestOrderEventsTopicConfig
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

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
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TestOrderEventsTopicConfig::class)
abstract class BaseConsumerTest : BaseApplicationServiceTest() {
    companion object {
        /** As long as `ContainerTestUtils.waitForAssignment` waits. */
        private val ASSIGNMENT_WAIT: Duration = Duration.ofSeconds(60)
    }

    @Autowired
    private lateinit var registry: KafkaListenerEndpointRegistry

    @Autowired
    private lateinit var orderEventsProperties: OrderEventsProperties

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    @BeforeEach
    fun awaitPartitionAssignment() {
        val topic = orderEventsProperties.topic
        val containers = registry.listenerContainers.filter { topic in it.containerProperties.topics.orEmpty() }
        check(containers.isNotEmpty()) { "No listener container listens to $topic." }

        containers.forEach { awaitEveryConsumerAssigned(it as ConcurrentMessageListenerContainer<*, *>) }
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
    fun cleanUpTables() {
        databaseCleanUp.truncateAllTables()
    }
}
