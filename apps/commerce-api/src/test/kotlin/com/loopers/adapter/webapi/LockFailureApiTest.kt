package com.loopers.adapter.webapi

import com.loopers.application.order.provided.OrderFinder
import com.loopers.application.point.provided.PointAccountFinder
import com.loopers.application.product.provided.ProductFinder
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.product.createProductAdminStockUpdateRequest
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.Pause
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import kotlin.concurrent.thread

/**
 * 행 잠금을 3초 안에 얻지 못한 요청의 응답을 고정한다(ADR 0018 규칙 7, 8). 잠금 대기는 두 자리에서 일어난다.
 * 쓰기가 행을 처음 읽는 잠금 조회와, 잠그지 않고 읽은 행의 UPDATE를 보내는 커밋의 flush다. 둘 다 409로 끝나고 아무것도 바꾸지 않아야 한다.
 *
 * 다른 스레드의 트랜잭션이 포트로 행을 잠근 채 [Pause]에서 멈춘 동안 같은 행이 필요한 요청을 보낸다.
 * 준비한 데이터를 그 스레드가 읽어야 하고 요청이 그 트랜잭션의 잠금을 기다려야 하므로, 클래스는 테스트 트랜잭션에서 빠지고
 * [DatabaseCleanUp]으로 표를 비운다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LockFailureApiTest(
    private val productFinder: ProductFinder,
    private val orderFinder: OrderFinder,
    private val pointAccountFinder: PointAccountFinder,
    private val databaseCleanUp: DatabaseCleanUp,
    transactionManager: PlatformTransactionManager,
) : BaseWebApiAdapterTest() {
    companion object {
        private const val PRODUCTS = "/api-admin/v1/products"
        private val ADMIN = user("admin").roles("ADMIN")

        /** 잠금을 쥔 스레드가 [Pause]에 닿기 전에 멈춰도 정리가 끝나도록, 그 스레드를 기다리는 한도. */
        private val AWAIT_LIMIT: Duration = Duration.ofSeconds(30)
    }

    private val transaction = TransactionTemplate(transactionManager)

    private val pause = Pause()

    /** 잠금을 쥔 스레드. */
    private var holder: Thread? = null

    /** 테스트가 풀기 전에 실패해도 잠금을 쥔 쪽을 풀고 끝나기를 기다린다. 표를 비우는 `TRUNCATE`가 그 잠금을 기다리다 멈추지 않게 한다. */
    @AfterEach
    fun cleanUp() {
        pause.release()
        holder?.join(AWAIT_LIMIT)
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `an admin write that cannot lock its product within the lock wait returns 409 and changes nothing`() {
        prepareProduct(stock = 10)
        holdInAnotherTransaction { productFinder.findForUpdate(product.id) }

        val body = assertThat(requestPutStock(product.id)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("CONCURRENT_REQUEST")
        body.extractingPath("$.meta.message").isEqualTo("다른 요청과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요.")

        releaseHolder()
        transaction.executeWithoutResult {
            assertThat(productFinder.find(product.id).stock).isEqualTo(10)
        }
    }

    /**
     * 확정은 포인트 계정을 잠그지 않고 읽으므로, 계정의 UPDATE가 커밋의 flush에서 처음 잠금을 기다린다.
     * `hibernate.order_updates`가 UPDATE를 엔티티 종류 순으로 보내 주문의 UPDATE는 이미 나간 뒤다. 409는 그것까지 되돌린 결과다.
     */
    @Test
    fun `a confirmation whose point update cannot lock at commit within the lock wait returns 409 and changes nothing`() {
        prepareOrder(products = listOf(prepareProduct(price = 1_000, stock = 10)), quantity = 3)
        charge(amount = 3_000)
        holdInAnotherTransaction {
            charge(amount = 1_000)
            entityManager.flush()
        }

        val body = assertThat(requestConfirm(order.id)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("CONCURRENT_REQUEST")
        body.extractingPath("$.meta.message").isEqualTo("다른 요청과 겹쳐 처리하지 못했습니다. 다시 시도해 주세요.")

        releaseHolder()
        transaction.executeWithoutResult {
            val draft = orderFinder.find(user.id, order.id)
            assertThat(draft.status).isEqualTo(OrderStatus.DRAFT)
            assertThat(draft.paidAmount).isNull()
            assertThat(productFinder.find(product.id).stock).isEqualTo(10)
            assertThat(pointAccountFinder.findByUser(user.id).balance.amount).isEqualTo(4_000L)
        }
    }

    /**
     * 다른 스레드에서 트랜잭션을 열어 [lock]으로 행을 잠그고, 그 트랜잭션이 잠금을 쥔 채 멈출 때까지 기다린다.
     * [lock]의 변경은 [releaseHolder] 뒤에 커밋된다.
     */
    private fun holdInAnotherTransaction(lock: () -> Unit) {
        holder = thread(name = "lock-holder") {
            transaction.executeWithoutResult {
                lock()
                pause.hold()
            }
        }
        pause.awaitHeld()
    }

    private fun releaseHolder() {
        pause.release()
        assertThat(holder!!.join(AWAIT_LIMIT)).describedAs("잠금을 쥔 쪽이 끝났다").isTrue()
    }

    /** fixture가 뽑은 수량을 싣는다. 100 이상이라 준비한 재고 10과 겹치지 않는다. */
    private fun requestPutStock(productId: Long): MvcTestResult {
        val request = createProductAdminStockUpdateRequest()
        return mvc.put().uri("$PRODUCTS/$productId/stock")
            .with(ADMIN)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"quantity": ${request.quantity}}""")
            .exchange()
    }

    private fun requestConfirm(orderId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/orders/$orderId/confirm")
            .header(UserIdHeader.NAME, user.id)
            .exchange()
    }
}
