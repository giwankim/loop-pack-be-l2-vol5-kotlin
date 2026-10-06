package com.loopers.application.order

import com.loopers.application.brand.required.BrandRepository
import com.loopers.application.user.provided.UserFinder
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderProduct
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.shared.PageSlice
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.validation.Valid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Service
@Validated
class OrderService(
    private val orderRepository: OrderRepository,
    private val userFinder: UserFinder,
    private val productRepository: ProductRepository,
    private val brandRepository: BrandRepository,
    private val pointAccountRepository: PointAccountRepository,
) {
    /** 요청마다 새 확정 전 주문이다. 같은 품목을 다시 보내면 주문이 하나 더 생긴다(ADR 0005). */
    @Transactional
    fun create(userId: Long, @Valid request: OrderCreateRequest): OrderInfo {
        userFinder.checkExists(userId)
        val products = request.items.map { item ->
            val product = availableProduct(item.productId)
            OrderProduct(product.id, product.name, product.price, item.quantity)
        }
        return OrderInfo.from(orderRepository.save(Order(userId, products)))
    }

    @Transactional(readOnly = true)
    fun find(userId: Long, orderId: Long): OrderInfo {
        userFinder.checkExists(userId)
        val order = orderRepository.findByIdAndUserId(orderId, userId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        return OrderInfo.from(order)
    }

    /**
     * 요청자가 만든 주문 한 조각. 늦게 만든 주문이 앞선다. 받는 사용자 식별자는 요청자 하나뿐이라
     * 남의 목록을 내줄 길이 없다(카탈로그 설계 5.30).
     *
     * 저장된 스냅샷만 싣는다. 상품을 다시 읽어 이름·단가를 채우지 않으므로 이름이 바뀌거나 삭제된 상품의 주문도
     * 만들 때의 값 그대로다(ADR 0002, 설계 9 조회).
     *
     * 옮기는 일을 [PageSlice.map]에 맡겨 품목을 읽는 것이 이 읽기 트랜잭션 안에서 끝나게 한다.
     * `open-in-view`가 꺼져 있어 adapter.webapi에서는 품목을 읽을 수 없다.
     */
    @Transactional(readOnly = true)
    fun findAll(userId: Long, @Valid request: OrderListRequest): PageSlice<OrderInfo> {
        userFinder.checkExists(userId)
        return orderRepository.findAll(userId = userId, page = request.page, size = request.size)
            .map(OrderInfo::from)
    }

    /**
     * 재고·잔액·확정 상태를 함께 커밋한다. 실패는 기존 DRAFT를 남긴다(ADR 0003). 결제의 기록은 확정된 주문이다(ADR 0006).
     * 이미 확정된 본인 주문은 현재 카탈로그·잔액을 읽기 전에 거절한다. 첫 확정의 결과는 GET으로 읽는다(ADR 0005).
     * 단일 요청의 원자성과 순차 재요청만 보장하며 동시 요청의 경합은 이번 범위 밖이다.
     */
    @Transactional
    fun confirm(userId: Long, orderId: Long): OrderInfo {
        userFinder.checkExists(userId)
        val order = orderRepository.findByIdAndUserId(orderId, userId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        order.validateConfirmable()

        // 모든 품목의 판매 가능 여부를 먼저 본 뒤 차감한다. 삭제와 재고 부족이 함께면 품목 차례와 무관하게
        // ORDER_PRODUCT_NOT_AVAILABLE이 앞선다(설계 15).
        val products = order.items.map { item -> item to availableProduct(item.productId) }
        products.forEach { (item, product) -> product.deductStock(item.quantity) }
        val account = pointAccountRepository.findByUserId(userId) ?: throw CoreException(ErrorType.POINT_ACCOUNT_MISSING)
        account.pay(order.totalAmount)
        order.confirm()
        return OrderInfo.from(order)
    }

    /**
     * 관리자가 보는 주문 한 조각. [OrderAdminListRequest.userId]가 있으면 그 사용자가 만든 주문만 고른다.
     *
     * 소유권을 묻지 않는다는 것이 이름이나 입력에 드러나야 하므로 관리자 조회는 [OrderAdminListRequest]를 받는다.
     * 요청자를 넣는 [findAll]과 같은 저장소 조회를 쓰며, 거르는 사용자가 없을 수 있다는 것만 다르다.
     * 품목은 저장소가 조각과 함께 읽어 주므로 [OrderInfo]로 옮기는 일이 이 트랜잭션 안에서 끝난다(설계 9 조회).
     */
    @Transactional(readOnly = true)
    fun findAll(@Valid request: OrderAdminListRequest): PageSlice<OrderInfo> =
        orderRepository.findAll(userId = request.userId, page = request.page, size = request.size)
            .map(OrderInfo::from)

    /**
     * 관리자가 보는 주문 하나. 주문한 사용자가 누구든 저장된 스냅샷을 그대로 준다.
     *
     * 이름에 역할을 적은 까닭은 [find]와 파라미터만으로는 갈리지 않기 때문이다. 둘 다 `Long`을 받으므로
     * 소유권을 묻지 않는 쪽을 실수로 고객 경로에서 부를 수 있다. 수식어가 붙는 쪽이 관리자인 것은
     * 고객 쪽이 기본이기 때문이다(CONTEXT.md 고객). 목록은 [OrderAdminListRequest]가 그 일을 한다.
     */
    @Transactional(readOnly = true)
    fun findForAdmin(orderId: Long): OrderInfo {
        val order = orderRepository.findById(orderId) ?: throw CoreException(ErrorType.ORDER_NOT_FOUND)
        return OrderInfo.from(order)
    }

    /** 논리 삭제된 상품·브랜드는 주문할 수도 확정할 수도 없다. 생성과 확정이 같은 판단을 쓴다. */
    private fun availableProduct(productId: Long): Product {
        val product = productRepository.findById(productId)
            ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        brandRepository.findById(product.brand.id) ?: throw CoreException(ErrorType.ORDER_PRODUCT_NOT_AVAILABLE)
        return product
    }
}
