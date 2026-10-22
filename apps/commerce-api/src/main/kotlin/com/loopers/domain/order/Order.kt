package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.CascadeType
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Embedded
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
        CheckConstraint(name = "CK_ORDERS_ON_PAID_AMOUNT_POSITIVE", constraint = "paid_amount > 0"),
        CheckConstraint(
            name = "CK_ORDERS_ON_PAYMENT_STATE",
            constraint = "(status = 'DRAFT' and paid_amount is null and confirmed_at is null) or " +
                    "(status = 'CONFIRMED' and paid_amount is not null and confirmed_at is not null)",
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

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "paid_amount", precision = 65, scale = 0))
    var paidAmount: Money? = null
        protected set

    var confirmedAt: Instant? = null
        protected set

    fun ensureDraft() {
        if (status != OrderStatus.DRAFT) {
            throw OrderAlreadyConfirmedException()
        }
    }

    fun confirm() {
        ensureDraft()

        paidAmount = totalAmount
        confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }
}
