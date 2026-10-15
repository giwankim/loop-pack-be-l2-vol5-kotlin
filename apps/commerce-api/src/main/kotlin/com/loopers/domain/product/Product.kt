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
import jakarta.persistence.ForeignKey
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction

/**
 * 브랜드 아래 파는 상품. 브랜드는 만들 때 정해지고 바뀌지 않으며, 상품은 브랜드의 상태를 바꾸지 않는다.
 * [brand]는 읽기용 참조이고 브랜드는 자기 저장소를 가진 별도 애그리거트다.
 *
 * `brand_id` 인덱스는 브랜드 삭제의 연쇄가 그 브랜드의 상품만, id 차례로 잠그게 한다. 잠금 읽기는 훑은 행을 모두 잠그므로,
 * 인덱스가 없으면 상품 표 전체가 연쇄의 커밋까지 잠긴다. 잠금은 훑는 인덱스의 차례로 걸리고, 이 인덱스는 끝에 붙은 기본 키 차례로
 * 읽힌다(ADR 0018). 외래 키도 이 인덱스를 쓴다.
 *
 * `(brand_id, price, id DESC)` 인덱스는 브랜드 필터 + 가격 낮은순의 목록을 받친다. 목록의 차례가 `price ASC, id DESC`라
 * 인덱스를 차례대로 읽어 정렬 없이 첫 페이지에서 멈춘다. 보조 인덱스는 끝에 기본 키를 오름차순으로 붙이므로 `id DESC`를
 * 적지 않으면 같은 가격의 차례가 거꾸로 되어 정렬이 남는다(카탈로그 설계 5.38).
 *
 * 단독 인덱스는 복합 인덱스의 앞부분처럼 보여도 지우면 안 된다. 지우면 외래 키와 연쇄의 잠금 읽기가 복합 인덱스로 옮겨 가고,
 * 연쇄가 상품을 가격 차례로 잠근다. `ProductQueryPerformanceApiTest`의 연쇄 가드가 그것을 막는다.
 */
@Entity
@Table(
    indexes = [
        Index(name = "IDX_PRODUCT_ON_BRAND_ID", columnList = "brand_id"),
        Index(name = "IDX_PRODUCT_ON_BRAND_ID_AND_PRICE_AND_ID", columnList = "brand_id, price, id DESC"),
    ],
)
@SQLRestriction("deleted_at is null")
class Product(
    brand: Brand,
    name: String,
    price: Money,
    stock: Int,
) : BaseEntity() {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, updatable = false, foreignKey = ForeignKey(name = "FK_PRODUCT_ON_BRAND_ID"))
    var brand: Brand = brand
        protected set

    /** 받은 그대로의 이름. 공백뿐이지 않고 [NAME_MAX_LENGTH]자 이하다. */
    @Column(nullable = false, length = NAME_MAX_LENGTH)
    var name: String = name
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
        checkName(name)
        checkPrice(price)
        checkStock(stock)
    }

    /** 이름과 가격을 바꾼다. 브랜드는 바뀌지 않는다. 하나라도 거절되면 둘 다 기존 값으로 남는다. */
    fun update(name: String, price: Money) {
        checkName(name)
        checkPrice(price)

        this.name = name
        this.price = price
    }

    /** 재고를 최종 수량으로 맞춘다. 수량이 음수면 거절하고 기존 재고를 그대로 둔다. */
    fun updateStock(quantity: Int) {
        checkStock(quantity)

        stock = quantity
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

    /** 이름이 지켜야 할 규칙. 생성과 수정이 같은 검사를 쓴다. */
    private fun checkName(name: String) {
        if (name.isBlank()) {
            throw InvalidNameException("상품 이름은 공백일 수 없습니다.")
        }

        if (name.length > NAME_MAX_LENGTH) {
            throw InvalidNameException("상품 이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    /** 재고가 지켜야 할 하한. 생성과 재고 변경이 같은 규칙을 쓴다. */
    private fun checkStock(stock: Int) {
        if (stock < 0) {
            throw InvalidStockException("재고는 0 이상이어야 합니다.")
        }
    }

    /** 가격이 지켜야 할 범위. 생성과 수정이 같은 규칙을 쓴다. */
    private fun checkPrice(price: Money) {
        if (price < MIN_PRICE || price > MAX_PRICE) {
            throw InvalidPriceException("상품 가격은 ${MIN_PRICE.amount}원 이상 ${MAX_PRICE.amount}원 이하여야 합니다.")
        }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val MIN_PRICE_AMOUNT = 1L
        const val MAX_PRICE_AMOUNT = 1_000_000_000L
        val MIN_PRICE = Money(MIN_PRICE_AMOUNT)
        val MAX_PRICE = Money(MAX_PRICE_AMOUNT)
    }
}
