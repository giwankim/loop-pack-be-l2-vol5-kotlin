package com.loopers.interfaces.api.v1.like

import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.UserIdHeader
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Like V1 API", description = "고객 좋아요 API 입니다. X-USER-ID 헤더의 사용자 식별자로 요청자를 식별합니다.")
interface LikeApiSpec {
    @Operation(
        summary = "좋아요 누르기",
        description = "요청자와 상품 사이에 좋아요 관계를 만듭니다. 이미 있으면 그대로 두고 성공합니다. " +
            "헤더가 없거나 그 사용자가 없으면 401, 없거나 삭제된 상품이면 404입니다.",
    )
    fun like(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long?,
        @Schema(name = "상품 ID", description = "좋아요를 누를 상품의 ID")
        productId: Long,
    ): ApiResponse<Any>

    @Operation(
        summary = "좋아요 취소",
        description = "요청자가 상품에 건 좋아요 관계를 없앱니다. 관계가 없어도 성공합니다. " +
            "삭제된 상품에 남은 좋아요도 취소됩니다. 헤더가 없거나 그 사용자가 없으면 401입니다.",
    )
    fun unlike(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, description = "요청자의 사용자 ID", required = true)
        userId: Long?,
        @Schema(name = "상품 ID", description = "좋아요를 취소할 상품의 ID")
        productId: Long,
    ): ApiResponse<Any>
}
