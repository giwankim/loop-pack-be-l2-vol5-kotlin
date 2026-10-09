package com.loopers.application.order

import com.loopers.application.order.provided.OrderAdminListRequest
import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.order.provided.OrderListRequest
import com.loopers.application.order.required.OrderListRepository
import com.loopers.application.order.required.OrderRepository
import com.loopers.domain.order.Order
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.stereotype.ValidatedApplicationService
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice

/**
 * [OrderFinder]의 구현. 두 목록이 Request를 받으므로 검증하는 Service다. 주문 하나는 Spring Data의 [OrderRepository]가,
 * 주문 목록은 QueryDSL을 쓰는 [OrderListRepository]가 읽는다. 어느 쪽이든 품목까지 읽어 주므로 돌려준 주문을
 * 트랜잭션 밖에서 건너도 된다(ADR 0014). 요청자는 웹 경계가 이미 받아들였다(ADR 0015).
 */
@ValidatedApplicationService(readOnly = true)
class OrderQueryService(
    private val orderRepository: OrderRepository,
    private val orderListRepository: OrderListRepository,
) : OrderFinder {
    override fun find(userId: Long, orderId: Long): Order {
        return orderRepository.findWithLineItemsByIdAndUserId(orderId, userId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
    }

    /** 클래스의 `readOnly`는 부르는 쪽의 쓰기 트랜잭션에 참여할 때 걸리지 않는다. */
    override fun findForUpdate(userId: Long, orderId: Long): Order {
        return orderRepository.findForUpdateWithLineItemsByIdAndUserId(orderId, userId)
            ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
    }

    override fun findAll(userId: Long, request: OrderListRequest): Slice<Order> {
        return orderListRepository.findAll(userId = userId, pageable = PageRequest.of(request.page, request.size))
    }

    /** 요청자를 넣는 [findAll]과 같은 저장소 조회를 쓴다. 갈리는 것은 거를 사용자의 유무다(설계 9 조회). */
    override fun findAll(request: OrderAdminListRequest): Slice<Order> {
        return orderListRepository.findAll(userId = request.userId, pageable = PageRequest.of(request.page, request.size))
    }

    override fun findForAdmin(orderId: Long): Order {
        return orderRepository.findWithLineItemsById(orderId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
    }
}
