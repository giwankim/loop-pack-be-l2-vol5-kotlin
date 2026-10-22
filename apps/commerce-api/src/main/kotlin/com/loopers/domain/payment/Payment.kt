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
 * order, one per idempotency key, and one per PG order ID. The PG order ID is not derived from the order ID, so its uniqueness
 * no longer follows from the order's, and step 3's callback finds payments by it (ADR 0029 rule 3).
 *
 * Without a JPA association, `scalar-foreign-keys.sql` adds the order foreign key `FK_PAYMENT_ON_ORDER_ID` (ADR 0014).
 * Hibernate drops tables in alphabetical order and leaves keys it does not map in place, so `orders` would go before `payment`
 * and MySQL would refuse. `scalar-foreign-keys-drop.sql` drops the key first (points-orders design 8.2).
 *
 * A payment is created only by [start] and begins as [PaymentStatus.UNKNOWN]. Its order ID, idempotency key, amount, and PG
 * order ID never change after creation. Card details are passed through to the PG and never stored.
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
        UniqueConstraint(name = "UK_PAYMENT_ON_PG_ORDER_ID", columnNames = ["pg_order_id"]),
    ],
)
@SQLRestriction("deleted_at is null")
class Payment private constructor(
    @Column(nullable = false, updatable = false)
    val orderId: Long,

    /**
     * Sent by the customer when starting the payment: 1 to [IDEMPOTENCY_KEY_MAX_LENGTH] visible ASCII characters with no
     * whitespace. Keys are compared exactly, so keys that differ only in case are different keys (ADR 0025 rule 2). The column
     * says so with `utf8mb4_bin`; under the server's default collation the unique key and the lookup would ignore case.
     */
    @Column(
        nullable = false,
        updatable = false,
        columnDefinition = "varchar($IDEMPOTENCY_KEY_MAX_LENGTH) character set utf8mb4 collate utf8mb4_bin",
    )
    val idempotencyKey: String,

    /** The amount charged through the PG. Must be at least 1 won. */
    @Embedded
    @AttributeOverride(
        name = "amount",
        column = Column(name = "amount", nullable = false, updatable = false, precision = 65, scale = 0),
    )
    val amount: Money,

    /**
     * The order identifier sent to the PG (`CONTEXT.md` pgOrderId), minted by a [PgOrderIdGenerator] when the payment starts
     * (ADR 0029). It is not derived from the order ID.
     */
    @Column(nullable = false, updatable = false)
    val pgOrderId: String,
) : BaseEntity() {
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
        checkIdempotencyKey(idempotencyKey)

        if (amount <= Money.ZERO) {
            throw InvalidPaymentException("결제 금액은 1원 이상이어야 합니다.")
        }
    }

    /**
     * Whether [idempotencyKey] is the key this payment was started with, so a confirmation carrying it replays this payment
     * (ADR 0025 rule 3). Keys are compared exactly, as the column does, so keys that differ only in case are different keys.
     * The order slice asks this instead of reading the key, because it reads nothing of a payment but its status (ADR 0028
     * rule 5).
     */
    fun isStartedWith(idempotencyKey: String): Boolean {
        return idempotencyKey == this.idempotencyKey
    }

    /**
     * Reconciles this payment against a PG transaction the PG reports for its PG order ID (ADR 0021 rule 2). PG callbacks, the
     * reconciliation API, and accepted create responses all end up here. The first rule that applies wins:
     *
     * 1. A different PG order ID, amount, or already known transaction key is a conflict.
     * 2. A payment failed by a pre-create rejection conflicts with any transaction, whatever its status, because the rejection
     *    proved none existed (ADR 0022).
     * 3. A finished payment conflicts with the opposite outcome and stays as it is on the same outcome or `PENDING`.
     * 4. A pending payment stays as it is on another `PENDING`.
     * 5. Otherwise the payment records the transaction key and moves to the reported status.
     *
     * Finding no transaction and finding several never get here. Both depend on the PG's answer alone, so the lookup by PG
     * order ID handles them (ADR 0028).
     */
    fun reconcile(transaction: PgTransaction): ReconcileResult {
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

        /** Visible ASCII, `!` (0x21) to `~` (0x7E): no space, no control character, nothing outside ASCII. */
        private val IDEMPOTENCY_KEY_CHARACTERS = '!'..'~'

        /**
         * Rejects an idempotency key that breaks the key rule (ADR 0025 rule 2) with an [InvalidPaymentException] naming the broken
         * part. Creating a payment checks the key here, and so does the web boundary before the key reaches a lookup: under
         * `utf8mb4_bin`'s PAD SPACE a key with a trailing space would find the payment of the key without it.
         */
        fun checkIdempotencyKey(idempotencyKey: String) {
            if (idempotencyKey.isBlank()) {
                throw InvalidPaymentException("멱등 키는 공백일 수 없습니다.")
            }

            if (idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
                throw InvalidPaymentException("멱등 키는 ${IDEMPOTENCY_KEY_MAX_LENGTH}자 이하여야 합니다.")
            }

            if (!idempotencyKey.all { it in IDEMPOTENCY_KEY_CHARACTERS }) {
                throw InvalidPaymentException("멱등 키는 공백 없는 보이는 ASCII 문자여야 합니다.")
            }
        }

        /**
         * Starts paying for order [orderId] through the PG. This is the only way to create a payment, so callers cannot choose
         * the PG order ID: [pgOrderIdGenerator] mints it (ADR 0029 rule 2). Callers pass the arguments by name.
         */
        fun start(orderId: Long, idempotencyKey: String, amount: Money, pgOrderIdGenerator: PgOrderIdGenerator): Payment {
            return Payment(
                orderId = orderId,
                idempotencyKey = idempotencyKey,
                amount = amount,
                pgOrderId = pgOrderIdGenerator.generate(),
            )
        }
    }
}
