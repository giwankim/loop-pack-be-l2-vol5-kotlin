package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderCreateRequest
import com.loopers.application.order.OrderListRequest
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.IdempotencyKeyHeader
import com.loopers.interfaces.api.PageResponse
import com.loopers.interfaces.api.UserIdHeader
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Order V1 API", description = "사용자의 주문 생성·확정·상세·목록 조회 API")
interface OrderApiSpec {
    @Operation(
        summary = "확정 전 주문 생성",
        description = "정수 productId·quantity를 가진 items 배열(1~100개)을 받습니다. 중복 상품의 수량을 합산하며 " +
            "서버의 이름·단가로 DRAFT를 저장합니다. 재고·포인트를 차감하지 않습니다. " +
            "같은 키와 같은 상품별 수량은 최초 DRAFT와 201을 재생하고, 다른 의도는 409입니다.",
    )
    fun create(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long?,
        @Parameter(
            name = IdempotencyKeyHeader.NAME,
            `in` = ParameterIn.HEADER,
            required = true,
            description = "1~128자의 ASCII 영문·숫자·하이픈·밑줄. 대소문자를 구분합니다.",
        )
        creationKey: String?,
        request: OrderCreateRequest,
    ): ApiResponse<OrderResponse>

    @Operation(
        summary = "내 주문 목록",
        description = "요청자가 만든 주문을 최신순으로 조회합니다. 만든 시각이 같으면 나중에 받은 주문이 앞섭니다. " +
            "항목은 상세와 같은 주문 응답이며 품목은 상품 ID 오름차순입니다. " +
            "page는 0 이상, size는 1~100이며 벗어나면 400입니다. 총 개수는 주지 않고 hasNext만 줍니다. 정렬 옵션은 없습니다.",
    )
    fun findAll(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long?,
        request: OrderListRequest,
    ): ApiResponse<PageResponse<OrderResponse>>

    @Operation(summary = "내 주문 상세", description = "저장된 주문 스냅샷을 조회합니다. 없거나 다른 사용자의 주문은 모두 404입니다.")
    fun find(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long?,
        orderId: Long,
    ): ApiResponse<OrderResponse>

    @Operation(
        summary = "내 주문 확정",
        description = "생성 당시 금액으로 재고와 포인트를 함께 차감합니다. 부족하면 409이며 같은 주문으로 재시도할 수 있습니다. " +
            "이미 확정된 주문은 최초 성공 결과를 반환합니다. 별도 Idempotency-Key는 필요하지 않습니다. " +
            "없거나 다른 사용자의 주문은 404입니다.",
    )
    fun confirm(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long?,
        orderId: Long,
    ): ApiResponse<OrderResponse>
}
