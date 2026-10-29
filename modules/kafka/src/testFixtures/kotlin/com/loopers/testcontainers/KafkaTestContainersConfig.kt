package com.loopers.testcontainers

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * The Kafka broker for integration tests. The container starts once per JVM, and its address goes only into the
 * `Environment` of a context that imports this configuration.
 *
 * Component scanning skips a top-level `@TestConfiguration`, so a test that needs the broker names it in `@Import`. Unlike a
 * system property the address does not leak into other contexts. The `test` profile of `kafka.yml` sets no address, so a
 * context that forgets the import keeps the literal `${BOOTSTRAP_SERVERS}`, whatever order the tests run in. The admin client
 * rejects it (`Invalid url in bootstrap.servers: ${BOOTSTRAP_SERVERS}`), and the profile's `spring.kafka.admin.fail-fast`
 * turns that into a failed context instead of an error in the log. A context that declares a topic, as commerce-api does,
 * therefore cannot start without this import.
 *
 * The image is `apache/kafka-native`, which Testcontainers' [KafkaContainer] supports alongside `apache/kafka`. It publishes
 * no major.minor tag, so the version is pinned in full (order-events design 8). The single node means one replica per
 * partition.
 */
@TestConfiguration(proxyBeanMethods = false)
class KafkaTestContainersConfig {
    companion object {
        private val kafkaContainer: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka-native:4.4.0"))
            .apply {
                start()
            }
    }

    @Bean
    fun kafkaContainerProperties(): DynamicPropertyRegistrar {
        return DynamicPropertyRegistrar { registry ->
            registry.add("spring.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
        }
    }
}
