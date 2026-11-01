package com.loopers.application.purchaseactivity.required

import com.loopers.domain.purchaseactivity.createPurchaseActivity
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import jakarta.persistence.PersistenceException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Instant

/**
 * Checks that the [PurchaseActivityRepository] Spring Data builds keeps its contract on a real MySQL. The unique key and the
 * quantity CHECK are what the database keeps, so they are checked here: by a save or an update that breaks each, and by name
 * in `information_schema`. The base is the service base, for the reason in
 * [ProcessedOrderConfirmationRepositoryTest].
 */
class PurchaseActivityRepositoryTest(
    private val purchaseActivityRepository: PurchaseActivityRepository,
) : BaseApplicationServiceTest() {
    /** A seventh fractional digit goes in, and the time reads back to the microsecond: the column keeps what the entity holds. */
    @Test
    fun `a saved activity reads back by its order id with its product, quantity and confirmation time after flush and clear`() {
        preparePurchaseActivity(confirmedAt = Instant.parse("2026-10-27T01:02:03.123456789Z"))
        entityManager.flushAndClear()

        val found = purchaseActivityRepository.findAllByOrderId(purchaseActivity.orderId)

        assertThat(found).singleElement().satisfies({ activity ->
            assertThat(activity).isNotSameAs(purchaseActivity)
            assertThat(activity.id).isEqualTo(purchaseActivity.id)
            assertThat(activity.productId).isEqualTo(purchaseActivity.productId)
            assertThat(activity.quantity).isEqualTo(purchaseActivity.quantity)
            assertThat(activity.confirmedAt).isEqualTo(Instant.parse("2026-10-27T01:02:03.123456Z"))
        })
    }

    /** Different products of one order and the same product in different orders are all separate activities. */
    @Test
    fun `findAllByOrderId gives every activity of the order and none of another order`() {
        val first = preparePurchaseActivity(orderId = 1, productId = 1)
        val second = preparePurchaseActivity(orderId = 1, productId = 2)
        preparePurchaseActivity(orderId = 2, productId = 1)
        entityManager.flushAndClear()

        val found = purchaseActivityRepository.findAllByOrderId(1)

        assertThat(found.map { it.id }).containsExactlyInAnyOrder(first.id, second.id)
    }

    @Test
    fun `a deleted activity is not found by its order id while the rest of the order is`() {
        val deleted = preparePurchaseActivity(orderId = 1, productId = 1)
        val kept = preparePurchaseActivity(orderId = 1, productId = 2)
        deletePurchaseActivity(deleted)
        entityManager.flushAndClear()

        val found = purchaseActivityRepository.findAllByOrderId(1)

        assertThat(found.map { it.id }).containsExactly(kept.id)
    }

    /** The ID is IDENTITY, so a save is an INSERT at once, and the second one is refused without waiting for a flush. */
    @Test
    fun `saving a second activity for the same order and product violates the unique key`() {
        preparePurchaseActivity(orderId = 1, productId = 1)

        val exception = assertThrows<DataIntegrityViolationException> {
            purchaseActivityRepository.save(createPurchaseActivity(orderId = 1, productId = 1))
        }

        assertThat(exception).rootCause().hasMessageContaining("UK_PURCHASE_ACTIVITY_ON_ORDER_ID_AND_PRODUCT_ID")
    }

    /**
     * The entity refuses such a quantity before the database sees it, so SQL goes around the entity: what refuses this update
     * is the CHECK itself. MySQL's 3819 is `ER_CHECK_CONSTRAINT_VIOLATED`.
     */
    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `the database refuses a non-positive quantity`(quantity: Int) {
        preparePurchaseActivity()
        entityManager.flushAndClear()

        val exception = assertThrows<PersistenceException> {
            entityManager
                .createNativeQuery("update purchase_activity set quantity = :quantity where id = :id")
                .setParameter("quantity", quantity)
                .setParameter("id", purchaseActivity.id)
                .executeUpdate()
        }

        assertThat(exception).rootCause().isInstanceOfSatisfying(SQLException::class.java) { cause ->
            assertThat(cause.errorCode).isEqualTo(3819)
        }
    }

    /** Every constraint on the table is listed, so a foreign key would show: IDs are values here (ADR 0033 rule 1). */
    @Test
    fun `the unique key and the check exist in the database under their names and the table has no foreign key`() {
        val constraints = entityManager
            .createNativeQuery(
                "select concat(constraint_name, ':', constraint_type) from information_schema.table_constraints " +
                    "where table_schema = database() and table_name = 'purchase_activity'",
            )
            .resultList
        val uniqueKeys = entityManager
            .createNativeQuery(
                "select concat(index_name, ':', group_concat(column_name order by seq_in_index)) " +
                    "from information_schema.statistics where table_schema = database() and table_name = 'purchase_activity' " +
                    "and non_unique = 0 and index_name <> 'PRIMARY' group by index_name",
            )
            .resultList

        assertThat(constraints).containsExactlyInAnyOrder(
            "PRIMARY:PRIMARY KEY",
            "UK_PURCHASE_ACTIVITY_ON_ORDER_ID_AND_PRODUCT_ID:UNIQUE",
            "CK_PURCHASE_ACTIVITY_ON_QUANTITY_POSITIVE:CHECK",
        )
        assertThat(uniqueKeys).containsExactly("UK_PURCHASE_ACTIVITY_ON_ORDER_ID_AND_PRODUCT_ID:order_id,product_id")
    }
}
