package com.loopers.support.test

import com.loopers.support.DatabaseCleanUp
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

/**
 * 커밋하는 provided 포트 테스트의 기반. [BaseApplicationServiceTest]의 컨텍스트와 `prepare` 메서드를 물려받되 테스트 트랜잭션에서
 * 빠진다. 그래서 `prepare`와 포트가 저마다 자기 트랜잭션에서 커밋하고, 다른 스레드의 트랜잭션이 그 결과를 본다.
 * 잠금을 쥔 트랜잭션 곁에서 다른 트랜잭션이 기다리는지처럼, 여러 트랜잭션이 엇갈리는 동작을 보는 테스트가 쓴다(ADR 0018).
 * 롤백으로 정리할 수 없으므로 테스트가 끝날 때마다 모든 표를 비운다.
 *
 * - [inAnotherThread]는 포트를 다른 스레드에서 부르고 곧바로 돌아온다. 돌려준 [Running]으로 그 호출이 끝났는지 묻고 결과를 받는다.
 *   잠금을 기다리는지는 SQL이 아니라 "아직 끝나지 않았는가"로 본다.
 * - [runConcurrently]는 모든 호출이 start gate에 닿으면 함께 풀고, 넘긴 차례대로 성공 값이나 던진 예외를 돌려준다.
 * - [inNewTransaction]은 결과를 새 트랜잭션에서 다시 읽는다. 커밋된 것만 보이고, 앞선 트랜잭션의 영속성 컨텍스트가 답하지 않는다.
 *
 * `prepare`가 돌려준 엔티티는 그 트랜잭션이 끝나 분리되어 있다. 지연 연관(`Product.brand` 등)을 건드리지 않는다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class BaseCommittingApplicationServiceTest : BaseApplicationServiceTest() {
    companion object {
        /** 멈춘 쪽을 풀지 못한 테스트에서도 정리가 끝나도록, 다른 스레드의 호출을 기다리는 한도. */
        private val AWAIT_LIMIT: Duration = Duration.ofSeconds(30)
    }

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

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
