package com.loopers.application.product

import com.loopers.domain.product.Product
import java.time.ZonedDateTime

/**
 * 상품 응답 모델. 상품은 브랜드를 연관으로 건너 읽으므로 트랜잭션 안에서 값으로 옮겨 돌려준다.
 * 고객·관리자 구분은 두지 않는다. 어느 필드를 내보낼지는 interfaces의 응답 DTO가 고른다(설계 5.7).
 */
data class ProductInfo(
    val id: Long,
    val brandId: Long,
    val brandName: String,
    val name: String,
    val price: Long,
    val stock: Int,
    val soldOut: Boolean,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
) {
    companion object {
        fun from(product: Product): ProductInfo =
            ProductInfo(
                id = product.id,
                brandId = product.brand.id,
                brandName = product.brand.name,
                name = product.name,
                price = product.price.amount,
                stock = product.stock.quantity,
                soldOut = product.isSoldOut(),
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
            )
    }
}
