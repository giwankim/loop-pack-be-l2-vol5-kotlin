package com.loopers.support.test

import com.loopers.testcontainers.KafkaTestContainersConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional

/**
 * The base of the streamer's provided port tests and of the tests that read its configuration. It starts the full context on
 * a real MySQL and Kafka, and a test transaction wraps the port's, so each test rolls back. The `@Import` is the same as
 * [com.loopers.CommerceStreamerContextTest]'s, so they share one cached context. The base carries every common test
 * annotation (ADR 0012); it holds no `prepare` helper until a test needs one.
 *
 * Every full context imports the broker. One that declares a topic, as [BaseConsumerTest]'s do, cannot start without it: the
 * `test` profile of `kafka.yml` has no broker address and turns on `spring.kafka.admin.fail-fast`, as in commerce-api. This
 * base's own context declares no topic and starts no listener container under `test`, so for now it would start without it.
 */
@SpringBootTest
@Import(
    MySqlTestContainersConfig::class,
    KafkaTestContainersConfig::class,
)
@Transactional
abstract class BaseApplicationServiceTest
