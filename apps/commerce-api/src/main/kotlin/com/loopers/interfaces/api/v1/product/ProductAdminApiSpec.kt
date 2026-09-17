package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductRegisterRequest
import com.loopers.interfaces.api.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Product Admin V1 API", description = "관리자 상품 API 입니다. ADMIN 역할이 필요합니다.")
interface ProductAdminApiSpec {
    @Operation(
        summary = "상품 등록",
        description = "삭제되지 않은 브랜드 아래 상품을 등록합니다. 이름은 공백뿐일 수 없고 앞뒤 공백을 포함해 100자 이하이며 " +
            "앞뒤 공백을 뗀 값이 저장됩니다. 가격은 1원 이상 1,000,000,000원 이하, 재고는 0 이상입니다.",
    )
    fun register(
        request: ProductRegisterRequest,
    ): ApiResponse<ProductAdminResponse>

    @Operation(
        summary = "상품 상세 조회",
        description = "ID로 삭제되지 않은 상품을 조회합니다.",
    )
    fun getProduct(
        @Schema(name = "상품 ID", description = "조회할 상품의 ID")
        productId: Long,
    ): ApiResponse<ProductAdminResponse>
}
