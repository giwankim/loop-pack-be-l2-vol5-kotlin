package com.loopers.application.order.provided

import com.loopers.domain.order.OrderStatus
import com.loopers.domain.shared.Money
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseApplicationServiceTest
import com.loopers.support.withStatistics
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [OrderFinder]를 실제 MySQL 위에서 확인한다. 읽을 주문은 기반 클래스의 `prepareOrder`가 같은 조각의 [OrderCreator]로 만든다.
 * 정리와 flush/clear의 까닭은 [com.loopers.application.like.provided.LikerTest]와 같다.
 *
 * 상세의 값은 [com.loopers.adapter.webapi.v1.order.OrderApiTest]가 HTTP로 이미 붙들어 두므로 여기서는 상세의 조회 횟수와
 * 목록, 관리자 조회를 본다. 조각의 차례와 `hasNext`는 [com.loopers.application.order.required.OrderRepositoryTest]가 SQL로 고정한다.
 *
 * 내 목록(#15)과 관리자 조회(#16)가 한 저장소 조회를 쓰므로 둘을 한 클래스에서 본다. 갈리는 것은 거를 사용자의 유무다(설계 16.2).
 * 요청자 확인은 웹 경계로 옮겨 갔으므로(ADR 0015) 둘의 조회 횟수도 같다.
 */
class OrderFinderTest(
    private val orderFinder: OrderFinder,
) : BaseApplicationServiceTest() {
    /**
     * 입력이 조각까지 이어지는지와, 트랜잭션 안에서만 읽을 수 있는 품목이 항목에 실리는지를 본다.
     * `open-in-view`가 꺼져 있으므로 품목은 이 읽기 트랜잭션 안에서 읽혀 있어야 한다(설계 9 조회).
     */
    @Test
    fun `the order list carries the default page and size into the slice and fills the stored items`() {
        prepareBrand()
        val shirt = prepareProduct(brand, name = "티셔츠", price = 1_000)
        val socks = prepareProduct(brand, name = "양말", price = 2_000)
        // 품목을 상품 ID의 거꾸로 넣는다. 그대로 실리면 차례를 확인한 것이 아니다.
        prepareOrder(socks to 1, shirt to 2)
        entityManager.flushAndClear()

        val slice = orderFinder.findAll(user.id, OrderListRequest())

        val listed = slice.content.single()
        assertThat(slice.number).isEqualTo(OrderListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(OrderListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext()).isFalse()
        assertThat(listed.status).isEqualTo(OrderStatus.DRAFT)
        assertThat(listed.totalAmount).isEqualTo(Money(4_000))
        assertThat(listed.paidAmount).isNull()
        assertThat(listed.confirmedAt).isNull()
        assertThat(listed.createdAt).isNotNull()
        assertThat(listed.items.map { it.productId }).containsExactly(shirt.id, socks.id)
        assertThat(listed.items.map { it.productName }).containsExactly("티셔츠", "양말")
        assertThat(listed.items.map { it.unitPrice }).containsExactly(Money(1_000), Money(2_000))
        assertThat(listed.items.map { it.quantity }).containsExactly(2, 1)
        assertThat(listed.items.map { it.lineAmount }).containsExactly(Money(2_000), Money(2_000))
    }

    /**
     * 상세는 주문과 품목을 한 문장으로 읽는다. 품목은 같은 애그리거트 안이라 조회가 엔티티 그래프로 함께 읽는다(ADR 0014).
     * 지연 로딩에 맡기면 품목을 건널 때 조회가 하나 더 나가고, 트랜잭션 밖에서는 건널 수조차 없다.
     */
    @Test
    fun `the detail reads the order and its items in one statement`() {
        prepareBrand()
        val products = List(2) { prepareProduct(brand) }
        prepareOrder(products = products)
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val found = orderFinder.find(user.id, order.id)

            assertThat(found.items.map { it.productId }).containsExactlyElementsOf(products.map { it.id })
            assertThat(statistics.prepareStatementCount).isEqualTo(1L)
        }
    }

    @Test
    fun `listing orders outside the page and size bounds is rejected by request validation`() {
        prepareUser()
        entityManager.flushAndClear()

        assertThat(violationsOf(user.id, OrderListRequest(page = -1))).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(violationsOf(user.id, OrderListRequest(size = 0))).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(violationsOf(user.id, OrderListRequest(size = OrderListRequest.MAX_SIZE + 1)))
            .containsExactly("size는 ${OrderListRequest.MAX_SIZE} 이하여야 합니다.")
    }

    /**
     * 가득 찬 조각도 조회는 둘이다. 주문 루트의 조각 하나, 품목을 모아 읽는 것 하나. 요청자는 웹 경계가 확인한다(ADR 0015).
     * 주문마다 품목을 읽으면 조각 크기만큼 늘어난다(설계 9 조회, 14.1).
     *
     * 크기를 상한까지 채우는 까닭은 품목 조회가 하나로 끝나는 근거를 경계에서 확인하려는 것이다. #15는 그 근거가
     * `jpa.yml`의 `default_batch_fetch_size`와 이 상한이 같다는 것이었고, #16이 품목을 명시적으로 읽게 되어
     * 이제는 전역 설정과 무관하게 하나다(설계 16.1). 상한을 채운 이 경우가 그것을 확인한다.
     */
    @Test
    fun `a slice filled to the maximum size still reads its items in one query`() {
        prepareUser()
        prepareProduct()
        val size = OrderListRequest.MAX_SIZE
        repeat(size) { prepareOrder(user, listOf(product)) }
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = orderFinder.findAll(user.id, OrderListRequest(size = size))

            assertThat(slice.content).hasSize(size)
            assertThat(slice.content.flatMap { it.items }).hasSize(size)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        }
    }

    /** 관리자 목록은 거를 사용자가 없으면 모든 사용자의 주문을 보고, 주문한 사용자의 식별자를 함께 싣는다. */
    @Test
    fun `the admin list gives the orders of every user latest first with the ordering user id`() {
        val mine = prepareUser()
        val theirs = prepareUser()
        prepareProduct()
        val first = prepareOrder(mine, listOf(product)).id
        val second = prepareOrder(theirs, listOf(product)).id
        entityManager.flushAndClear()

        val slice = orderFinder.findAll(OrderAdminListRequest())

        assertThat(slice.content.map { it.id }).containsExactly(second, first)
        assertThat(slice.content.map { it.userId }).containsExactly(theirs.id, mine.id)
        assertThat(slice.number).isZero()
        assertThat(slice.size).isEqualTo(OrderListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext()).isFalse()
    }

    /**
     * 관리자 목록도 조회가 둘이다. 주문 루트의 조각 하나와 품목을 모아 읽는 것 하나다.
     * 총 개수를 세지 않는다는 약속도 이 수에 걸려 있다(카탈로그 설계 5.5).
     */
    @Test
    fun `the admin list reads the page of orders and all of their items in two queries`() {
        prepareUser()
        prepareBrand()
        val products = List(3) { prepareProduct(brand) }
        prepareOrder(user, products, quantity = 1)
        prepareOrder(user, listOf(products.first()), quantity = 2)
        entityManager.flushAndClear()

        entityManager.withStatistics { statistics ->
            val slice = orderFinder.findAll(OrderAdminListRequest())

            assertThat(slice.content.map { it.items.size }).containsExactly(1, 3)
            assertThat(slice.content.flatMap { it.items }.map { it.quantity }).containsExactly(2, 1, 1, 1)
            assertThat(statistics.prepareStatementCount).isEqualTo(2L)
        }
    }

    /** 컨트롤러를 거치지 않는 호출도 같은 페이지 규칙을 받는다(카탈로그 설계 5.25). */
    @Test
    fun `the admin list rejects a page and a size outside the bounds and accepts the maximum`() {
        assertThat(violationsOf(OrderAdminListRequest(page = -1))).containsExactly("page는 0 이상이어야 합니다.")
        assertThat(violationsOf(OrderAdminListRequest(size = 0))).containsExactly("size는 1 이상이어야 합니다.")
        assertThat(violationsOf(OrderAdminListRequest(size = OrderListRequest.MAX_SIZE + 1)))
            .containsExactly("size는 ${OrderListRequest.MAX_SIZE} 이하여야 합니다.")
        // 상한은 포함이다. 거절하는 쪽만 보면 @Max를 좁혀도 아무 테스트가 말하지 않는다.
        assertThat(orderFinder.findAll(OrderAdminListRequest(size = OrderListRequest.MAX_SIZE)).size)
            .isEqualTo(OrderListRequest.MAX_SIZE)
    }

    @Test
    fun `the admin detail gives another user's order with the ordering user id and the stored snapshot`() {
        prepareBrand()
        val shirt = prepareProduct(brand, price = 1_000)
        val pants = prepareProduct(brand, price = 2_000)
        prepareOrder(pants to 1, shirt to 2)
        entityManager.flushAndClear()

        val found = orderFinder.findForAdmin(order.id)

        assertThat(found.id).isEqualTo(order.id)
        assertThat(found.userId).isEqualTo(user.id)
        assertThat(found.status).isEqualTo(OrderStatus.DRAFT)
        assertThat(found.totalAmount).isEqualTo(Money(4_000))
        assertThat(found.items.map { it.productId }).containsExactly(shirt.id, pants.id)
        assertThat(found.items.map { it.lineAmount }).containsExactly(Money(2_000), Money(2_000))
        assertThat(found.paidAmount).isNull()
        assertThat(found.confirmedAt).isNull()
    }

    /** 관리자 상세는 요청자를 받지 않으므로, 없는 주문만이 거절 사유다. */
    @Test
    fun `the admin detail rejects an order that does not exist`() {
        val exception = assertThrows<CoreException> { orderFinder.findForAdmin(Long.MAX_VALUE) }

        assertThat(exception.errorType).isEqualTo(ErrorType.ORDER_NOT_FOUND)
    }

    private fun violationsOf(userId: Long, request: OrderListRequest): List<String> =
        assertThrows<ConstraintViolationException> { orderFinder.findAll(userId, request) }
            .constraintViolations.map { it.message }

    private fun violationsOf(request: OrderAdminListRequest): List<String> =
        assertThrows<ConstraintViolationException> { orderFinder.findAll(request) }
            .constraintViolations.map { it.message }
}
