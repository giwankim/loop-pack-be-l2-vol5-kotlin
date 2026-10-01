package com.loopers.domain.product

import com.loopers.application.product.ProductAdminRegisterRequest
import com.loopers.application.product.ProductAdminStockUpdateRequest
import com.loopers.application.product.ProductAdminUpdateRequest
import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.Money
import org.instancio.Instancio

/** 상품 이름. 1..[Product.NAME_MAX_LENGTH]자의 대문자 영문이다. */
fun productName(): String = Instancio.gen().string().length(1, Product.NAME_MAX_LENGTH).upperCase().get()

/** 상품 가격. [Product.MIN_PRICE_AMOUNT]..[Product.MAX_PRICE_AMOUNT]원 전체에서 뽑는다. */
fun productPrice(): Money = Money(Instancio.gen().longs().range(Product.MIN_PRICE_AMOUNT, Product.MAX_PRICE_AMOUNT).get())

/** 상품 재고. 100..1,000개라 품절(0)이 나오지 않고, 기본 주문 수량보다 크다. */
fun productStock(): Stock = Stock(Instancio.gen().ints().range(100, 1_000).get())

/** 저장하지 않은 상품. 진짜 생성자를 거친다. 브랜드는 다른 애그리거트라 테스트가 넘긴다. */
fun createProduct(
    brand: Brand,
    name: String = productName(),
    price: Money = productPrice(),
    stock: Stock = productStock(),
): Product = Product(brand = brand, name = name, price = price, stock = stock)

/** 상품 등록 요청. 등록 규칙을 지나고 품절이 아닌 상품을 만든다. 브랜드 ID는 다른 애그리거트라 테스트가 넘긴다. */
fun createProductAdminRegisterRequest(
    brandId: Long,
    name: String = productName(),
    price: Long = productPrice().amount,
    stock: Int = productStock().quantity,
): ProductAdminRegisterRequest = ProductAdminRegisterRequest(brandId = brandId, name = name, price = price, stock = stock)

/** 상품 수정 요청. 수정 규칙을 지나는 이름과 가격을 가진다. */
fun createProductAdminUpdateRequest(
    name: String = productName(),
    price: Long = productPrice().amount,
): ProductAdminUpdateRequest = ProductAdminUpdateRequest(name = name, price = price)

/** 재고 변경 요청. 품절로 맞추지 않는다. */
fun createProductAdminStockUpdateRequest(quantity: Int = productStock().quantity): ProductAdminStockUpdateRequest =
    ProductAdminStockUpdateRequest(quantity)
