package com.loopers.adapter.integration.kafka

import com.loopers.support.test.BaseApplicationServiceTest
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    /** The relay's two limits (ADR 0035 rule 6). One batch can't stall on the defaults of 120 s and 60 s. */
    @Test
    fun `the producer factory is configured with the relay's delivery timeout and max block`() {
        val producerConfiguration = producerFactory.configurationProperties

        assertThat(producerConfiguration).containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "21000")
        assertThat(producerConfiguration).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000")
    }

    @Test
    fun `the relay's limits leave the retries, the request timeout and the linger as they were`() {
        val producerConfiguration = producerFactory.configurationProperties

        assertThat(producerConfiguration).containsEntry(ProducerConfig.RETRIES_CONFIG, 3)
        assertThat(producerConfiguration).containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "20000")
        assertThat(producerConfiguration).doesNotContainKey(ProducerConfig.LINGER_MS_CONFIG)
    }

    /**
     * Kafka refuses a delivery timeout below `linger.ms + request.timeout.ms` when it constructs the client, so the floor is the real
     * client's answer rather than a sum this test repeats. 20,005 ms is the client default linger of 5 ms plus the 20 s request timeout.
     * The configured 21 s starts, and one millisecond under the floor doesn't.
     */
    @Test
    fun `the real client starts with the configured limits and refuses a delivery timeout under the floor`() {
        startAndCloseClient(producerFactory.configurationProperties)
        startAndCloseClient(producerFactory.configurationProperties + (ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to "20005"))

        val exception = assertThrows<KafkaException> {
            startAndCloseClient(producerFactory.configurationProperties + (ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to "20004"))
        }

        assertThat(exception).rootCause().isInstanceOf(ConfigException::class.java)
        assertThat(exception).rootCause().hasMessageContaining(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)
    }

    /** A client of its own: the factory's `createProducer` hands out one shared producer, which a test mustn't close or configure. */
    private fun startAndCloseClient(producerConfiguration: Map<String, Any>) {
        KafkaProducer(producerConfiguration, ByteArraySerializer(), ByteArraySerializer()).close()
    }
}
