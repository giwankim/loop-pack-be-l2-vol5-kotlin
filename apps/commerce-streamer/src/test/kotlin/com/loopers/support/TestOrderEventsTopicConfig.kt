package com.loopers.support

import com.loopers.adapter.consumer.OrderEventsProperties
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.config.TopicBuilder

/**
 * Declares the topic a consumer test reads, under the name its class sets in `order-events.topic`. The streamer never creates
 * its topic (commerce-api does), so the test does, and the context's `KafkaAdmin` creates it on start. `BaseConsumerTest`
 * imports it; it is top-level because Spring Framework 7 treats a nested configuration class in a test's hierarchy as
 * default configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestOrderEventsTopicConfig {
    companion object {
        /** commerce-api declares `order-events` with 3 partitions (order-events design 7); each test topic matches it. */
        const val PARTITIONS = 3
    }

    @Bean
    fun testOrderEventsTopic(orderEventsProperties: OrderEventsProperties): NewTopic {
        return TopicBuilder.name(orderEventsProperties.topic)
            .partitions(PARTITIONS)
            .replicas(1)
            .build()
    }
}
