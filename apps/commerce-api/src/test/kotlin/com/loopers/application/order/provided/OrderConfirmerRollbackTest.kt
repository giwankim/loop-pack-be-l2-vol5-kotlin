package com.loopers.application.order.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.application.order.required.OrderRepository
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.createOrderConfirmRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessResourceFailureException
import org.wiremock.spring.InjectWireMock

/**
 * 주문 확정의 TX A가 변경 SQL을 보낸 뒤 실패하면 두 상품의 재고 예약, 결제, 결제 중인 주문이 함께 되돌아가고 PG에는 아무것도 가지 않는지
 * 실제 MySQL과 테스트 PG에서 확인한다(ADR 0024 규칙 1, 2). TX A가 커밋하지 않았으므로 발송 기록도 없다.
 *
 * 실패는 TX A의 마지막 단계인 주문 저장에 넣는다. [OrderRepository]를 spy해 처음 부른 `save`가 영속성 컨텍스트를 flush한 뒤 던지게 한다.
 * 결제의 INSERT는 식별자가 `IDENTITY`라 저장에서 곧바로 나갔고, 첫 상품의 재고는 둘째 상품을 잠가 읽을 때의 자동 flush로, 주문과 둘째 상품의
 * 재고는 stub의 flush로 나간다. 그래서 예외는 TX A의 변경 SQL이 다 나간 뒤에 난다. 운영 코드는 flush하지 않는다.
 * 롤백된 결과를 새 트랜잭션에서 다시 읽어야 하므로 커밋하는 기반을 쓴다. 저장소는 JDK 프록시이므로 stub은 spy에 바로 건다.
 * 기반의 `prepareOrder`도 이 저장소로 주문을 저장하므로 stub은 준비를 마친 뒤에 건다.
 *
 * HTTP로 보는 실패는 커밋의 마지막 UPDATE 자체를 임시 CHECK로 거절하는
 * [com.loopers.adapter.webapi.v1.order.OrderConfirmationApiTest]에 있다.
 */
@EnableTestPg
class OrderConfirmerRollbackTest(
    private val orderConfirmer: OrderConfirmer,
    private val orderFinder: OrderFinder,
    private val productFinder: ProductFinder,
) : BaseCommittingApplicationServiceTest() {
    @MockkSpyBean
    private lateinit var orderRepository: OrderRepository

    /** 생성자 주입의 하나뿐인 예외다. 까닭은 [OrderPaymentIntegrationTest]의 같은 필드에 있다. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    /**
     * 실패한 시도도 결제를 만들며 PG 주문 번호를 하나 가져갔다. 다시 확정한 결제는 다음 번호를 받으므로 생성 스텁은 그 번호에만 건다.
     * 실패한 시도가 PG에 보냈다면 생성이 둘이거나 스텁과 맞지 않은 요청이 남는다.
     */
    @Test
    fun `a confirmation failing at the order's save in TX A rolls back the reservations and the payment and sends nothing`() {
        val first = prepareProduct(price = 1_000, stock = 10)
        val second = prepareProduct(price = 2_000, stock = 5)
        prepareOrder(first to 3, second to 1)
        val request = createOrderConfirmRequest()
        nextPgOrderId()
        testPg.acceptsCreate(
            pgOrderId = nextPgOrderId(),
            amount = 5_000,
            card = CreditCard(type = request.cardType, number = request.cardNo),
            transactionKey = "20261022:TR:rollback",
            pgStatus = "SUCCESS",
        )
        val failure = DataAccessResourceFailureException("주문을 저장하다 실패했다")
        failSavingOrderOnceAfterFlush(failure)

        val exception = assertThrows<DataAccessResourceFailureException> {
            orderConfirmer.confirm(user.id, order.id, "rollback-key", request)
        }

        assertThat(exception).isSameAs(failure)
        assertThat(testPg.createCount()).isZero()
        inNewTransaction {
            val persisted = orderFinder.find(user.id, order.id)
            assertThat(persisted.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(persisted.paymentMethod).isNull()
            assertThat(persisted.updatedAt).isEqualTo(order.updatedAt)
            assertThat(productFinder.find(first.id).stock).isEqualTo(10)
            assertThat(productFinder.find(second.id).stock).isEqualTo(5)
        }
        val confirmed = orderConfirmer.confirm(user.id, order.id, "rollback-key", request)
        assertThat(confirmed.order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(testPg.createCount()).isOne()
        assertThat(testPg.unmatchedRequests()).isEmpty()
        inNewTransaction {
            assertThat(productFinder.find(first.id).stock).isEqualTo(7)
            assertThat(productFinder.find(second.id).stock).isEqualTo(4)
        }
    }

    /** 처음 부른 주문 저장만 그때까지의 변경을 flush해 MySQL에 보낸 뒤 [failure]를 던진다. 다시 부른 저장은 그대로 지나간다. */
    private fun failSavingOrderOnceAfterFlush(failure: RuntimeException) {
        every { orderRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        } andThenAnswer {
            callOriginal()
        }
    }
}
