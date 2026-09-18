package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductAdminListRequest
import com.loopers.application.product.ProductRegisterRequest
import com.loopers.application.product.ProductStockUpdateRequest
import com.loopers.application.product.ProductUpdateRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.PageResponse
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
        summary = "상품 목록 조회",
        description = "삭제되지 않은 상품을 늦게 등록된 것부터 한 조각씩 조회합니다. brandId를 주면 그 브랜드의 상품만 봅니다. " +
            "page는 0 이상, size는 1 이상 100 이하이며, 주지 않으면 page=0, size=20입니다. " +
            "총 개수 대신 다음 조각의 존재(hasNext)를 줍니다.",
    )
    fun getProducts(
        request: ProductAdminListRequest,
    ): ApiResponse<PageResponse<ProductAdminResponse>>

    @Operation(
        summary = "상품 상세 조회",
        description = "ID로 삭제되지 않은 상품을 조회합니다.",
    )
    fun getProduct(
        @Schema(name = "상품 ID", description = "조회할 상품의 ID")
        productId: Long,
    ): ApiResponse<ProductAdminResponse>

    @Operation(
        summary = "상품 수정",
        description = "상품의 이름과 가격을 바꿉니다. 상품이 속한 브랜드는 바뀌지 않습니다. 이름과 가격의 규칙은 등록과 같고, " +
            "하나라도 거절되면 둘 다 기존 값으로 남습니다.",
    )
    fun updateProduct(
        @Schema(name = "상품 ID", description = "수정할 상품의 ID")
        productId: Long,
        request: ProductUpdateRequest,
    ): ApiResponse<ProductAdminResponse>

    @Operation(
        summary = "재고 변경",
        description = "재고를 보낸 수량으로 맞춥니다. 남은 수량을 빼거나 더하는 것이 아닙니다. 0은 품절로 맞추는 정상 요청이고, " +
            "음수는 거절되어 기존 재고가 그대로 남습니다.",
    )
    fun updateStock(
        @Schema(name = "상품 ID", description = "재고를 바꿀 상품의 ID")
        productId: Long,
        request: ProductStockUpdateRequest,
    ): ApiResponse<ProductAdminResponse>

    @Operation(
        summary = "상품 삭제",
        description = "상품을 삭제됨 상태로 만듭니다. 삭제된 상품은 조회·수정·재고 변경·삭제에서 없는 상품입니다. " +
            "상품에 남은 좋아요는 그대로 둡니다.",
    )
    fun deleteProduct(
        @Schema(name = "상품 ID", description = "삭제할 상품의 ID")
        productId: Long,
    ): ApiResponse<Any>
}
