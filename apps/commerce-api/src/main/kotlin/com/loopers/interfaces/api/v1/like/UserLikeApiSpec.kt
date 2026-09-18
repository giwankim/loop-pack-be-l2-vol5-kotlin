package com.loopers.interfaces.api.v1.like

import com.loopers.application.like.LikeListRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.PageResponse
import com.loopers.interfaces.api.UserIdHeader
import com.loopers.interfaces.api.v1.product.ProductResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Like V1 API", description = "고객 좋아요 API 입니다. X-USER-ID 헤더의 사용자 식별자로 요청자를 식별합니다.")
interface UserLikeApiSpec {
    @Operation(
        summary = "내 좋아요 목록 조회",
        description = "요청자가 좋아요를 누른 상품을 한 조각씩 조회합니다. 최근에 누른 상품이 앞서고, 누른 시각이 같으면 " +
            "나중에 누른 상품이 앞섭니다. 삭제된 상품은 목록에서 빠집니다. 항목은 상품 목록의 항목과 같고 " +
            "likeCount는 요청자의 것만이 아니라 그 상품에 걸린 좋아요 관계의 개수입니다. " +
            "page는 0 이상, size는 1 이상 100 이하이며, 주지 않으면 page=0, size=20입니다. " +
            "총 개수 대신 다음 조각의 존재(hasNext)를 줍니다. " +
            "헤더가 없거나 그 사용자가 없으면 401, 경로의 사용자가 요청자와 다르면 403입니다.",
    )
    fun getLikedProducts(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long?,
        @Schema(name = "사용자 ID", description = "목록을 볼 사용자의 ID. 요청자 자신이어야 합니다")
        pathUserId: Long,
        request: LikeListRequest,
    ): ApiResponse<PageResponse<ProductResponse>>
}
