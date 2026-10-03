package com.loopers.domain.order

import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.CascadeType
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 생성 정보는 불변이다. 카탈로그의 삭제 행위를 물려받지 않는다. */
@Entity
@Table(
    name = "orders",
    indexes = [
        Index(name = "idx_orders_user_created", columnList = "user_id, created_at DESC, id DESC"),
        Index(name = "idx_orders_created", columnList = "created_at DESC, id DESC"),
    ],
    check = [
        CheckConstraint(
            constraint = "total_amount > 0 and " +
                "((status = 'DRAFT' and paid_amount is null and confirmed_at is null) or " +
                "(status = 'CONFIRMED' and paid_amount is not null and paid_amount = total_amount and confirmed_at is not null))",
        ),
    ],
)
class Order(
    @Column(nullable = false, updatable = false)
    val userId: Long,
    products: List<OrderProduct>,
) {
    init {
        if (products.isEmpty() || products.map { it.productId }.distinct().size != products.size) {
            throw InvalidOrderException("주문은 상품별로 하나씩인 품목을 포함해야 합니다.")
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @OneToMany(mappedBy = "order", cascade = [CascadeType.PERSIST])
    @OrderBy("productId ASC")
    private val lineItems: MutableList<OrderLineItem> = products.sortedBy { it.productId }
        .map { OrderLineItem(this, it) }.toMutableList()

    val items: List<OrderLineItem>
        get() = lineItems.toList()

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "total_amount", nullable = false, updatable = false))
    val totalAmount: Money = lineItems.fold(Money(0)) { total, item -> total + item.lineAmount }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: OrderStatus = OrderStatus.DRAFT
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "paid_amount"))
    var paidAmount: Money? = null
        protected set

    var confirmedAt: Instant? = null
        protected set

    /** MySQL datetime(6)와 정밀도를 맞춰 저장 전의 첫 응답과 저장 후 조회가 같다. */
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    /**
     * 확정할 수 있는 주문인지 본다. 확정 전 주문만 확정할 수 있다. application이 상품·재고·포인트를 보기 전에 불러,
     * 그 사이 잔액을 쓰거나 상품이 삭제됐어도 이미 확정된 주문이라는 거절이 앞서게 한다(ADR 0005).
     */
    fun validateConfirmable() {
        if (status == OrderStatus.CONFIRMED) {
            throw OrderAlreadyConfirmedException()
        }
    }

    /**
     * 저장된 총액으로 확정한다. 재고·포인트의 차감은 application의 같은 트랜잭션에서 이뤄진다(ADR 0003).
     * 이미 확정된 주문은 거절해 결제액·확정 시각을 다시 쓰지 않는다. application이 [validateConfirmable]로 먼저 거절하므로
     * 정상 흐름은 여기서 거절되지 않는다.
     */
    fun confirm() {
        validateConfirmable()
        paidAmount = totalAmount
        confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }
}
