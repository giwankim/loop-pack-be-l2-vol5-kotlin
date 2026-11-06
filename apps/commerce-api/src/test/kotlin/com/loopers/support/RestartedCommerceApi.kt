package com.loopers.support

import com.loopers.CommerceApiApplication
import com.loopers.testcontainers.KafkaTestContainersConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.logging.LoggingApplicationListener
import org.springframework.context.ConfigurableApplicationContext

/**
 * Starts commerce-api once more on this JVM's MySQL, Redis and Kafka and the test PG at [pgBaseUrl], hands it to [block], and closes it
 * afterwards: a restarted process. It starts from the production application class with the `test` profile and the same Testcontainers
 * configurations as the bases. The containers are static, so it gets the same database, Redis and broker, and the
 * `DynamicPropertyRegistrar` beans those configurations declare still supply the connection details outside the test framework,
 * through `spring-boot-testcontainers`'s autoconfiguration.
 *
 * Overrides go in as command-line arguments, since a builder's `properties` are defaults that the profile's files win over:
 * - `pg.base-url` is the first context's test PG, so its stubs and request journal carry over.
 * - `spring.jpa.hibernate.ddl-auto=none`: the `test` profile's `create` would recreate the tables and erase what the first context
 *   left. A production restart doesn't recreate the schema either.
 * - `server.port` and `management.server.port` are 0.
 * - `spring.main.banner-mode=off`: printed after a scene's INFO line, the banner would read as that line's continuation in
 *   `scripts/scene-lines`.
 *
 * [args] come after these, so a scene can add its own, such as `order-events.recovery.order-ids`, or replace one. Logging is one per
 * JVM and the first context owns it, so `LoggingApplicationListener` is left out: closing the restart would otherwise mark logback
 * uninitialized, and the next test context would configure it again. The first context's spies and `@EnableTestPg`'s PG order ID
 * generator are not in the restart, so a scene starts no payment in it.
 */
fun restartedCommerceApi(pgBaseUrl: String, vararg args: String, block: (ConfigurableApplicationContext) -> Unit) {
    val application = SpringApplicationBuilder(
        CommerceApiApplication::class.java,
        MySqlTestContainersConfig::class.java,
        RedisTestContainersConfig::class.java,
        KafkaTestContainersConfig::class.java,
    )
        .profiles("test")
        .build()
    application.setListeners(application.listeners.filterNot { it is LoggingApplicationListener })

    application.run(
        "--pg.base-url=$pgBaseUrl",
        "--spring.jpa.hibernate.ddl-auto=none",
        "--server.port=0",
        "--management.server.port=0",
        "--spring.main.banner-mode=off",
        *args,
    ).use(block)
}
