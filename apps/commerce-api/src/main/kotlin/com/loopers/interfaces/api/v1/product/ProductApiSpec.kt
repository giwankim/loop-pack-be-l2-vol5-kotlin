package com.loopers.interfaces.api.v1.product

import com.loopers.application.product.ProductListRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Product V1 API", description = "고객 상품 API 입니다. 식별이 필요 없습니다.")
interface ProductApiSpec {
    @Operation(
        summary = "상품 상세 조회",
        description = "ID로 삭제되지 않은 상품을 조회합니다. 없거나 삭제된 상품은 없는 상품입니다. " +
            "남은 재고 수량 대신 품절 여부를 주며, 재고가 0이면 soldOut이 true입니다. " +
            "likeCount는 상품에 걸린 좋아요 관계의 개수입니다.",
    )
    fun getProduct(
        @Schema(name = "상품 ID", description = "조회할 상품의 ID")
        productId: Long,
    ): ApiResponse<ProductResponse>

    @Operation(
        summary = "상품 목록 조회",
        description = "삭제되지 않은 상품을 한 조각씩 조회합니다. brandId를 주면 그 브랜드의 상품만 보며, " +
            "없거나 삭제된 브랜드를 가리키면 빈 목록입니다. sort는 latest(기본)와 price_asc이고 어느 쪽이든 " +
            "같은 값끼리는 나중에 등록된 상품이 앞섭니다. 모르는 sort는 400입니다. " +
            "page는 0 이상, size는 1 이상 100 이하이며, 주지 않으면 page=0, size=20입니다. " +
            "총 개수 대신 다음 조각의 존재(hasNext)를 줍니다. 항목마다 likeCount를 함께 줍니다.",
    )
    fun getProducts(
        request: ProductListRequest,
    ): ApiResponse<PageResponse<ProductResponse>>
}
