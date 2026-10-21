package com.loopers.domain.payment

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.SQLRestriction

/**
 * The commerce side's record of paying for one draft order through the PG (`CONTEXT.md` Payment). It reflects what is known
 * about the PG transaction, not the transaction itself.
 *
 * Payment is its own aggregate and refers to its order by ID only (ADR 0021). Unique constraints allow at most one payment per
 * order and one per idempotency key.
 *
 * Without a JPA association, `scalar-foreign-keys.sql` adds the order foreign key `FK_PAYMENT_ON_ORDER_ID` (ADR 0014).
 * Hibernate drops tables in alphabetical order and leaves keys it does not map in place, so `orders` would go before `payment`
 * and MySQL would refuse. `scalar-foreign-keys-drop.sql` drops the key first (points-orders design 8.2).
 *
 * A payment starts as [PaymentStatus.UNKNOWN]. Its order ID, idempotency key, amount, and PG order ID never change after
 * creation. Card details are passed through to the PG and never stored.
 *
 * Answers from the PG come in only through [reconcile] and [reject], and only those two move the status forward. An answer
 * that does not match this payment leaves it untouched and is returned as [ReconcileResult.Conflicted] (ADR 0021 rule 1).
 * Calls that get no answer from the PG never reach this class.
 *
 * Nothing deletes payments yet, but as an aggregate root Payment supports soft deletion, and queries treat a deleted payment
 * as missing (ADR 0016).
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "UK_PAYMENT_ON_ORDER_ID", columnNames = ["order_id"]),
        UniqueConstraint(name = "UK_PAYMENT_ON_IDEMPOTENCY_KEY", columnNames = ["idempotency_key"]),
    ],
)
@SQLRestriction("deleted_at is null")
class Payment(
    @Column(nullable = false, updatable = false)
    val orderId: Long,

    /** Sent by the customer when starting the payment. Must not be blank or exceed [IDEMPOTENCY_KEY_MAX_LENGTH] characters. */
    @Column(nullable = false, updatable = false, length = IDEMPOTENCY_KEY_MAX_LENGTH)
    val idempotencyKey: String,

    /** The amount charged through the PG. Must be at least 1 won. */
    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "amount", nullable = false, updatable = false))
    val amount: Money,
) : BaseEntity() {
    /**
     * The order identifier sent to the PG (`CONTEXT.md` pgOrderId): `LP-ORD-` plus the order ID, zero-padded to at least 10
     * digits. Longer IDs are kept whole. Built with `padStart` rather than `String.format("%010d")`, whose digits depend on the
     * JVM's default locale.
     */
    @Column(nullable = false, updatable = false)
    val pgOrderId: String = "LP-ORD-" + orderId.toString().padStart(10, '0')

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: PaymentStatus = PaymentStatus.UNKNOWN
        protected set

    /**
     * The PG's identifier for this payment's PG transaction (`CONTEXT.md` transactionKey), or null until it is known. It is set
     * from the first matching PG transaction, and any other key reported later is a conflict. A payment failed by a pre-create
     * rejection never had a PG transaction, so its key stays null.
     */
    var transactionKey: String? = null
        protected set

    init {
        if (idempotencyKey.isBlank()) {
            throw InvalidPaymentException("멱등 키는 공백일 수 없습니다.")
        }

        if (idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw InvalidPaymentException("멱등 키는 ${IDEMPOTENCY_KEY_MAX_LENGTH}자 이하여야 합니다.")
        }

        if (amount <= Money.ZERO) {
            throw InvalidPaymentException("결제 금액은 1원 이상이어야 합니다.")
        }
    }

    /**
     * Reconciles this payment against the PG transactions the PG reports for its PG order ID (ADR 0021 rule 2). PG callbacks,
     * the reconciliation API, and accepted create responses all end up here. The first rule that applies wins:
     *
     * 1. No transactions: nothing new is learned, so nothing changes. Not finding one is not a failure.
     * 2. Several transactions: there is no telling which one is this payment's, so it is a conflict.
     * 3. A different PG order ID, amount, or already known transaction key is a conflict.
     * 4. A payment failed by a pre-create rejection conflicts with any transaction, whatever its status, because the rejection
     *    proved none existed (ADR 0022).
     * 5. A finished payment conflicts with the opposite outcome and stays as it is on the same outcome or `PENDING`.
     * 6. A pending payment stays as it is on another `PENDING`.
     * 7. Otherwise the payment records the transaction key and moves to the reported status.
     */
    fun reconcile(transactions: List<PgTransaction>): ReconcileResult {
        if (transactions.isEmpty()) {
            return ReconcileResult.Unchanged
        }

        if (transactions.size > 1) {
            return ReconcileResult.Conflicted(ConflictReason.MULTIPLE_TRANSACTIONS)
        }

        val transaction = transactions.single()
        val reason = conflictReasonFor(transaction.pgOrderId, transaction.amount)
        if (reason != null) {
            return ReconcileResult.Conflicted(reason)
        }

        if (transactionKey != null && transaction.transactionKey != transactionKey) {
            return ReconcileResult.Conflicted(ConflictReason.TRANSACTION_KEY_MISMATCH)
        }

        // Reconciling records the transaction key whenever it finishes a payment, so a failed payment without one must have been
        // failed by a pre-create rejection.
        if (status == PaymentStatus.FAILED && transactionKey == null) {
            return ReconcileResult.Conflicted(ConflictReason.TRANSACTION_AFTER_REJECTION)
        }

        val reportedStatus = transaction.status.toPaymentStatus()
        if (status.isFinished && reportedStatus.isFinished && reportedStatus != status) {
            return ReconcileResult.Conflicted(ConflictReason.RESULT_MISMATCH)
        }

        if (status.isFinished || reportedStatus == status) {
            return ReconcileResult.Unchanged
        }

        transactionKey = transaction.transactionKey

        return moveTo(reportedStatus)
    }

    /**
     * Applies a [PgRejection] (ADR 0022). A different PG order ID or amount is a conflict, as in [reconcile]. Otherwise an
     * `UNKNOWN` payment fails and a `FAILED` one stays as it is. A `PENDING` or `SUCCESS` payment already knows a PG transaction
     * exists, so a rejection saying there is none conflicts with it. With no transaction, there is no transaction key to record.
     */
    fun reject(rejection: PgRejection): ReconcileResult {
        val reason = conflictReasonFor(rejection.pgOrderId, rejection.amount)
        if (reason != null) {
            return ReconcileResult.Conflicted(reason)
        }

        return when (status) {
            PaymentStatus.UNKNOWN -> moveTo(PaymentStatus.FAILED)
            PaymentStatus.FAILED -> ReconcileResult.Unchanged
            PaymentStatus.PENDING, PaymentStatus.SUCCESS -> ReconcileResult.Conflicted(ConflictReason.RESULT_MISMATCH)
        }
    }

    /**
     * Why an answer's PG order ID or amount conflicts with this payment, or null if both match. The PG order ID is checked
     * first.
     */
    private fun conflictReasonFor(pgOrderId: String, amount: Money): ConflictReason? {
        if (pgOrderId != this.pgOrderId) {
            return ConflictReason.PG_ORDER_ID_MISMATCH
        }

        if (amount != this.amount) {
            return ConflictReason.AMOUNT_MISMATCH
        }

        return null
    }

    /** Assumes a forward transition: `UNKNOWN` to any other status, or `PENDING` to `SUCCESS` or `FAILED`. */
    private fun moveTo(to: PaymentStatus): ReconcileResult {
        val from = status
        status = to

        return ReconcileResult.Changed(from, to)
    }

    private fun PgStatus.toPaymentStatus(): PaymentStatus {
        return when (this) {
            PgStatus.PENDING -> PaymentStatus.PENDING
            PgStatus.SUCCESS -> PaymentStatus.SUCCESS
            PgStatus.FAILED -> PaymentStatus.FAILED
        }
    }

    companion object {
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 64
    }
}
