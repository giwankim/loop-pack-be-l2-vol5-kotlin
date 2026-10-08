package com.loopers.adapter.webapi.v1.order

import com.loopers.adapter.webapi.ApiResponse
import com.loopers.adapter.webapi.PageResponse
import com.loopers.application.order.provided.OrderAdminListRequest
import com.loopers.application.order.provided.OrderFinder
import com.loopers.support.stereotype.WebApiAdapter
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 관리자 주문 조회. 요청자 헤더가 없다. 자격은 관리자 경계가 보고 소유권은 묻지 않는다([OrderApi]와 다른 점이다).
 */
@WebApiAdapter
@RequestMapping("/api-admin/v1/orders")
class OrderAdminApi(private val orderFinder: OrderFinder) : OrderAdminApiSpec {
    /** 쿼리 문자열을 [OrderAdminListRequest]로 바로 받는다. 본문이 없는 요청의 `@RequestBody` 자리다(카탈로그 설계 5.17). */
    @GetMapping
    override fun getOrders(
        @ModelAttribute @Valid request: OrderAdminListRequest,
    ): ApiResponse<PageResponse<OrderAdminResponse>> {
        val orders = orderFinder.findAll(request)

        return ApiResponse.success(PageResponse.from(orders.map(OrderAdminResponse::from)))
    }

    @GetMapping("/{orderId}")
    override fun getOrder(
        @PathVariable("orderId") orderId: Long,
    ): ApiResponse<OrderAdminResponse> {
        val order = orderFinder.findForAdmin(orderId)

        return ApiResponse.success(OrderAdminResponse.from(order))
    }
}
