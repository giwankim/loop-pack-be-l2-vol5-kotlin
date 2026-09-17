package com.loopers.interfaces.api.brand

import com.loopers.domain.brand.Brand
import java.time.ZonedDateTime

object BrandAdminV1Dto {
    data class RegisterRequest(
        val name: String,
    )

    data class BrandResponse(
        val id: Long,
        val name: String,
        val createdAt: ZonedDateTime,
        val updatedAt: ZonedDateTime,
    ) {
        companion object {
            fun from(brand: Brand): BrandResponse {
                return BrandResponse(
                    id = brand.id,
                    name = brand.name,
                    createdAt = brand.createdAt,
                    updatedAt = brand.updatedAt,
                )
            }
        }
    }
}
