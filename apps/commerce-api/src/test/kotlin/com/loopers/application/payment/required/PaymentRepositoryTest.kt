package com.loopers.application.payment.required

import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.createPayment
import com.loopers.domain.payment.createPgTransaction
import com.loopers.domain.shared.Money
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

/**
 * Spring Data가 만든 [PaymentRepository]가 실제 MySQL에서 계약을 지키는지 확인한다. 패키지 위치의 이유는
 * [com.loopers.application.user.required.UserRepositoryTest]와 같다.
 *
 * 주문마다 결제 하나, 멱등 키마다 결제 하나, 주문을 향한 외래 키는 DB가 지키는 약속이라 여기서 본다. 제약이 실제로
 * 이름대로 만들어졌는지는 `information_schema`에서도 확인한다. 스키마를 다시 만들 때 외래 키가 지워지고 다시 생기는지는
 * [com.loopers.adapter.webapi.v1.order.OrderApiTest]의 스키마 재생성 테스트가 본다. 테스트 트랜잭션 안에서는 스키마 도구가
 * 메타데이터 잠금을 기다리기 때문이다.
 */
class PaymentRepositoryTest(
    private val paymentRepository: PaymentRepository,
) : BaseRepositoryTest() {
    @Test
    fun `a 65 digit payment amount round trips without losing digits`() {
        prepareOrder()
        val amount = Money("9".repeat(65).toBigInteger())
        paymentRepository.save(createPayment(orderId = order.id, amount = amount))
        entityManager.flushAndClear()

        assertThat(paymentRepository.findByOrderId(order.id)?.amount).isEqualTo(amount)
    }

    @Test
    fun `a saved payment reads back by its order id and by its idempotency key after flush and clear`() {
        preparePayment()
        entityManager.flushAndClear()

        val byOrderId = paymentRepository.findByOrderId(order.id)
        val byIdempotencyKey = paymentRepository.findByIdempotencyKey(payment.idempotencyKey)

        assertThat(byOrderId).isNotNull().isNotSameAs(payment)
        assertThat(byOrderId?.id).isEqualTo(payment.id)
        assertThat(byOrderId?.orderId).isEqualTo(order.id)
        assertThat(byOrderId?.idempotencyKey).isEqualTo(payment.idempotencyKey)
        assertThat(byOrderId?.pgOrderId).isEqualTo(payment.pgOrderId)
        assertThat(byOrderId?.amount).isEqualTo(order.totalAmount)
        assertThat(byOrderId?.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(byOrderId?.createdAt).isNotNull()
        assertThat(byIdempotencyKey?.id).isEqualTo(payment.id)
    }

    @Test
    fun `a conflicting transaction leaves the saved status and transaction key unchanged`() {
        preparePayment()
        val transaction = createPgTransaction(payment, status = PgStatus.PENDING)
        reconcile(listOf(transaction))
        reconcile(listOf(createPgTransaction(payment, amount = payment.amount + Money(1))))
        entityManager.flushAndClear()

        val found = paymentRepository.findByOrderId(order.id)

        assertThat(found).isNotNull().isNotSameAs(payment)
        assertThat(found?.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(found?.transactionKey).isEqualTo(transaction.transactionKey)
    }

    @Test
    fun `the lookups are null for an order without a payment and for an unknown idempotency key`() {
        val orderWithoutPayment = prepareOrder()
        preparePayment()
        entityManager.flushAndClear()

        assertThat(paymentRepository.findByOrderId(orderWithoutPayment.id)).isNull()
        assertThat(paymentRepository.findByIdempotencyKey("unknown-key")).isNull()
    }

    @Test
    fun `a deleted payment is found neither by its order id nor by its idempotency key`() {
        preparePayment()
        deletePayment()
        entityManager.flushAndClear()

        assertThat(paymentRepository.findByOrderId(order.id)).isNull()
        assertThat(paymentRepository.findByIdempotencyKey(payment.idempotencyKey)).isNull()
    }

    /** 식별자가 IDENTITY라 저장이 곧 INSERT이므로 두 번째 결제는 flush를 기다리지 않고 바로 거절된다. */
    @Test
    fun `saving a second payment for the same order violates the unique key`() {
        preparePayment()

        assertThrows<DataIntegrityViolationException> { paymentRepository.save(createPayment(order.id)) }
    }

    @Test
    fun `saving a second payment with the same idempotency key violates the unique key`() {
        preparePayment()
        val otherOrder = prepareOrder()

        assertThrows<DataIntegrityViolationException> {
            paymentRepository.save(createPayment(otherOrder.id, idempotencyKey = payment.idempotencyKey))
        }
    }

    /** 주문 행이 없는 결제는 DB가 거절한다. 외래 키는 연관이 아니라 `scalar-foreign-keys.sql`이 만든다(ADR 0014). */
    @Test
    fun `saving a payment for an order that does not exist violates the foreign key`() {
        assertThrows<DataIntegrityViolationException> { paymentRepository.save(createPayment(orderId = 999L)) }
    }

    /**
     * 열을 모두 적는다. 카드를 실수로 담는 가장 쉬운 길은 [com.loopers.domain.payment.CreditCard]를 `@Embedded`로 두는 것인데,
     * 그러면 열 이름이 `type`과 `number`라 "card"가 든 이름을 찾는 검사로는 걸리지 않는다. 열이 늘면 이 목록도 함께 고친다.
     */
    @Test
    fun `the payment table stores neither card nor conflict details`() {
        val columns = entityManager
            .createNativeQuery(
                "select column_name from information_schema.columns where table_schema = database() " +
                    "and table_name = 'payment'",
            )
            .resultList

        assertThat(columns).containsExactlyInAnyOrder(
            "id",
            "order_id",
            "idempotency_key",
            "pg_order_id",
            "amount",
            "status",
            "transaction_key",
            "created_at",
            "updated_at",
            "deleted_at",
        )
    }

    @Test
    fun `the order foreign key and both unique keys exist in the database under their names`() {
        val foreignKey = entityManager
            .createNativeQuery(
                "select referenced_table_name, referenced_column_name from information_schema.key_column_usage " +
                    "where table_schema = database() and table_name = 'payment' " +
                    "and constraint_name = 'FK_PAYMENT_ON_ORDER_ID'",
            )
            .singleResult as Array<*>
        val uniqueKeys = entityManager
            .createNativeQuery(
                "select concat(index_name, ':', column_name) from information_schema.statistics " +
                    "where table_schema = database() and table_name = 'payment' and non_unique = 0 " +
                    "and index_name <> 'PRIMARY'",
            )
            .resultList

        assertThat(foreignKey[0]).isEqualTo("orders")
        assertThat(foreignKey[1]).isEqualTo("id")
        assertThat(uniqueKeys).containsExactlyInAnyOrder(
            "UK_PAYMENT_ON_ORDER_ID:order_id",
            "UK_PAYMENT_ON_IDEMPOTENCY_KEY:idempotency_key",
        )
    }
}
