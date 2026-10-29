package com.loopers.adapter.consumer

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter

/**
 * The listener side of consuming `order-events` (ADR 0033 rule 7). `modules:kafka` owns the connection and serialization;
 * the promises a listener makes (ack mode, error handler, concurrency, group, offset reset) belong to the streamer, next to
 * its listener. The group and offset reset are in `application.yml`; the rest is here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderEventsProperties::class)
class OrderEventsListenerConfig {
    companion object {
        /** The bean name of the record listener factory the order events listener names as its container factory. */
        const val ORDER_EVENTS_LISTENER = "orderEventsListenerContainerFactory"

        /** One consumer per partition: commerce-api declares the topic with 3 (order-events design 7). */
        private const val CONCURRENCY = 3
    }

    /**
     * Values are read as bytes and converted by the listener adapter to the listener method's parameter type, so a body that
     * cannot be read fails in the adapter's converter, before the listener method is called, and reaches the error handler as
     * spring-kafka's `ConversionException`, not inside `poll()`. The deserializer is set here rather than left to `kafka.yml`,
     * because the converter depends on it.
     *
     * `MANUAL_IMMEDIATE` commits a record's offset on the consumer thread when the listener calls `acknowledge()`, after its
     * transaction has committed (ADR 0033 rule 6). That only holds if the consumer never commits on its own, so auto-commit is
     * turned off here too. `kafka.yml`'s `enable-auto-commit` sits in the raw `properties` map, which passes its keys to Kafka
     * as written, and Kafka's key is `enable.auto.commit`. The container would turn auto-commit off anyway when the factory
     * leaves the key out, but the factory states it.
     *
     * [CommonContainerStoppingErrorHandler] stops all three consumers on any exception from the converter or the listener, an
     * unprocessable event and a transient failure alike, leaving the record's offset uncommitted; that policy is provisional
     * (ADR 0033 rule 5). The listener's ERROR line says which it was: a refusal (`Refused an order confirmed event that cannot
     * be processed`), which a restart meets again, or a failure (`Could not record an order confirmed event`), which a restart
     * records once its cause is gone. A record the converter could not read has neither, only spring-kafka's own line.
     */
    @Bean(ORDER_EVENTS_LISTENER)
    fun orderEventsListenerContainerFactory(
        kafkaProperties: KafkaProperties,
        converter: ByteArrayJacksonJsonMessageConverter,
    ): ConcurrentKafkaListenerContainerFactory<String, ByteArray> {
        val consumerConfig = HashMap(kafkaProperties.buildConsumerProperties())
            .apply {
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            }

        return ConcurrentKafkaListenerContainerFactory<String, ByteArray>().apply {
            setConsumerFactory(DefaultKafkaConsumerFactory(consumerConfig))
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            setCommonErrorHandler(CommonContainerStoppingErrorHandler())
            setConcurrency(CONCURRENCY)
            setRecordMessageConverter(converter)
        }
    }
}
