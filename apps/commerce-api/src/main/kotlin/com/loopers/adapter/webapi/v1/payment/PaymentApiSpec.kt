package com.loopers.adapter.webapi.v1.payment

import com.loopers.adapter.webapi.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.responses.ApiResponse as DocumentedResponse

@Tag(name = "Payment V1 API", description = "PG가 부르는 결제 API")
interface PaymentApiSpec {
    @Operation(
        summary = "PG의 결제 알림",
        description = "PG 거래가 바뀌었다는 PG의 알림을 받아, 본문의 orderId(PG 주문 번호)의 결제를 PG에 다시 조회해 대사합니다. " +
            "본문의 transactionKey와 status는 읽지 않으며, 없거나 낯선 값이어도 됩니다. 결과는 조회한 PG 거래로만 반영합니다. " +
            "X-USER-ID 헤더도 서명도 받지 않습니다. " +
            "조회한 PG 거래가 성공이면 주문을 확정하고, 실패면 결제에 실패한 주문으로 바꾸고 예약한 재고를 돌려줍니다. " +
            "같은 알림을 여러 번 받아도 주문은 한 번만 바뀝니다. " +
            "대사가 끝나면 무엇이 바뀌었든 200입니다. 반영했거나, 바뀐 것이 없거나, PG 거래가 없거나 여럿이거나 결제와 맞지 않거나, " +
            "결제가 이미 끝났어도 그렇습니다. " +
            "orderId가 없거나 비었거나 본문이 JSON이 아니면 400, 모르는 PG 주문 번호는 404(PAYMENT_NOT_FOUND)이며 둘 다 PG에 조회하지 " +
            "않습니다. PG를 조회할 수 없으면 아무것도 바꾸지 않고 503(PG_UNAVAILABLE)이니 알림을 다시 보내 주세요.",
        responses = [
            DocumentedResponse(responseCode = "200", description = "대사를 마쳤다. 다시 보내지 않아도 된다."),
            DocumentedResponse(responseCode = "400", description = "본문의 형식이 틀렸다."),
            DocumentedResponse(responseCode = "404", description = "그 PG 주문 번호의 결제가 없다(PAYMENT_NOT_FOUND)."),
            DocumentedResponse(responseCode = "503", description = "PG를 조회하지 못했다. 다시 보내면 답이 바뀔 수 있다(PG_UNAVAILABLE)."),
        ],
    )
    fun callback(request: PaymentCallbackRequest): ApiResponse<Any>
}
