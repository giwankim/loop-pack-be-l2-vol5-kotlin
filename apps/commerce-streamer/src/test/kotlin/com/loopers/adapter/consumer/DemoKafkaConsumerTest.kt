package com.loopers.adapter.consumer

import com.loopers.support.test.BaseApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.config.KafkaListenerEndpointRegistry

/**
 * The template's demo listener stays in the streamer, but the `test` profile keeps its container from starting, so no test
 * context consumes its topic (purchase activity design 5). The container has no listener id, so the test finds it by topic.
 */
class DemoKafkaConsumerTest(
    private val registry: KafkaListenerEndpointRegistry,
) : BaseApplicationServiceTest() {
    @Test
    fun `the demo listener is registered but its container does not start in the test profile`() {
        val container = registry.listenerContainers.single { "demo.internal.topic-v1" in it.containerProperties.topics.orEmpty() }

        assertThat(container.isRunning).isFalse()
    }
}
