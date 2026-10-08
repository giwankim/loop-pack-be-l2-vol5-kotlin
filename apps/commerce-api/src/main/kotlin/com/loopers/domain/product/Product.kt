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
import org.hibernate.annotations.SQLRestriction

/**
 * 브랜드 아래 파는 상품. 브랜드는 만들 때 정해지고 바뀌지 않으며, 상품은 브랜드의 상태를 바꾸지 않는다.
 * [brand]는 읽기용 참조이고 브랜드는 자기 저장소를 가진 별도 애그리거트다.
 */
@Entity
@SQLRestriction("deleted_at is null")
class Product(
    brand: Brand,
    name: String,
    price: Money,
    stock: Int,
) : BaseEntity() {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, updatable = false)
    val brand: Brand = brand

    /** 받은 그대로의 이름. 공백뿐이지 않고 [NAME_MAX_LENGTH]자 이하다. */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    var name: String = validatedName(name)
        protected set

    @Embedded
    @AttributeOverride(name = "amount", column = Column(name = "price", nullable = false))
    var price: Money = price
        protected set

    /** 팔 수 있는 남은 수량. 0 아래로 내려가지 않는다. */
    @Column(nullable = false)
    var stock: Int = stock
        protected set

    init {
        validatePrice(price)
        validateStock(stock)
    }

    /** 이름과 가격을 바꾼다. 브랜드는 바뀌지 않는다. 하나라도 거절되면 둘 다 기존 값으로 남는다. */
    fun update(name: String, price: Money) {
        val validated = validatedName(name)
        validatePrice(price)
        this.name = validated
        this.price = price
    }

    /** 가격이 지켜야 할 범위. 생성과 수정이 같은 규칙을 쓴다. */
    private fun validatePrice(price: Money) {
        if (price < MIN_PRICE || price > MAX_PRICE) {
            throw InvalidPriceException("상품 가격은 ${MIN_PRICE.amount}원 이상 ${MAX_PRICE.amount}원 이하여야 합니다.")
        }
    }

    /** 재고를 최종 수량으로 맞춘다. 수량이 음수면 거절하고 기존 재고를 그대로 둔다. */
    fun updateStock(quantity: Int) {
        validateStock(quantity)
        stock = quantity
    }

    /** 재고가 지켜야 할 하한. 생성과 재고 변경이 같은 규칙을 쓴다. */
    private fun validateStock(stock: Int) {
        if (stock < 0) {
            throw InvalidStockException("재고는 0 이상이어야 합니다.")
        }
    }

    /** 구매 수량만큼 재고를 차감한다. 수량이 잘못되거나 부족하면 기존 재고를 유지한다. */
    fun deductStock(quantity: Int) {
        if (quantity <= 0) {
            throw InvalidStockException("차감 수량은 1개 이상이어야 합니다.")
        }

        if (quantity > stock) {
            throw InsufficientStockException()
        }

        stock -= quantity
    }

    /** 재고가 0이면 품절이다. */
    fun isSoldOut(): Boolean {
        return stock == 0
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val MIN_PRICE_AMOUNT = 1L
        const val MAX_PRICE_AMOUNT = 1_000_000_000L
        val MIN_PRICE = Money(MIN_PRICE_AMOUNT)
        val MAX_PRICE = Money(MAX_PRICE_AMOUNT)

        /**
         * 이름이 지켜야 할 규칙. 생성과 수정이 같은 검사를 쓴다.
         * `name` 프로퍼티의 초기값이 부르는 자리라 받은 값을 그대로 돌려주고, 인스턴스 메서드가 아니라 여기에 둔다.
         */
        private fun validatedName(name: String): String {
            if (name.isBlank()) {
                throw InvalidNameException("상품 이름은 공백일 수 없습니다.")
            }

            if (name.length > NAME_MAX_LENGTH) {
                throw InvalidNameException("상품 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
            }

            return name
        }
    }
}
