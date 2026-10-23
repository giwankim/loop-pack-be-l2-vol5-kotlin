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

@Tag(name = "Order V1 API", description = "사용자의 주문 생성·확정·결제 대사·상세·목록 조회 API")
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
            "PG가 거절하거나 실패를 알리면 결제에 실패한 주문으로 200이고, 예약한 재고를 돌려줍니다. " +
            "답을 받지 못한 사이 결제 대사가 결제를 끝냈으면 그 결과로 200입니다. " +
            "결제 중인 주문의 결과는 Location의 상세 조회로 읽고, 결제 대사로 PG에 다시 확인합니다. " +
            "같은 멱등 키로 다시 확정하면 PG를 다시 부르지 않고 지금 저장된 상태로 답합니다. 결제 중이면 202와 Location, 결제가 끝났으면 " +
            "실패여도 200이고, 카드는 견주지 않습니다. " +
            "없거나 다른 사용자의 주문은 키와 상관없이 404입니다. 포인트로 확정된 이전 주문은 409(ORDER_ALREADY_CONFIRMED), " +
            "결제를 시작한 주문에 다른 키는 409(IDEMPOTENCY_KEY_MISMATCH), 다른 주문이 쓴 키는 다른 사용자의 주문이어도 " +
            "409(IDEMPOTENCY_KEY_IN_USE)이며, 셋 다 재고를 예약하거나 PG를 부르기 전입니다. " +
            "재고가 부족하면 409(INSUFFICIENT_STOCK), 주문할 수 없는 상품이 있으면 404(ORDER_PRODUCT_NOT_AVAILABLE)이며, " +
            "두 거절에서 주문은 확정 전 그대로이고 키도 쓰이지 않아 같은 키로 다시 확정할 수 있습니다. " +
            "응답에는 결제수단(paymentMethod)과 결제 상태(paymentStatus)가 있고, 거래 키·PG 주문 번호·카드는 없습니다.",
        responses = [
            DocumentedResponse(responseCode = "200", description = "결제가 성공해 확정된 주문, 또는 결제에 실패한 주문"),
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

    @Operation(
        summary = "내 주문의 결제 대사",
        description = "끝나지 않은 결제를 PG 주문 번호로 PG에 조회해, 찾은 PG 거래를 확정과 같은 방식으로 반영합니다. 본문이 없습니다. " +
            "확정이 시간 초과나 응답 유실로 결과를 받지 못한 결제의 결과를 되찾으며, PG 거래의 생성을 다시 보내지 않습니다. " +
            "PG 거래가 성공이면 확정된 주문으로 200, 실패면 결제에 실패한 주문으로 200이고 예약한 재고를 돌려줍니다. " +
            "PG 거래를 찾지 못했거나, PG를 조회할 수 없거나, PG 거래가 여럿이거나 결제와 맞지 않으면 아무것도 바꾸지 않고 " +
            "결제 중인 주문으로 202와 Location을 돌려줍니다. 몇 번을 다시 물어도 결제를 실패로 끝내거나 재고를 돌려주지 않습니다. " +
            "결제가 이미 끝난 주문과 포인트로 확정된 이전 주문은 PG를 부르지 않고 저장된 상태로 200입니다. " +
            "확정 전 주문은 대사할 결제가 없어 409(PAYMENT_NOT_STARTED)이고, 없거나 다른 사용자의 주문은 모두 404입니다. " +
            "응답은 확정과 같은 주문 응답이며 거래 키·PG 주문 번호·카드는 없습니다.",
        responses = [
            DocumentedResponse(responseCode = "200", description = "결제가 끝난 주문(확정 또는 결제 실패)이나 포인트로 확정된 주문"),
            DocumentedResponse(
                responseCode = "202",
                description = "PG의 결과를 아직 알 수 없는 결제 중인 주문",
                headers = [Header(name = "Location", description = "주문 상세의 경로(/api/v1/orders/{orderId})")],
            ),
        ],
    )
    fun reconcile(
        @Parameter(name = UserIdHeader.NAME, `in` = ParameterIn.HEADER, required = true, description = "요청자의 사용자 ID")
        userId: Long,
        orderId: Long,
    ): ResponseEntity<ApiResponse<OrderResponse>>
}
