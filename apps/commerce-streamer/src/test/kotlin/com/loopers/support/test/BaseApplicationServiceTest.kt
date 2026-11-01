package com.loopers.support.test

import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecordRequest
import com.loopers.application.purchaseactivity.provided.PurchaseActivityRecorder
import com.loopers.application.purchaseactivity.required.ProcessedOrderConfirmationRepository
import com.loopers.application.purchaseactivity.required.PurchaseActivityRepository
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.PurchaseActivity
import com.loopers.domain.purchaseactivity.createProcessedOrderConfirmation
import com.loopers.domain.purchaseactivity.createPurchaseActivity
import com.loopers.domain.purchaseactivity.createPurchaseActivityRecordRequest
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
 * is cached anyway, instead of starting a second one.
 *
 * A state the recording port makes is prepared through it: [prepareRecordedEvent] records an event, which writes both roots
 * (ADR 0012). The repository tests stand where commerce-api's `BaseRepositoryTest` does: they check the repositories below
 * the port, so they do not prepare through it. That would fail every repository test whenever recording breaks, and it could
 * not prepare an order's activities one at a time. So `prepare<Type>` saves one entity fixture through its repository, under
 * the names `BaseRepositoryTest` uses, and the change helpers save explicitly instead of relying on dirty checking.
 *
 * Every full context imports the broker, and none starts without it: the `test` profile of `kafka.yml` has no broker address,
 * so the order events listener's container cannot build its consumers from the literal `${BOOTSTRAP_SERVERS}`. One that
 * declares a topic, as [BaseConsumerTest]'s do, fails earlier still, because the profile turns on
 * `spring.kafka.admin.fail-fast`, as in commerce-api. In this base's own context the container listens to `order-events`,
 * which nothing creates, so it reads nothing.
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

    @Autowired
    private lateinit var purchaseActivityRecorder: PurchaseActivityRecorder

    /** The processed order confirmation prepared last. */
    protected lateinit var processedOrderConfirmation: ProcessedOrderConfirmation

    /** The purchase activity prepared last. */
    protected lateinit var purchaseActivity: PurchaseActivity

    /**
     * An order confirmed event recorded through [PurchaseActivityRecorder]: one processed order confirmation and one purchase
     * activity per item. The recorder returns only whether it recorded, so this returns the request it recorded, which a
     * test sends again, or sends with one value changed, to meet the recorded fact. It sets no entity field.
     */
    protected fun prepareRecordedEvent(
        eventId: String? = null,
        orderId: Long? = null,
        confirmedAt: Instant? = null,
        items: List<PurchaseActivityRecordRequest.Item>? = null,
    ): PurchaseActivityRecordRequest {
        val request = createPurchaseActivityRecordRequest(eventId, orderId, confirmedAt, items)
        purchaseActivityRecorder.record(request)

        return request
    }

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
