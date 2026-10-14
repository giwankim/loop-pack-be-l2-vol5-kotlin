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

/**
 * 생성 정보는 불변이다. 다른 엔티티처럼 [BaseEntity]를 상속해 식별자와 생성·수정 시각을 물려받는다(ADR 0016).
 * 생성 시각은 저장할 때 찍히고, 확정 시각은 [confirm]이 스스로 찍는다.
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

    /** 주문 품목. `productId` 오름차순이며 읽기 전용이다. 매핑되는 쪽은 [_lineItems]라 JPA 속성 이름은 `_lineItems`다. */
    val lineItems: List<OrderLineItem>
        get() = _lineItems

    /** 품목 금액의 합. 생성 뒤 바뀌지 않는 품목에서 바로 나오므로 저장하지 않는다. */
    val totalAmount: Money
        get() = _lineItems.fold(Money.ZERO) { total, item -> total + item.lineAmount }

    // 총액을 세려면 품목이 있어야 하므로 _lineItems 초기화 뒤에 둔다.
    init {
        if (products.isEmpty() || products.map { it.productId }.distinct().size != products.size) {
            throw InvalidOrderException("주문은 상품별로 하나씩인 품목을 포함해야 합니다.")
        }

        // 저장하지 않는 총액을 생성에서 한 번 센다. 넘치는 주문은 저장 전에 거절되어, 읽을 때마다 넘치는 주문이 남지 않는다.
        totalAmount
    }

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

    /**
     * 확정 전(DRAFT) 주문인지 본다. 확정 전 주문만 확정할 수 있다. application이 상품·재고·포인트를 보기 전에 불러,
     * 그 사이 잔액을 쓰거나 상품이 삭제됐어도 이미 확정된 주문이라는 거절이 앞서게 한다(ADR 0005).
     */
    fun ensureDraft() {
        if (status != OrderStatus.DRAFT) {
            throw OrderAlreadyConfirmedException()
        }
    }

    /**
     * 총액으로 확정한다. 재고·포인트의 차감은 application의 같은 트랜잭션에서 이뤄진다(ADR 0003).
     * 이미 확정된 주문은 거절해 결제액·확정 시각을 다시 쓰지 않는다. application이 [ensureDraft]로 먼저 거절하므로
     * 정상 흐름은 여기서 거절되지 않는다.
     */
    fun confirm() {
        ensureDraft()

        paidAmount = totalAmount
        confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        status = OrderStatus.CONFIRMED
    }
}
