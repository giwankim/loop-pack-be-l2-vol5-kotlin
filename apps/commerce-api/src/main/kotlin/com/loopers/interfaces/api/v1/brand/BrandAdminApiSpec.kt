package com.loopers.interfaces.api.v1.brand

import com.loopers.application.brand.BrandRegisterRequest
import com.loopers.application.brand.BrandUpdateRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.SliceResponse
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
        summary = "브랜드 목록 조회",
        description = "삭제되지 않은 브랜드를 최신 등록순으로 한 조각 조회합니다. 총 개수는 세지 않고 다음 조각이 있는지만 알려줍니다.",
    )
    fun getBrands(
        @Schema(name = "page", description = "0부터 세는 조각 번호. 기본값 0")
        page: Int?,
        @Schema(name = "size", description = "한 조각이 담는 최대 개수. 1에서 100 사이이며 기본값 20")
        size: Int?,
    ): ApiResponse<SliceResponse<BrandAdminResponse>>

    @Operation(
        summary = "브랜드 상세 조회",
        description = "ID로 삭제되지 않은 브랜드를 조회합니다.",
    )
    fun getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        brandId: Long,
    ): ApiResponse<BrandAdminResponse>

    @Operation(
        summary = "브랜드 수정",
        description = "브랜드의 이름을 바꿉니다. 이름 규칙은 등록과 같습니다. 거절되면 기존 이름이 그대로 남습니다.",
    )
    fun update(
        @Schema(name = "브랜드 ID", description = "수정할 브랜드의 ID")
        brandId: Long,
        request: BrandUpdateRequest,
    ): ApiResponse<BrandAdminResponse>

    @Operation(
        summary = "브랜드 삭제",
        description = "브랜드에 삭제 시각을 찍습니다. 삭제된 브랜드는 목록·상세·수정·삭제에서 없는 브랜드입니다.",
    )
    fun delete(
        @Schema(name = "브랜드 ID", description = "삭제할 브랜드의 ID")
        brandId: Long,
    ): ApiResponse<Any>
}
