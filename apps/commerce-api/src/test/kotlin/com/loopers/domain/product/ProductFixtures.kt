package com.loopers.domain.product

import com.loopers.application.product.provided.ProductAdminRegisterRequest
import com.loopers.application.product.provided.ProductAdminStockUpdateRequest
import com.loopers.application.product.provided.ProductAdminUpdateRequest
import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.Money
import com.loopers.domain.unsaved
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KInstancio.gen
import org.instancio.kotlin.KSelect.field

/**
 * 저장하지 않은 상품. 생성자를 건너뛰므로 이름·가격 규칙은 계약 테스트가 지킨다.
 * 브랜드는 다른 애그리거트라 테스트가 넘긴다. 맡기면 Instancio가 저장되지 않은 브랜드를 지어낸다.
 * 가격은 1..1,000,000,000원 전체, 재고는 100..1,000개라 품절(0)이 나오지 않고 기본 주문 수량보다 크다.
 */
fun createProduct(
    brand: Brand,
    name: String? = null,
    price: Money? = null,
    stock: Stock? = null,
): Product {
    return KInstancio.of<Product>()
        .unsaved()
        .set(field(Product::brand), brand)
        .set(field(Product::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .set(field(Product::price), price ?: Money(gen().longs().range(1, 1_000_000_000).get()))
        .set(field(Product::stock), stock ?: Stock(gen().ints().range(100, 1_000).get()))
        .create()
}

/** 상품 등록 요청. 등록 규칙을 지나고 품절이 아닌 상품을 만든다. 브랜드 ID는 다른 애그리거트라 테스트가 넘긴다. */
fun createProductAdminRegisterRequest(
    brandId: Long,
    name: String? = null,
    price: Long? = null,
    stock: Int? = null,
): ProductAdminRegisterRequest {
    return KInstancio.of<ProductAdminRegisterRequest>()
        .set(field(ProductAdminRegisterRequest::brandId), brandId)
        .set(field(ProductAdminRegisterRequest::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .set(field(ProductAdminRegisterRequest::price), price ?: gen().longs().range(1, 1_000_000_000).get())
        .set(field(ProductAdminRegisterRequest::stock), stock ?: gen().ints().range(100, 1_000).get())
        .create()
}

/** 상품 수정 요청. 수정 규칙을 지나는 이름과 가격을 가진다. */
fun createProductAdminUpdateRequest(
    name: String? = null,
    price: Long? = null,
): ProductAdminUpdateRequest {
    return KInstancio.of<ProductAdminUpdateRequest>()
        .set(field(ProductAdminUpdateRequest::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .set(field(ProductAdminUpdateRequest::price), price ?: gen().longs().range(1, 1_000_000_000).get())
        .create()
}

/** 재고 변경 요청. 품절로 맞추지 않는다. */
fun createProductAdminStockUpdateRequest(quantity: Int? = null): ProductAdminStockUpdateRequest {
    return KInstancio.of<ProductAdminStockUpdateRequest>()
        .set(field(ProductAdminStockUpdateRequest::quantity), quantity ?: gen().ints().range(100, 1_000).get())
        .create()
}
