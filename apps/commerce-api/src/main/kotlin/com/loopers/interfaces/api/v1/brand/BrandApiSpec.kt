package com.loopers.interfaces.api.v1.brand

import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Brand V1 API", description = "고객 브랜드 API 입니다. 식별이 필요 없습니다.")
interface BrandApiSpec {
    @Operation(
        summary = "브랜드 상세 조회",
        description = "ID로 삭제되지 않은 브랜드를 조회합니다. 없거나 삭제된 브랜드는 없는 브랜드입니다.",
    )
    fun getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        brandId: Long,
    ): ApiResponse<BrandResponse>
}
