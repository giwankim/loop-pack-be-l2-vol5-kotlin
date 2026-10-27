package com.loopers.adapter.integration.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * The Kafka side of the order events adapter (ADR 0032). The application declares its own topic, so a fresh broker needs no
 * manual setup: the starter's `KafkaAdmin` creates every `NewTopic` bean while the context starts. A topic that already exists
 * is not recreated; the admin only adds partitions when it has fewer than declared.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderEventsProperties::class)
class OrderEventsKafkaConfig {
    /** One replica, because there is one broker: the local compose and the test container both run a single node. */
    @Bean
    fun orderEventsTopic(properties: OrderEventsProperties): NewTopic {
        return TopicBuilder.name(properties.topic)
            .partitions(properties.partitions)
            .replicas(1)
            .build()
    }
}
