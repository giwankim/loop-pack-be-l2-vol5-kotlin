package com.loopers.adapter.consumer

import com.loopers.adapter.consumer.OrderEventsListenerConfig.Companion.ORDER_EVENTS_LISTENER
import com.loopers.support.test.BaseApplicationServiceTest
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.ContainerProperties.AckMode

/**
 * The record listener factory the streamer consumes `order-events` with (ADR 0033 rules 5 to 7, purchase activity design 5).
 *
 * The consumer settings are read from the factory's consumer factory, which holds only what was set explicitly, so a client
 * default cannot stand in for a missing setting. Concurrency has no getter on the factory, so the container settings are read
 * from a container the factory creates; it is never started.
 *
 * A listener can still override the factory: `@KafkaListener` uses its `id` as the group unless `idIsGroup` is false. Only a
 * listener's own running container shows the group it joined.
 */
class OrderEventsListenerConfigTest(
    @Qualifier(ORDER_EVENTS_LISTENER)
    private val factory: ConcurrentKafkaListenerContainerFactory<*, *>,
) : BaseApplicationServiceTest() {
    @Test
    fun `the order events factory's consumers never commit on their own and read values as bytes`() {
        val config = checkNotNull(factory.consumerFactory).configurationProperties

        assertThat(config)
            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
    }

    @Test
    fun `the order events factory's consumers join the streamer's group from the earliest offset and never create a topic`() {
        val config = checkNotNull(factory.consumerFactory).configurationProperties

        assertThat(config)
            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "commerce-streamer")
            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false")
    }

    @Test
    fun `an order events container acknowledges each record as it is called and runs three consumers`() {
        val container = factory.createContainer("order-events")

        assertThat(container.containerProperties.ackMode).isEqualTo(AckMode.MANUAL_IMMEDIATE)
        assertThat(container.concurrency).isEqualTo(3)
    }
}
