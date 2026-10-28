package com.loopers.adapter.integration.kafka

import com.loopers.support.test.BaseApplicationServiceTest
import org.apache.kafka.clients.producer.ProducerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.ProducerFactory

/**
 * The producer that publishes order confirmed events asks for `acks=all` (`kafka.yml`, order-events design 5). With one broker
 * this is no stronger than `acks=1`; it states the intent rather than proving replication.
 *
 * The test reads the factory's own configuration, which holds only what was set explicitly. The client's default has been
 * `all` since Kafka 3.0, so a producer's effective value would pass even without the setting.
 */
class OrderEventsProducerTest(
    private val producerFactory: ProducerFactory<Any, Any>,
) : BaseApplicationServiceTest() {
    @Test
    fun `the producer factory is configured with acks all`() {
        assertThat(producerFactory.configurationProperties).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
    }
}
