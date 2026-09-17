package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductInfo
import java.time.ZonedDateTime

data class ProductAdminResponse(
    val id: Long,
    val brandId: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
) {
    companion object {
        fun from(info: ProductInfo): ProductAdminResponse {
            return ProductAdminResponse(
                id = info.id,
                brandId = info.brandId,
                name = info.name,
                price = info.price,
                stock = info.stock,
                createdAt = info.createdAt,
                updatedAt = info.updatedAt,
            )
        }
    }
}
