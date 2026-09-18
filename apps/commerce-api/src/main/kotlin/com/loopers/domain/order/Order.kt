package com.loopers.domain.order

import com.loopers.domain.shared.IdempotencyKey
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.CascadeType
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
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Check
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 생성 정보는 불변이다. 카탈로그의 삭제 행위를 물려받지 않는다. */
@Entity
@Table(
    name = "orders",
    uniqueConstraints = [UniqueConstraint(name = "uk_orders_user_creation_key", columnNames = ["user_id", "creation_key"])],
    indexes = [
        Index(name = "idx_orders_user_created", columnList = "user_id, created_at DESC, id DESC"),
        Index(name = "idx_orders_created", columnList = "created_at DESC, id DESC"),
    ],
)
@Check(
    constraints = "total_amount > 0 and ((status = 'DRAFT' and paid_amount is null and confirmed_at is null) or " +
        "(status = 'CONFIRMED' and paid_amount is not null and paid_amount = total_amount and confirmed_at is not null))",
)
class Order(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,
    @Column(
        name = "creation_key",
        nullable = false,
        updatable = false,
        columnDefinition = IdempotencyKey.COLUMN_DEFINITION,
    )
    val creationKey: String,
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

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null
        protected set

    /** MySQL datetime(6)와 정밀도를 맞춰 첫 응답도 저장 후 재생과 같다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    /**
     * 저장된 총액으로 확정한다. 재고·포인트의 차감은 application의 같은 트랜잭션에서 이뤄진다(ADR 0003).
     * 이미 확정된 주문은 거절해 결제액·확정 시각을 다시 쓰지 않는다. 확정 결과의 재생은 application이 먼저 처리하므로
     * 정상 흐름은 이 거절에 닿지 않는다.
     */
    fun confirm() {
        if (status == OrderStatus.CONFIRMED) {
            throw InvalidOrderException("이미 확정된 주문입니다.")
        }
        paidAmount = totalAmount
        confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }
}
