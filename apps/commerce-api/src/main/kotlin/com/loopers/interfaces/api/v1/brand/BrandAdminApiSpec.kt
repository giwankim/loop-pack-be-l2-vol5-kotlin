package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandRegisterRequest
import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Brand Admin V1 API", description = "관리자 브랜드 API 입니다. ADMIN 역할이 필요합니다.")
interface BrandAdminApiSpec {
    @Operation(
        summary = "브랜드 등록",
        description = "이름으로 브랜드를 등록합니다. 이름은 공백뿐일 수 없고 앞뒤 공백을 포함해 100자 이하이며 " +
            "앞뒤 공백을 뗀 값이 저장됩니다. 삭제되지 않은 브랜드와 겹칠 수 없습니다.",
    )
    fun register(
        request: BrandRegisterRequest,
    ): ApiResponse<BrandAdminResponse>

    @Operation(
        summary = "브랜드 상세 조회",
        description = "ID로 삭제되지 않은 브랜드를 조회합니다.",
    )
    fun getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        brandId: Long,
    ): ApiResponse<BrandAdminResponse>
}
