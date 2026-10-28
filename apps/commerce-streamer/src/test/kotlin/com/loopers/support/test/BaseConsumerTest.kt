package com.loopers.support.test

import com.loopers.adapter.consumer.OrderEventsProperties
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.TestOrderEventsTopicConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The base of the streamer's consumer tests: a test puts a record on the broker, and a listener container records it on its
 * own thread (purchase activity design 7). It extends [BaseApplicationServiceTest] but leaves the test transaction, because
 * the listener's transaction commits outside it; each test therefore empties every table when it ends.
 *
 * The broker is one per JVM, so each subclass names a topic of its own with
 * `@TestPropertySource(properties = ["order-events.topic=…"])`, which also gives it a context of its own.
 * [TestOrderEventsTopicConfig] declares that topic, since the streamer never creates it.
 *
 * Before each test the base waits until the listener containers on that topic hold all its partitions. A scene's wait then
 * measures the listener rather than the group joining, and a scene that expects nothing to happen starts from consumers that
 * are reading. A context with no container on the topic fails here instead of waiting out a scene's timeout.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TestOrderEventsTopicConfig::class)
abstract class BaseConsumerTest : BaseApplicationServiceTest() {
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

        containers.forEach { ContainerTestUtils.waitForAssignment(it, TestOrderEventsTopicConfig.PARTITIONS) }
    }

    @AfterEach
    fun cleanUpTables() {
        databaseCleanUp.truncateAllTables()
    }
}
