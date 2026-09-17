package com.loopers.interfaces.api.brand

import com.loopers.application.brand.BrandInfo
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
            fun from(info: BrandInfo): BrandResponse {
                return BrandResponse(
                    id = info.id,
                    name = info.name,
                    createdAt = info.createdAt,
                    updatedAt = info.updatedAt,
                )
            }
        }
    }
}
