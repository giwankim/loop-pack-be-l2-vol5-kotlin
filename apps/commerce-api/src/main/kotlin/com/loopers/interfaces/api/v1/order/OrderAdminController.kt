package com.loopers.interfaces.api.v1.order

import com.loopers.application.order.OrderAdminListRequest
import com.loopers.application.order.OrderService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.PageResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 주문 조회. 요청자 헤더가 없다. 자격은 관리자 경계가 보고 소유권은 묻지 않는다([OrderController]와 다른 점이다).
 */
@RestController
@RequestMapping("/api-admin/v1/orders")
class OrderAdminController(private val orderService: OrderService) : OrderAdminApiSpec {
    /** 쿼리 문자열을 [OrderAdminListRequest]로 바로 받는다. 본문이 없는 요청의 `@RequestBody` 자리다(카탈로그 설계 5.17). */
    @GetMapping
    override fun getOrders(
        @ModelAttribute @Valid request: OrderAdminListRequest,
    ): ApiResponse<PageResponse<OrderAdminResponse>> = orderService.findAll(request)
        .let { ApiResponse.success(PageResponse.from(it, OrderAdminResponse::from)) }

    @GetMapping("/{orderId}")
    override fun getOrder(
        @PathVariable("orderId") orderId: Long,
    ): ApiResponse<OrderAdminResponse> = orderService.findForAdmin(orderId)
        .let { ApiResponse.success(OrderAdminResponse.from(it)) }
}
