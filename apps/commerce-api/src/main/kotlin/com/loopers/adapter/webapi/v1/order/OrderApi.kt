package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.IdempotencyKey
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.RequesterId
import com.loopers.application.order.provided.OrderConfirmRequest
import com.loopers.application.order.provided.OrderConfirmer
import com.loopers.application.order.provided.OrderCreateRequest
import com.loopers.application.order.provided.OrderCreator
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.provided.OrderListRequest
import com.loopers.domain.order.OrderStatus
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import java.net.URI

/**
 * 고객 주문. 요청자는 웹 경계가 받아들여 넘긴다([RequesterId]). 생성은 요청마다 새 주문이다(ADR 0005).
 * 확정은 멱등 키([IdempotencyKey])와 카드를 받아 PG로 결제한다(ADR 0024, ADR 0025). 대사는 끝나지 않은 결제를 PG에 조회해 반영한다.
 */
@WebApiAdapter
@RequestMapping("/api/v1/orders")
class OrderApi(
    private val orderFinder: OrderFinder,
    private val orderCreator: OrderCreator,
    private val orderConfirmer: OrderConfirmer,
) : OrderApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @RequesterId userId: Long,
        @RequestBody @Valid request: OrderCreateRequest,
    ): ApiResponse<OrderResponse> {
        val order = orderCreator.create(userId, request)

        return ApiResponse.success(OrderResponse.from(order))
    }

    /** 쿼리 문자열을 [OrderListRequest]로 바로 받는다. 까닭은 다른 목록과 같다(카탈로그 설계 5.17, 5.22). */
    @GetMapping
    override fun findAll(
        @RequesterId userId: Long,
        @ModelAttribute @Valid request: OrderListRequest,
    ): ApiResponse<PageResponse<OrderResponse>> {
        val orders = orderFinder.findAll(userId, request)

        return ApiResponse.success(PageResponse.from(orders.map(OrderResponse::from)))
    }

    @GetMapping("/{orderId}")
    override fun find(
        @RequesterId userId: Long,
        @PathVariable orderId: Long,
    ): ApiResponse<OrderResponse> {
        val order = orderFinder.findInfo(userId, orderId)

        return ApiResponse.success(OrderResponse.from(order))
    }

    /** 같은 키의 재요청도 저장된 상태로 [paymentResponse]의 규칙을 따른다(ADR 0025 규칙 3). */
    @PostMapping("/{orderId}/confirm")
    override fun confirm(
        @RequesterId userId: Long,
        @PathVariable orderId: Long,
        @IdempotencyKey idempotencyKey: String,
        @RequestBody @Valid request: OrderConfirmRequest,
    ): ResponseEntity<ApiResponse<OrderResponse>> {
        val order = orderConfirmer.confirm(userId, orderId, idempotencyKey, request)

        return paymentResponse(order)
    }

    /** 본문이 없다. 응답은 확정과 같은 [paymentResponse]의 규칙이다. */
    @PostMapping("/{orderId}/payment/reconcile")
    override fun reconcile(
        @RequesterId userId: Long,
        @PathVariable orderId: Long,
    ): ResponseEntity<ApiResponse<OrderResponse>> {
        val order = orderConfirmer.reconcile(userId, orderId)

        return paymentResponse(order)
    }

    /**
     * 결제 중인 주문이면 아직 결과가 없으므로 202와 그 주문의 위치를, 확정된 주문이나 결제에 실패한 주문, 포인트로 확정된 주문이면 200을
     * 돌려준다(ADR 0025 규칙 3). 위치의 상세 조회가 같은 응답을 돌려준다.
     */
    private fun paymentResponse(order: OrderInfo): ResponseEntity<ApiResponse<OrderResponse>> {
        val response = OrderResponse.from(order)

        if (response.status == OrderStatus.PAYMENT_PENDING) {
            val location = URI.create("/api/v1/orders/${response.orderId}")

            return ResponseEntity.accepted().location(location).body(ApiResponse.success(response))
        }

        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
