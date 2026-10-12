package com.loopers.adapter.webapi.v1.like

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.adapter.webapi.v1.product.ProductResponse
import com.loopers.application.like.provided.LikeListRequest
import com.loopers.application.like.provided.LikeRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Like V1 API", description = "고객 좋아요 API 입니다. X-USER-ID 헤더의 사용자 식별자로 요청자를 식별합니다.")
interface LikeApiSpec {
    @Operation(
        summary = "좋아요 누르기",
        description = "요청자와 본문의 productId 상품 사이에 좋아요 관계를 만듭니다. 이미 있으면 그대로 두고 성공합니다. " +
            "관계를 처음 만들 때도 201이 아니라 200입니다. 본문이 없거나, productId가 없거나 1 미만이면 400입니다. " +
            "헤더가 없거나 그 사용자가 없으면 401, 없거나 삭제된 상품이면 404입니다.",
    )
    fun like(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long,
        request: LikeRequest,
    ): ApiResponse<Any>

    @Operation(
        summary = "좋아요 취소",
        description = "요청자가 경로의 상품에 건 좋아요 관계를 없앱니다. 관계가 없어도 성공합니다. " +
            "삭제된 상품에 남은 좋아요도 취소됩니다. 경로의 상품 ID가 숫자가 아니면 400, 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun unlike(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long,
        @Schema(name = "상품 ID", description = "좋아요를 취소할 상품의 ID")
        productId: Long,
    ): ApiResponse<Any>

    @Operation(
        summary = "내 좋아요 목록 조회",
        description = "요청자가 좋아요를 누른 상품을 한 조각씩 조회합니다. 최근에 누른 상품이 앞서고, 누른 시각이 같으면 " +
            "나중에 누른 상품이 앞섭니다. 삭제된 상품은 목록에서 빠집니다. 항목은 상품 목록의 항목과 같고 " +
            "likeCount는 요청자의 것만이 아니라 그 상품에 걸린 좋아요 관계의 개수입니다. " +
            "page는 0 이상, size는 1 이상 100 이하이며, 주지 않으면 page=0, size=20입니다. " +
            "총 개수 대신 다음 조각의 존재(hasNext)를 줍니다. 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun getLikedProducts(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long,
        request: LikeListRequest,
    ): ApiResponse<PageResponse<ProductResponse>>
}
