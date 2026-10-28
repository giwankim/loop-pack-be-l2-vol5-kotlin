package com.loopers.adapter.integration.kafka

import com.loopers.support.test.BaseApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.test.context.TestPropertySource

/**
 * A context that sets `order-events.topic` declares its topic under that name. This is how a test class that reads the broker
 * can isolate itself: the broker is one per JVM, so such a class names a topic of its own (order-events design 2). The
 * override is a different property set, so this class starts a context of its own.
 */
@TestPropertySource(properties = ["order-events.topic=order-events-overridden"])
class OverriddenOrderEventsTopicTest(
    private val kafkaAdmin: KafkaAdmin,
) : BaseApplicationServiceTest() {
    @Test
    fun `the topic is created under the overriding name with the default three partitions`() {
        val topicName = "order-events-overridden"

        val topic = kafkaAdmin.describeTopics(topicName).getValue(topicName)

        assertThat(topic.partitions()).hasSize(3)
    }
}
