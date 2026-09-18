package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderCreateRequest
import com.loopers.application.order.OrderListRequest
import com.loopers.application.order.OrderService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.IdempotencyKeyHeader
import com.loopers.interfaces.api.PageResponse
import com.loopers.interfaces.api.UserIdHeader
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
import org.springframework.web.bind.annotation.RestController

/**
 * 고객 주문. 요청자는 헤더에서 읽는다([UserIdHeader]). 생성은 [IdempotencyKeyHeader]의 키로 요청을 구별하며
 * 헤더의 존재와 형식은 여기서 거른다([com.loopers.interfaces.api.v1.point.PointController]와 같은 자리, 설계 12.4, 13).
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController(private val orderService: OrderService) : OrderApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @RequestHeader(IdempotencyKeyHeader.NAME, required = false) creationKey: String?,
        @RequestBody @Valid request: OrderCreateRequest,
    ): ApiResponse<OrderResponse> = orderService
        .create(UserIdHeader.require(userId), IdempotencyKeyHeader.require(creationKey), request)
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
}
