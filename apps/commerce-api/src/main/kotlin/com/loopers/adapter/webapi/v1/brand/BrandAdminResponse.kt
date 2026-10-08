package com.loopers.adapter.webapi.v1.brand

import com.loopers.domain.brand.Brand
import java.time.Instant

data class BrandAdminResponse(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
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
