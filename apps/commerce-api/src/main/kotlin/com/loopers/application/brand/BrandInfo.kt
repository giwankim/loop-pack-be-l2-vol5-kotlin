package com.loopers.application.brand

import com.loopers.domain.brand.Brand
import java.time.ZonedDateTime

data class BrandInfo(
    val id: Long,
    val name: String,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
) {
    companion object {
        fun from(brand: Brand): BrandInfo {
            return BrandInfo(
                id = brand.id,
                name = brand.name,
                createdAt = brand.createdAt,
                updatedAt = brand.updatedAt,
            )
        }
    }
}
