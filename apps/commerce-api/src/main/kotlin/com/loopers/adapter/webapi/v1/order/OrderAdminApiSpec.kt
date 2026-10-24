package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.application.order.provided.OrderAdminListRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Order Admin V1 API", description = "관리자 주문 조회 API 입니다. ADMIN 역할이 필요합니다.")
interface OrderAdminApiSpec {
    @Operation(
        summary = "주문 목록 조회",
        description = "모든 사용자의 주문을 늦게 생성된 것부터 한 조각씩 조회합니다. userId를 주면 그 사용자가 만든 주문만 봅니다. " +
            "page는 0 이상, size는 1 이상 100 이하이며, 주지 않으면 page=0, size=20입니다. " +
            "총 개수 대신 다음 조각의 존재(hasNext)를 줍니다. 각 주문에는 주문한 사용자의 ID가 실립니다. " +
            "각 주문은 상세와 같은 응답이라 결제수단(paymentMethod)과, 결제수단이 PG인 주문은 결제 상태(paymentStatus)도 싣습니다. " +
            "확정 전 주문에는 결제수단이, 확정 전 주문과 포인트로 결제한 주문에는 결제 상태가 없습니다. PG를 부르지 않습니다.",
    )
    fun getOrders(
        request: OrderAdminListRequest,
    ): ApiResponse<PageResponse<OrderAdminResponse>>

    @Operation(
        summary = "주문 상세 조회",
        description = "주문한 사용자가 누구든 저장된 주문 스냅샷과 그 사용자의 ID를 조회합니다. 없는 주문은 404입니다. " +
            "품목의 이름·단가는 주문 생성 당시의 값이며 이후 상품이 바뀌거나 삭제되어도 그대로입니다. " +
            "결제수단(paymentMethod)과, 결제수단이 PG인 주문은 결제 상태(paymentStatus)도 싣습니다. " +
            "확정 전 주문에는 결제수단이, 확정 전 주문과 포인트로 결제한 주문에는 결제 상태가 없습니다. PG를 부르지 않습니다.",
    )
    fun getOrder(
        @Parameter(`in` = ParameterIn.PATH, required = true, description = "조회할 주문의 ID")
        orderId: Long,
    ): ApiResponse<OrderAdminResponse>
}
