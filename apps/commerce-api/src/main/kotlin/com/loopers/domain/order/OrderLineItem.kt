package com.loopers.domain.order

import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Check

@Entity
@Table(
    name = "order_line_item",
    uniqueConstraints = [UniqueConstraint(name = "uk_order_line_item_product", columnNames = ["order_id", "product_id"])],
)
@Check(constraints = "unit_price > 0 and quantity > 0 and line_amount > 0")
class OrderLineItem internal constructor(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "order_id",
        nullable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_order_line_item_order"),
    )
    private val order: Order,
    product: OrderProduct,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(name = "product_id", nullable = false, updatable = false)
    val productId: Long = product.productId

    @Column(name = "product_name", nullable = false, updatable = false, length = 100)
    val productName: String = product.productName

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "unit_price", nullable = false, updatable = false))
    val unitPrice: Money = product.unitPrice

    @Column(nullable = false, updatable = false)
    val quantity: Int = product.quantity

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "line_amount", nullable = false, updatable = false))
    val lineAmount: Money = unitPrice * quantity
}
