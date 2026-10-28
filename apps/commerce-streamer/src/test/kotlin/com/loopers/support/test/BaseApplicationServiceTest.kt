package com.loopers.support.test

import com.loopers.application.purchaseactivity.required.ProcessedOrderConfirmationRepository
import com.loopers.application.purchaseactivity.required.PurchaseActivityRepository
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.PurchaseActivity
import com.loopers.domain.purchaseactivity.createProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.createPurchaseActivity
import com.loopers.testcontainers.KafkaTestContainersConfig
import com.loopers.testcontainers.MySqlTestContainersConfig
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The base of the streamer's provided port tests, its repository tests, and the tests that read its configuration. It starts
 * the full context on a real MySQL and Kafka, and a test transaction wraps the port's, so each test rolls back. The `@Import`
 * is the same as [com.loopers.CommerceStreamerContextTest]'s, so they share one cached context. The base carries every common
 * test annotation (ADR 0012).
 *
 * The streamer has no JPA slice base like commerce-api's `BaseRepositoryTest`. Its repository tests run in this context, which
 * is cached anyway, instead of starting a second one. No provided port records anything yet, so `prepare<Type>` saves an
 * entity fixture through its repository, and the change helpers save explicitly instead of relying on dirty checking.
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
abstract class BaseApplicationServiceTest {
    @Autowired
    protected lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var processedOrderConfirmationRepository: ProcessedOrderConfirmationRepository

    @Autowired
    private lateinit var purchaseActivityRepository: PurchaseActivityRepository

    /** The processed order confirmation prepared last. */
    protected lateinit var processedOrderConfirmation: ProcessedOrderConfirmation

    /** The purchase activity prepared last. */
    protected lateinit var purchaseActivity: PurchaseActivity

    /** A processed order confirmation saved through its repository. */
    protected fun prepareProcessedOrderConfirmation(
        eventId: String? = null,
        orderId: Long? = null,
        confirmedAt: Instant? = null,
    ): ProcessedOrderConfirmation {
        return processedOrderConfirmationRepository
            .save(createProcessedOrderConfirmation(eventId, orderId, confirmedAt))
            .also { processedOrderConfirmation = it }
    }

    /** A purchase activity saved through its repository. */
    protected fun preparePurchaseActivity(
        orderId: Long? = null,
        productId: Long? = null,
        confirmedAt: Instant? = null,
    ): PurchaseActivity {
        return purchaseActivityRepository
            .save(createPurchaseActivity(orderId, productId, confirmedAt = confirmedAt))
            .also { purchaseActivity = it }
    }

    /** Stamps the deletion time and saves explicitly. Nothing deletes a confirmation; the repository tests use it. */
    protected fun deleteProcessedOrderConfirmation(
        processedOrderConfirmation: ProcessedOrderConfirmation = this.processedOrderConfirmation,
    ) {
        processedOrderConfirmationRepository.save(processedOrderConfirmation.apply { delete() })
    }

    /** The same as [deleteProcessedOrderConfirmation]. */
    protected fun deletePurchaseActivity(purchaseActivity: PurchaseActivity = this.purchaseActivity) {
        purchaseActivityRepository.save(purchaseActivity.apply { delete() })
    }
}
