package com.loopers.adapter.integration.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.kafka.config.TopicBuilder
import org.springframework.scheduling.annotation.EnableAsync
import java.util.concurrent.Executors

/**
 * The Kafka side of the order events adapter (ADR 0032). The application declares its own topic, so a fresh broker needs no
 * manual setup: the starter's `KafkaAdmin` creates every `NewTopic` bean while the context starts. A topic that already exists
 * is not recreated; the admin only adds partitions when it has fewer than declared.
 *
 * It also enables `@Async` for [OrderConfirmedKafkaRelay] and gives it an executor of its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(OrderEventsProperties::class)
class OrderEventsKafkaConfig {
    companion object {
        /** The bean name of the executor the relay runs on. */
        const val ORDER_EVENTS_EXECUTOR = "orderEventsExecutor"
    }

    /** One replica, because there is one broker: the local compose and the test container both run a single node. */
    @Bean
    fun orderEventsTopic(properties: OrderEventsProperties): NewTopic {
        return TopicBuilder.name(properties.topic)
            .partitions(properties.partitions)
            .replicas(1)
            .build()
    }

    /**
     * Runs each relay on a virtual thread of its own (ADR 0032 rule 3). It has no queue, so a broker that stops answering piles
     * up no backlog in memory, and the hand-off after TX B commits never meets a full queue's `TaskRejectedException`. The switch
     * `spring.threads.virtual.enabled` stays off: it would move Tomcat and the listener containers onto virtual threads too.
     *
     * Being an `Executor` bean, it makes Boot back off from its own `applicationTaskExecutor`, which nothing in commerce-api
     * uses: no MVC handler returns an async type and no other method is `@Async`. Nothing waits for running relays when the
     * context closes, so a relay that has not published by then is part of the publish gap (order-events design 6).
     */
    @Bean(ORDER_EVENTS_EXECUTOR)
    fun orderEventsExecutor(): AsyncTaskExecutor {
        return TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())
    }
}
