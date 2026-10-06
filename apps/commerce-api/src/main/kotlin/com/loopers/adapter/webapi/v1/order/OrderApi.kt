package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.order.OrderCreateRequest
import com.loopers.application.order.OrderListRequest
import com.loopers.application.order.OrderService
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus

/** 고객 주문. 요청자는 헤더에서 읽는다([UserIdHeader]). 생성은 요청마다 새 주문이다(ADR 0005). */
@WebApiAdapter
@RequestMapping("/api/v1/orders")
class OrderApi(private val orderService: OrderService) : OrderApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @RequestBody @Valid request: OrderCreateRequest,
    ): ApiResponse<OrderResponse> = orderService
        .create(UserIdHeader.require(userId), request)
        .let { ApiResponse.success(OrderResponse.from(it)) }

    /** 쿼리 문자열을 [OrderListRequest]로 바로 받는다. 까닭은 다른 목록과 같다(카탈로그 설계 5.17, 5.22). */
    @GetMapping
    override fun findAll(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @ModelAttribute @Valid request: OrderListRequest,
    ): ApiResponse<PageResponse<OrderResponse>> = orderService.findAll(UserIdHeader.require(userId), request)
        .let { PageResponse.from(it, OrderResponse::from) }
        .let { ApiResponse.success(it) }

    @GetMapping("/{orderId}")
    override fun find(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @PathVariable orderId: Long,
    ): ApiResponse<OrderResponse> = orderService.find(UserIdHeader.require(userId), orderId)
        .let { ApiResponse.success(OrderResponse.from(it)) }

    @PostMapping("/{orderId}/confirm")
    override fun confirm(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @PathVariable orderId: Long,
    ): ApiResponse<OrderResponse> = orderService.confirm(UserIdHeader.require(userId), orderId)
        .let { ApiResponse.success(OrderResponse.from(it)) }
}
