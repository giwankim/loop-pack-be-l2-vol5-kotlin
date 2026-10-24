package com.loopers.adapter.webapi.v1.payment

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.application.order.provided.OrderPaymentReconciler
import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * PG가 부르는 결제 API. 요청자도 멱등 키도 받지 않는다(ADR 0031 규칙 7). 결제 슬라이스가 PG 주문 번호로 결제를 찾고, 주문 슬라이스의 PG 대사
 * 포트가 그 주문을 대사한다(ADR 0028 규칙 6). 어댑터가 두 슬라이스를 부르므로 슬라이스 사이에 고리가 없다.
 */
@WebApiAdapter
@RequestMapping("/api/v1/payments")
class PaymentApi(
    private val paymentFinder: PaymentFinder,
    private val orderPaymentReconciler: OrderPaymentReconciler,
) : PaymentApiSpec {
    /**
     * 본문에서 PG 주문 번호만 읽는다. 답은 PG가 다시 보낼 값이 있는지다(ADR 0031 규칙 6). 대사가 끝나면 무엇이 바뀌었든 200이다. 모르는 PG
     * 주문 번호의 404와 조회할 수 없을 때의 503은 기존 advice가 `ErrorType`으로 옮긴다.
     */
    @PostMapping("/callback")
    override fun callback(
        @RequestBody @Valid request: PaymentCallbackRequest,
    ): ApiResponse<Any> {
        val payment = paymentFinder.findByPgOrderId(request.orderId)
        orderPaymentReconciler.reconcile(payment.orderId)

        return ApiResponse.success()
    }
}
