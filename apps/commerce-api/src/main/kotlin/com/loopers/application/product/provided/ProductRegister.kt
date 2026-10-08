package com.loopers.application.product.provided

import jakarta.validation.Valid

/** 관리자의 상품 쓰기. 등록·수정·재고 수정·삭제를 맡는다. */
interface ProductRegister {
    /** 브랜드가 없거나 삭제됐으면 `BRAND_NOT_FOUND`를 던진다. */
    fun register(@Valid request: ProductAdminRegisterRequest): ProductInfo

    /** 없는 상품이면 `PRODUCT_NOT_FOUND`를 던진다. 브랜드는 바뀌지 않는다. */
    fun update(id: Long, @Valid request: ProductAdminUpdateRequest): ProductInfo

    /** 없는 상품이면 `PRODUCT_NOT_FOUND`를 던진다. 재고를 최종 수량으로 맞춘다. */
    fun updateStock(id: Long, @Valid request: ProductAdminStockUpdateRequest): ProductInfo

    /** 없는 상품이면 `PRODUCT_NOT_FOUND`를 던진다. 이미 삭제된 상품도 없는 상품이다. */
    fun delete(id: Long)
}
