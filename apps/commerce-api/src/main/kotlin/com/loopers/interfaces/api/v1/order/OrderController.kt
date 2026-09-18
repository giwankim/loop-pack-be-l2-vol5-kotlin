package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderCreateRequest
import com.loopers.application.order.OrderService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.UserIdHeader
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(private val orderService: OrderService) : OrderApiSpec {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @RequestHeader("Idempotency-Key", required = false) creationKey: String?,
        @RequestBody @Valid request: OrderCreateRequest,
    ): ApiResponse<OrderResponse> = orderService.create(UserIdHeader.require(userId), creationKey, request)
        .let { ApiResponse.success(OrderResponse.from(it)) }

    @GetMapping("/{orderId}")
    override fun find(
        @RequestHeader(UserIdHeader.NAME, required = false) userId: Long?,
        @PathVariable orderId: Long,
    ): ApiResponse<OrderResponse> = orderService.find(UserIdHeader.require(userId), orderId)
        .let { ApiResponse.success(OrderResponse.from(it)) }
}
