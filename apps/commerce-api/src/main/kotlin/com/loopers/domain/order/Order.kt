package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.CascadeType
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(
    name = "orders",
    indexes = [
        Index(name = "IDX_ORDERS_ON_USER_ID_AND_CREATED_AT_AND_ID", columnList = "user_id, created_at DESC, id DESC"),
        Index(name = "IDX_ORDERS_ON_CREATED_AT_AND_ID", columnList = "created_at DESC, id DESC"),
    ],
    check = [
        // payment_method가 null이면 `payment_method = 'PG'`는 참도 거짓도 아니고, CHECK는 그런 행을 통과시킨다. 그래서 `is not null`을 함께 둔다.
        CheckConstraint(
            name = "CK_ORDERS_ON_PAYMENT_STATE",
            constraint = "(status = 'DRAFT' and payment_method is null and confirmed_at is null) or " +
                    "(status in ('PAYMENT_PENDING', 'PAYMENT_FAILED') and payment_method is not null " +
                    "and payment_method = 'PG' and confirmed_at is null) or " +
                    "(status = 'CONFIRMED' and payment_method is not null and confirmed_at is not null)",
        ),
    ],
)
@SQLRestriction("deleted_at is null")
class Order(
    @Column(nullable = false, updatable = false)
    val userId: Long,
    products: List<OrderProduct>,
) : BaseEntity() {
    @OneToMany(mappedBy = "order", cascade = [CascadeType.PERSIST])
    @OrderBy("productId ASC")
    private val _lineItems: MutableList<OrderLineItem> = products.sortedBy { it.productId }
        .map { OrderLineItem(this, it) }.toMutableList()

    val lineItems: List<OrderLineItem>
        get() = _lineItems

    val totalAmount: Money
        get() = _lineItems.fold(Money.ZERO) { total, item -> total + item.lineAmount }

    init {
        if (products.isEmpty() || products.distinctBy { it.productId }.size != products.size) {
            throw InvalidOrderException("주문은 상품별로 하나씩인 품목을 포함해야 합니다.")
        }
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: OrderStatus = OrderStatus.DRAFT
        protected set

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var paymentMethod: PaymentMethod? = null
        protected set

    val paidAmount: Money?
        get() = if (status == OrderStatus.CONFIRMED) totalAmount else null

    var confirmedAt: Instant? = null
        protected set

    fun ensureDraft() {
        if (status == OrderStatus.CONFIRMED) {
            throw OrderAlreadyConfirmedException()
        }

        if (status != OrderStatus.DRAFT) {
            throw OrderStatusConflictException("결제를 시작한 주문입니다.")
        }
    }

    fun ensurePaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw OrderStatusConflictException("결제 중인 주문이 아닙니다.")
        }
    }

    /** 포인트로 결제해 바로 확정한다. PG로 결제하는 주문은 [startPayment]로 시작한다. 포인트 결제의 길과 함께 지운다(ADR 0027). */
    fun confirm() {
        ensureDraft()

        paymentMethod = PaymentMethod.POINT
        confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }

    fun startPayment() {
        ensureDraft()

        paymentMethod = PaymentMethod.PG
        status = OrderStatus.PAYMENT_PENDING
    }

    fun completePayment() {
        ensurePaymentPending()

        confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }

    fun failPayment() {
        ensurePaymentPending()

        status = OrderStatus.PAYMENT_FAILED
    }
}
