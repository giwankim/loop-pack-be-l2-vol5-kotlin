package com.loopers.interfaces.api.v1.brand

import com.loopers.domain.brand.Brand
import java.time.ZonedDateTime

data class BrandAdminResponse(
    val id: Long,
    val name: String,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
) {
    companion object {
        fun from(brand: Brand): BrandAdminResponse {
            return BrandAdminResponse(
                id = brand.id,
                name = brand.name,
                createdAt = brand.createdAt,
                updatedAt = brand.updatedAt,
            )
        }
    }
}
