package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.IdempotencyKeyHeader
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.order.provided.OrderConfirmRequest
import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.application.order.provided.OrderListRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocumentedResponse

@Tag(name = "Order V1 API", description = "사용자의 주문 생성·확정·상세·목록 조회 API")
interface OrderApiSpec {
    @Operation(
        summary = "확정 전 주문 생성",
        description = "정수 productId·quantity를 가진 items 배열(1~100개)을 받습니다. 품목 객체 하나만 보내면 품목 하나짜리 배열로 읽습니다. " +
            "같은 상품이 두 번 있으면 400입니다. " +
            "서버의 이름·단가로 DRAFT를 저장합니다. 재고·포인트를 차감하지 않습니다. " +
            "요청마다 새 주문이므로 같은 요청을 다시 보내면 DRAFT가 하나 더 생깁니다.",
    )
    fun create(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long,
        request: OrderCreateRequest,
    ): ApiResponse<OrderResponse>

    @Operation(
        summary = "내 주문 목록",
        description = "요청자가 만든 주문을 최신순으로 조회합니다. 만든 시각이 같으면 나중에 받은 주문이 앞섭니다. " +
            "항목은 상세와 같은 주문 응답이며 품목은 상품 ID 오름차순입니다. " +
            "항목마다 결제수단(paymentMethod)과, 결제수단이 PG인 주문은 결제 상태(paymentStatus)도 싣습니다. " +
            "확정 전 주문에는 결제수단이, 확정 전 주문과 포인트로 결제한 주문에는 결제 상태가 없습니다. PG를 부르지 않습니다. " +
            "page는 0 이상, size는 1~100이며 벗어나면 400입니다. 총 개수는 주지 않고 hasNext만 줍니다. 정렬 옵션은 없습니다.",
    )
    fun findAll(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long,
        request: OrderListRequest,
    ): ApiResponse<PageResponse<OrderResponse>>

    @Operation(
        summary = "내 주문 상세",
        description = "저장된 주문 스냅샷을 조회합니다. 결제수단(paymentMethod)이 PG인 주문은 결제 상태(paymentStatus)도 싣습니다. " +
            "확정 전 주문에는 결제수단이, 확정 전 주문과 포인트로 결제한 주문에는 결제 상태가 없습니다. PG를 부르지 않습니다. " +
            "없거나 다른 사용자의 주문은 모두 404입니다.",
    )
    fun find(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long,
        orderId: Long,
    ): ApiResponse<OrderResponse>

    @Operation(
        summary = "내 주문 확정",
        description = "확정 전 주문의 결제를 PG로 시작합니다. 본문은 카드 종류(cardType)와 카드 번호(cardNo)이고, 금액은 생성 당시의 주문 합계입니다. " +
            "카드가 비었거나 카드 번호가 Luhn 검사를 지나지 않으면 재고를 예약하거나 PG를 부르기 전에 400입니다. " +
            "주문 합계가 PG가 받는 금액(9,223,372,036,854,775,807원)을 넘어도 PG를 부르기 전에 400입니다. " +
            "재고를 예약하고 PG에 PG 거래의 생성을 한 번 보내며 포인트는 쓰지 않습니다. " +
            "PG가 성공을 알리면 확정된 주문으로 200, 처리 중이거나 답을 받지 못하면 결제 중인 주문으로 202와 Location을 돌려줍니다. " +
            "결제 중인 주문의 결과는 Location의 상세 조회로 읽습니다. " +
            "재고가 부족하면 409(INSUFFICIENT_STOCK), 주문할 수 없는 상품이 있으면 404(ORDER_PRODUCT_NOT_AVAILABLE)이며, " +
            "두 거절에서 주문은 확정 전 그대로라 다시 확정할 수 있습니다. " +
            "확정 전이 아닌 주문은 409(ORDER_ALREADY_CONFIRMED)입니다. 없거나 다른 사용자의 주문은 404입니다. " +
            "응답에는 결제수단(paymentMethod)과 결제 상태(paymentStatus)가 있고, 거래 키·PG 주문 번호·카드는 없습니다.",
        responses = [
            DocumentedResponse(responseCode = "200", description = "결제가 성공해 확정된 주문"),
            DocumentedResponse(
                responseCode = "202",
                description = "PG가 아직 결과를 알리지 않은 결제 중인 주문",
                headers = [Header(name = "Location", description = "주문 상세의 경로(/api/v1/orders/{orderId})")],
            ),
        ],
    )
    fun confirm(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long,
        orderId: Long,
        @Parameter(
            name = IdempotencyKeyHeader.NAME,
            `in` = ParameterIn.HEADER,
            required = true,
            description = "멱등 키. 1자에서 64자까지의 공백 없는 보이는 ASCII이며 대소문자를 가립니다. 없거나 형식이 틀리면 400입니다.",
        )
        idempotencyKey: String,
        request: OrderConfirmRequest,
    ): ResponseEntity<ApiResponse<OrderResponse>>
}
