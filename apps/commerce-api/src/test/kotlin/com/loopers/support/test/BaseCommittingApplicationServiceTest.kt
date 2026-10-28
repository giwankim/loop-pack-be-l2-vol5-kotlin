package com.loopers.support.test

import com.loopers.application.payment.provided.PaymentFinder
import com.loopers.application.product.ProductListCacheKeyGenerator
import com.loopers.application.product.provided.ProductFinder
import com.loopers.application.product.provided.ProductListRequest
import com.loopers.application.product.required.ProductListRevisionRepository
import com.loopers.domain.order.Order
import com.loopers.domain.payment.Payment
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.TestPg
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.reflect.KClass

/**
 * 커밋하는 provided 포트 테스트의 기반. [BaseApplicationServiceTest]의 컨텍스트와 `prepare` 메서드를 물려받되 테스트 트랜잭션에서
 * 빠진다. 그래서 `prepare`와 포트가 저마다 자기 트랜잭션에서 커밋하고, 다른 스레드의 트랜잭션이 그 결과를 본다.
 * 잠금을 쥔 트랜잭션 곁에서 다른 트랜잭션이 기다리는지처럼, 여러 트랜잭션이 엇갈리는 동작을 보는 테스트가 쓴다(ADR 0018).
 * 중간에 실패한 트랜잭션이 되돌아간 결과를 새 트랜잭션에서 읽는 테스트도 쓴다(ADR 0017).
 * 롤백으로 정리할 수 없으므로 테스트가 끝날 때마다 모든 표를 비운다.
 *
 * - [inAnotherThread]는 포트를 다른 스레드에서 부르고 곧바로 돌아온다. 돌려준 [Running]으로 그 호출이 끝났는지 묻고 결과를 받는다.
 *   잠금을 기다리는지는 SQL이 아니라 "[LOCK_WAIT_PROBE] 안에 끝나지 않았는가"로 본다. 잠금을 쥔 쪽은 [com.loopers.support.Pause]로 멈춘다.
 * - [runConcurrently]는 모든 호출이 start gate에 닿으면 함께 풀고, 넘긴 차례대로 성공 값이나 던진 예외를 돌려준다.
 *   [tally]는 그 결과를 성공·업무 거절·기술 오류로 센다.
 * - [inNewTransaction]은 결과를 새 트랜잭션에서 다시 읽는다. 커밋된 것만 보이고, 앞선 트랜잭션의 영속성 컨텍스트가 답하지 않는다.
 * - [currentListRevision]과 [currentListCacheKey]는 커밋된 상품 목록 revision과 그것을 품은 고객 목록의 캐시 키를 새 트랜잭션에서
 *   읽는다. 롤백하는 테스트는 revision을 올리지 않으므로 캐시의 무효화를 보는 테스트가 이 기반을 쓴다(ADR 0020).
 *
 * `prepare`가 돌려준 엔티티는 그 트랜잭션이 끝나 분리되어 있다. 지연 연관(`Product.brand` 등)을 건드리지 않는다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class BaseCommittingApplicationServiceTest : BaseApplicationServiceTest() {
    companion object {
        /** 멈춘 쪽을 풀지 못한 테스트에서도 정리가 끝나도록, 다른 스레드의 호출을 기다리는 한도. */
        private val AWAIT_LIMIT: Duration = Duration.ofSeconds(30)

        /**
         * 다른 쪽이 잠금을 기다리는지 보려고 기다리는 시간. 잠금이 없으면 이 안에 끝난다.
         * 잠금 대기가 3초여도(ADR 0018 규칙 7) 기다리는 쪽이 그 전에 풀려나도록 넉넉히 짧다.
         * 하위 클래스가 읽는 companion의 `protected` 멤버라서 Kotlin이 `@JvmStatic`을 요구한다.
         */
        @JvmStatic
        protected val LOCK_WAIT_PROBE: Duration = Duration.ofSeconds(1)
    }

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var productFinder: ProductFinder

    @Autowired
    private lateinit var productListRevisionRepository: ProductListRevisionRepository

    @Autowired
    private lateinit var productListCacheKeyGenerator: ProductListCacheKeyGenerator

    @Autowired
    private lateinit var paymentFinder: PaymentFinder

    /** 이 테스트가 다른 스레드에서 부른 호출. 정리는 이것들이 끝나기를 기다린다. */
    private val started = mutableListOf<Running<*>>()

    /**
     * 다른 스레드의 호출이 모두 끝난 뒤 표를 비운다. 테스트가 중간에 실패해 잠금을 쥔 호출이 남아도,
     * 표를 비우는 `TRUNCATE`가 그 잠금을 기다리다 멈추지 않게 한다.
     */
    @AfterEach
    fun cleanUpTables() {
        started.forEach { it.awaitQuietly() }
        databaseCleanUp.truncateAllTables()
    }

    /** [block]을 새 트랜잭션에서 부르고 커밋한다. 결과를 다시 읽는 단언을 담는다. */
    protected fun <T> inNewTransaction(block: () -> T): T {
        val transaction = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        @Suppress("UNCHECKED_CAST")
        return transaction.execute { block() } as T
    }

    /** 커밋된 상품 목록 revision. 행이 없으면 0이다. */
    protected fun currentListRevision(): Long {
        return inNewTransaction { productListRevisionRepository.findRevision() }
    }

    /**
     * 지금 커밋된 revision으로 고객 목록이 [request]에 쓰는 캐시 키. 키 생성기는 revision을 읽는 트랜잭션 안에서만 키를 만든다.
     * 운영의 키 생성기가 만드므로 테스트는 키의 철자를 적지 않는다.
     */
    protected fun currentListCacheKey(request: ProductListRequest): Any {
        val findAll = ProductFinder::class.java.getMethod("findAll", ProductListRequest::class.java)

        return inNewTransaction { productListCacheKeyGenerator.generate(productFinder, findAll, request) }
    }

    /**
     * Confirms [order] with a create that [testPg] accepts as a pending PG transaction, so the order is left payment pending,
     * and returns its payment read in a new transaction. A scene reads the PG order ID and transaction key from that payment.
     */
    protected fun confirmPending(testPg: TestPg, order: Order = this.order): Payment {
        confirmOrder(testPg, order, pgStatus = "PENDING")

        return inNewTransaction { paymentFinder.findByOrder(order.id) }
    }

    /** [block]을 다른 스레드에서 부르고 곧바로 돌아온다. 그 스레드에는 트랜잭션이 없으므로 [block]이 부른 포트가 저마다 커밋한다. */
    protected fun <T> inAnotherThread(block: () -> T): Running<T> {
        val future = CompletableFuture<T>()
        thread(name = "another-${started.size + 1}") {
            try {
                future.complete(block())
            } catch (exception: Throwable) {
                future.completeExceptionally(exception)
            }
        }
        return Running(future).also { started += it }
    }

    /** 각 호출은 자기 스레드에서 포트를 부르므로 커밋까지 끝난 결과를 받는다. 한 호출이 실패해도 나머지 결과를 모두 받는다. */
    protected fun <T> runConcurrently(tasks: List<() -> T>): List<Result<T>> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val running = tasks.map { task ->
            inAnotherThread {
                ready.countDown()
                if (!start.await(AWAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw AssertionError("동시 호출의 start gate가 ${AWAIT_LIMIT.toSeconds()}초 안에 열리지 않았다.")
                }
                runCatching(task)
            }
        }

        try {
            if (!ready.await(AWAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw AssertionError("동시 호출이 ${AWAIT_LIMIT.toSeconds()}초 안에 start gate에 닿지 않았다.")
            }
        } finally {
            start.countDown()
        }
        return running.map { it.await() }
    }

    /**
     * [runConcurrently]의 결과를 성공·업무 거절·기술 오류로 센다. [expectedRejections]는 그 테스트가 기대하는 업무 거절의 예외 타입이다.
     * 타입이 정확히 같은 예외만 업무 거절로 세므로, 상위 타입(`RuleViolationException`)을 넘겨 다른 거절까지 셀 수 없다.
     * 나머지 예외는 모두 기술 오류다. API가 409로 바꾸는 잠금 실패(`CannotAcquireLockException` 등)도 여기서는 기술 오류다.
     * 그래서 기술 오류가 품절이나 이미 확정된 주문의 거절로 섞여 통과하지 않는다.
     */
    protected fun tally(results: List<Result<*>>, vararg expectedRejections: KClass<out Throwable>): Outcomes {
        val exceptions = results.mapNotNull { it.exceptionOrNull() }
        val rejections = exceptions.count { it::class in expectedRejections }
        return Outcomes(
            successes = results.count { it.isSuccess },
            rejections = rejections,
            technicalErrors = exceptions.size - rejections,
        )
    }

    /** [tally]가 센 결과. start gate 테스트는 셋을 함께 단언하고, [total]이 요청 수와 같은지 본다. */
    protected data class Outcomes(
        val successes: Int,
        val rejections: Int,
        val technicalErrors: Int,
    ) {
        val total: Int
            get() = successes + rejections + technicalErrors
    }

    /** 다른 스레드에서 도는 호출. */
    protected class Running<T>(
        private val future: CompletableFuture<T>,
    ) {
        /** 길어야 [timeout]만큼 기다려 그 안에 끝났는지 답한다. 끝나지 않았어도 호출은 계속 돈다. */
        fun finishesWithin(timeout: Duration): Boolean {
            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                return false
            } catch (_: ExecutionException) {
                return true
            }
            return true
        }

        /** 끝나기를 기다려 결과를 돌려준다. 호출이 던졌으면 그 예외를 그대로 던진다. */
        fun await(): T {
            try {
                return future.get(AWAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
            } catch (exception: ExecutionException) {
                throw exception.cause!!
            } catch (_: TimeoutException) {
                throw AssertionError("다른 스레드의 호출이 ${AWAIT_LIMIT.toSeconds()}초 안에 끝나지 않았다.")
            }
        }

        internal fun awaitQuietly() {
            runCatching { future.get(AWAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS) }
        }
    }
}
