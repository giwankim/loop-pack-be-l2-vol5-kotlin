package com.loopers.interfaces.api.v1.brand

import com.loopers.domain.brand.Brand

/** 고객 브랜드 응답. 같은 브랜드를 읽지만 관리자와 달리 등록·수정 시각은 내보내지 않는다(설계 5.7). */
data class BrandResponse(
    val id: Long,
    val name: String,
) {
    companion object {
        fun from(brand: Brand): BrandResponse =
            BrandResponse(
                id = brand.id,
                name = brand.name,
            )
    }
}
