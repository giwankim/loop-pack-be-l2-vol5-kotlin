package com.loopers.adapter.integration.kafka

import com.loopers.support.test.BaseApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin

/**
 * The application declares its `order-events` topic, and `KafkaAdmin` creates it on the test broker while the context starts
 * (ADR 0032 rule 9). The test reads the topic back from the broker instead of looking at the `NewTopic` bean, which is only a
 * request: the admin leaves a topic that already exists with more partitions as it is and only logs it at INFO.
 *
 * It has no port, but extends [BaseApplicationServiceTest] to share the context every service test uses.
 */
class OrderEventsTopicTest(
    private val kafkaAdmin: KafkaAdmin,
) : BaseApplicationServiceTest() {
    @Test
    fun `the order events topic exists on the broker with three partitions of one replica each`() {
        val topicName = "order-events"

        val topic = kafkaAdmin.describeTopics(topicName).getValue(topicName)

        assertThat(topic.partitions()).hasSize(3)
        assertThat(topic.partitions()).allSatisfy { assertThat(it.replicas()).hasSize(1) }
    }
}
