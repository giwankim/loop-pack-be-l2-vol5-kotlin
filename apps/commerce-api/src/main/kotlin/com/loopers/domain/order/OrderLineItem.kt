package com.loopers.domain.order

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 주문에 딸린 품목. 주문을 거쳐서만 읽고, [delete]를 부르지 않는다(ADR 0016).
 * 삭제 필터를 두지 않는다. 주문이 품목을 엔티티 그래프로 조인해 읽을 때 대상의 `@SQLRestriction`도 조인에 붙어,
 * 걸러진 품목이 [Order.totalAmount]가 세는 품목에서 빠지기 때문이다.
 *
 * `product_id` 인덱스는 상품 외래 키가 쓴다. 유일 제약은 `order_id`로 시작해 이 외래 키를 받치지 못한다.
 */
@Entity
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "UK_ORDER_LINE_ITEM_ON_ORDER_ID_AND_PRODUCT_ID", columnNames = ["order_id", "product_id"]),
    ],
    indexes = [Index(name = "IDX_ORDER_LINE_ITEM_ON_PRODUCT_ID", columnList = "product_id")],
    check = [
        CheckConstraint(name = "CK_ORDER_LINE_ITEM_ON_UNIT_PRICE_POSITIVE", constraint = "unit_price > 0"),
        CheckConstraint(name = "CK_ORDER_LINE_ITEM_ON_QUANTITY_POSITIVE", constraint = "quantity > 0"),
    ],
)
class OrderLineItem internal constructor(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, updatable = false, foreignKey = ForeignKey(name = "FK_ORDER_LINE_ITEM_ON_ORDER_ID"))
    private val order: Order,
    product: OrderProduct,
) : BaseEntity() {
    @Column(nullable = false, updatable = false)
    val productId: Long = product.productId

    @Column(nullable = false, updatable = false, length = 100)
    val productName: String = product.productName

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "unit_price", nullable = false, updatable = false))
    val unitPrice: Money = product.unitPrice

    @Column(nullable = false, updatable = false)
    val quantity: Int = product.quantity

    /** 단가 × 수량. 같은 행의 두 열에서 바로 나오므로 저장하지 않는다. 넘침은 주문이 총액을 셀 때 생성에서 거절된다. */
    val lineAmount: Money
        get() = unitPrice * quantity
}
