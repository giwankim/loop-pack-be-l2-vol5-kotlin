package com.loopers.domain.product

import com.loopers.domain.BaseEntity
import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction

/**
 * 브랜드 아래 파는 상품. 브랜드는 만들 때 정해지고 바뀌지 않으며, 상품은 브랜드의 상태를 바꾸지 않는다.
 * [brand]는 읽기용 참조이고 브랜드는 자기 저장소를 가진 별도 애그리거트다.
 */
@Entity
@Table(name = "product")
@SQLRestriction("deleted_at is null")
class Product(
    brand: Brand,
    name: String,
    price: Money,
    stock: Stock,
) : BaseEntity() {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false, updatable = false)
    val brand: Brand = brand

    /** 앞뒤 공백을 뗀 이름. 비어 있지 않고 [NAME_MAX_LENGTH]자 이하다. */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name.trim()
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "price", nullable = false))
    var price: Money = price
        protected set

    @Embedded
    @AttributeOverride(name = "quantity", column = Column(name = "stock_quantity", nullable = false))
    var stock: Stock = stock
        protected set

    init {
        validateName(this.name)
        validatePrice(price)
    }

    /** 이름과 가격을 바꾼다. 브랜드는 바뀌지 않는다. */
    fun update(name: String, price: Money) {
        val trimmed = name.trim()
        validateName(trimmed)
        validatePrice(price)
        this.name = trimmed
        this.price = price
    }

    /** 앞뒤 공백을 뗀 이름이 지켜야 할 규칙. 생성과 수정이 같은 규칙을 쓴다. */
    private fun validateName(trimmed: String) {
        if (trimmed.isEmpty()) {
            throw InvalidNameException("상품 이름은 공백일 수 없습니다.")
        }
        if (trimmed.length > NAME_MAX_LENGTH) {
            throw InvalidNameException("상품 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    /** 가격이 지켜야 할 범위. 생성과 수정이 같은 규칙을 쓴다. */
    private fun validatePrice(price: Money) {
        if (price < MIN_PRICE || price > MAX_PRICE) {
            throw InvalidPriceException("상품 가격은 ${MIN_PRICE.amount}원 이상 ${MAX_PRICE.amount}원 이하여야 합니다.")
        }
    }

    /** 재고를 최종 수량으로 맞춘다. 수량이 음수면 거절하고 기존 재고를 그대로 둔다. */
    fun updateStock(quantity: Int) {
        stock = Stock(quantity)
    }

    /** 재고가 0이면 품절이다. */
    fun isSoldOut(): Boolean = stock.isEmpty()

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val MIN_PRICE_AMOUNT = 1L
        const val MAX_PRICE_AMOUNT = 1_000_000_000L
        val MIN_PRICE = Money(MIN_PRICE_AMOUNT)
        val MAX_PRICE = Money(MAX_PRICE_AMOUNT)
    }
}
