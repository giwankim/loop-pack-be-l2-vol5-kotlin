package com.loopers.application.order.provided

import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 갱신 유실을 재현하는 음성 대조군. 운영 코드가 아니고, 잠그지 않은 read-then-write가 왜 틀리는지 보여 주려고만 둔다.
 * 실제 서비스가 차감을 잃지 않는지는 [OrderConfirmerConcurrencyTest]가 확인한다. 고친 쪽 곁에 망가진 쪽을 둔다.
 *
 * 두 트랜잭션이 저마다 JDBC로 재고를 잠그지 않고 읽는다. 둘 다 읽을 때까지 latch에서 기다린 뒤, 읽은 값에서 1을 뺀 상수를
 * 조건도 버전도 없이 쓴다. 둘 다 커밋하지만 차감은 하나만 남는다. 그래서 성공 수 + 최종 재고가 처음 재고와 맞지 않는다.
 * `stock = stock - 1`로 쓰면 재현되지 않는다. InnoDB의 UPDATE는 행을 잠그고 가장 최근에 커밋된 값을 읽으므로,
 * 뒤진 쪽이 앞선 쪽의 커밋을 기다렸다가 4에서 다시 뺀다. 잃는 것은 읽은 값으로 계산한 상수를 쓸 때다.
 *
 * 두 읽기가 모두 끝나도록 붙드는 latch는 이 대조군에만 있다. 운영 코드의 잠금 구간에는 latch도 sleep도 두지 않는다.
 * 실제 서비스의 경쟁은 start gate로 함께 풀어 그대로 겨루게 한다.
 *
 * [TransactionTemplate] 안의 [JdbcTemplate]은 그 트랜잭션의 커넥션을 쓴다. 두 트랜잭션이 정말 다른 커넥션에서 돌았는지는
 * 각자 읽은 `connection_id()`로 확인한다. 모든 대기에는 한도가 있다. 한도를 넘긴 대기와 SQL 오류는 재현으로 세지 않고
 * 테스트를 실패시킨다.
 *
 * 검증하는 포트가 없고 동작 자체를 SQL로 짓는 대조군이라, 생성자로 [JdbcTemplate]과 트랜잭션 관리자를 받는다.
 * 기반은 커밋한 준비, 다른 스레드의 호출, 그 호출을 기다린 뒤의 정리를 쓰려고 상속한다.
 */
class LostUpdateControlTest(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        /** 두 트랜잭션이 모두 재고를 읽기를 기다리는 한도. 기반이 다른 스레드를 기다리는 한도보다 짧아, 그 한도보다 먼저 이 대기가 실패한다. */
        private val BOTH_READ_LIMIT: Duration = Duration.ofSeconds(10)
    }

    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `two transactions that read stock 5 without a lock and each write 4 both commit, losing one deduction`() {
        prepareProduct(stock = 5)
        val bothRead = CountDownLatch(2)

        val results = runConcurrently(List(2) { { readThenWrite(product.id, bothRead) } })

        // 시간 초과나 SQL 오류로 끝난 쪽은 재현으로 세지 않는다. 그 예외를 그대로 던져 테스트를 실패시킨다.
        val reads = results.map { it.getOrThrow() }
        assertThat(reads.map { it.stock }).containsExactly(5, 5)
        assertThat(reads[0].connectionId).isNotEqualTo(reads[1].connectionId)
        val successes = results.count { it.isSuccess }
        val finalStock = stockOf(product.id)
        assertThat(successes).isEqualTo(2)
        assertThat(finalStock).isEqualTo(4)
        assertThat(successes + finalStock).isNotEqualTo(5)
    }

    /**
     * 한 트랜잭션에서 재고를 잠그지 않고 읽고, 다른 트랜잭션도 읽을 때까지 [bothRead]에서 기다린 뒤 읽은 값에서 1을 뺀 상수를 쓰고 커밋한다.
     * 읽다가 실패해도 [bothRead]를 세어, 다른 쪽이 한도까지 기다리지 않고 이어 가게 한다.
     */
    private fun readThenWrite(productId: Long, bothRead: CountDownLatch): StockRead {
        return transaction.execute {
            val read = try {
                StockRead(
                    connectionId = jdbc.queryForObject("select connection_id()", Long::class.java)!!,
                    stock = stockOf(productId),
                )
            } finally {
                bothRead.countDown()
            }
            if (!bothRead.await(BOTH_READ_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw AssertionError("두 트랜잭션이 ${BOTH_READ_LIMIT.toSeconds()}초 안에 모두 재고를 읽지 못했다.")
            }

            jdbc.update("update product set stock = ? where id = ?", read.stock - 1, productId)
            read
        }
    }

    /** 재고를 잠그지 않고 읽는다. 트랜잭션 안에서는 그 트랜잭션의 커넥션으로 읽고, 트랜잭션 밖에서는 커밋된 값을 새로 읽는다. */
    private fun stockOf(productId: Long): Int {
        return jdbc.queryForObject("select stock from product where id = ?", Int::class.java, productId)!!
    }

    /** 한 트랜잭션이 잠그지 않고 읽은 재고와 그 트랜잭션의 커넥션. */
    private data class StockRead(
        val connectionId: Long,
        val stock: Int,
    )
}
