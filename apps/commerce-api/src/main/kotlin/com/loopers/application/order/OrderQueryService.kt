package com.loopers.application.order

import com.loopers.application.order.provided.OrderAdminListRequest
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.provided.OrderInfo
import com.loopers.application.order.provided.OrderListRequest
import com.loopers.application.order.required.OrderListRepository
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.user.provided.UserFinder
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice

/**
 * [OrderFinder]의 구현. 두 목록이 Request를 받으므로 검증하는 Service다. 주문 하나는 Spring Data의 [OrderRepository]가,
 * 주문 목록은 QueryDSL을 쓰는 [OrderListRepository]가 읽는다. 요청자가 있는지는 [UserFinder]에 묻는다.
 */
@ValidatedApplicationService(readOnly = true)
class OrderQueryService(
    private val orderRepository: OrderRepository,
    private val orderListRepository: OrderListRepository,
    private val userFinder: UserFinder,
) : OrderFinder {
    override fun find(userId: Long, orderId: Long): OrderInfo {
        userFinder.checkExists(userId)
        val order = orderRepository.findByIdAndUserId(orderId, userId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        return OrderInfo.from(order)
    }

    /**
     * 옮기는 일을 [Slice.map]에 맡겨 품목을 읽는 것이 이 읽기 트랜잭션 안에서 끝나게 한다.
     * `open-in-view`가 꺼져 있어 adapter.webapi에서는 품목을 읽을 수 없다.
     */
    override fun findAll(userId: Long, request: OrderListRequest): Slice<OrderInfo> {
        userFinder.checkExists(userId)
        return orderListRepository.findAll(userId = userId, pageable = PageRequest.of(request.page, request.size))
            .map(OrderInfo::from)
    }

    /**
     * 요청자를 넣는 [findAll]과 같은 저장소 조회를 쓴다. 품목은 저장소가 조각과 함께 읽어 주므로
     * [OrderInfo]로 옮기는 일이 이 트랜잭션 안에서 끝난다(설계 9 조회).
     */
    override fun findAll(request: OrderAdminListRequest): Slice<OrderInfo> =
        orderListRepository.findAll(userId = request.userId, pageable = PageRequest.of(request.page, request.size))
            .map(OrderInfo::from)

    override fun findForAdmin(orderId: Long): OrderInfo {
        val order = orderRepository.findById(orderId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        return OrderInfo.from(order)
    }
}
