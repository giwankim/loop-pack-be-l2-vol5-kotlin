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

/**
 * 생성 정보는 불변이다. 다른 엔티티처럼 [BaseEntity]를 상속해 식별자와 생성·수정 시각을 물려받는다(ADR 0016).
 * 생성 시각은 저장할 때 찍힌다.
 *
 * 주문을 삭제하는 기능은 없지만 애그리거트 루트라 논리 삭제를 기본으로 하여, 삭제된 주문은 없는 주문이다.
 * 품목은 필터를 두지 않고 주문을 따른다([OrderLineItem]).
 */
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

    /** 결제수단이 PG인 주문인지. 결제 애그리거트는 이런 주문에만 있다(ADR 0028 규칙 1). */
    val paysThroughPg: Boolean
        get() = paymentMethod == PaymentMethod.PG

    /** 상품 ID마다 그 품목의 수량. 확정은 이 수량으로 재고를 예약하고, 결제가 실패하면 같은 수량을 돌려준다(ADR 0026 규칙 2). */
    val productQuantities: Map<Long, Int>
        get() = _lineItems.associate { it.productId to it.quantity }

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

    fun startPayment() {
        ensureDraft()

        paymentMethod = PaymentMethod.PG
        status = OrderStatus.PAYMENT_PENDING
    }

    /**
     * Confirms the order at [confirmedAt], cut to the microseconds the column keeps so that the instant held here is the one
     * a later read returns. The caller decides the instant (ADR 0032 rule 8).
     */
    fun completePayment(confirmedAt: Instant) {
        ensurePaymentPending()

        this.confirmedAt = confirmedAt.truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }

    fun failPayment() {
        ensurePaymentPending()

        status = OrderStatus.PAYMENT_FAILED
    }
}
