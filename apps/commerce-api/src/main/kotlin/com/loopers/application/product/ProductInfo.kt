package com.loopers.application.product

import com.loopers.domain.product.Product
import java.time.ZonedDateTime

/**
 * 상품 응답 모델. 상품은 브랜드를 연관으로 건너 읽으므로 트랜잭션 안에서 값으로 옮겨 돌려준다.
 * 고객 응답(`Customer`)은 상품 조회 티켓에서 더한다.
 */
object ProductInfo {
    /** 관리자가 보는 상품. 재고 수량과 등록·수정 시각을 담는다. */
    data class Admin(
        val id: Long,
        val brandId: Long,
        val name: String,
        val price: Long,
        val stock: Int,
        val createdAt: ZonedDateTime,
        val updatedAt: ZonedDateTime,
    ) {
        companion object {
            fun from(product: Product): Admin =
                Admin(
                    id = product.id,
                    brandId = product.brand.id,
                    name = product.name.value,
                    price = product.price.amount,
                    stock = product.stock.quantity,
                    createdAt = product.createdAt,
                    updatedAt = product.updatedAt,
                )
        }
    }
}
