package com.loopers.support

import com.loopers.domain.payment.PgOrderIdGenerator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 테스트가 정해 둔 PG 주문 번호를 정한 차례대로 내주는 생성기(ADR 0029 규칙 4). 테스트 PG의 생성 스텁은 PG 주문 번호까지 맞추므로,
 * 테스트는 확정을 부르기 전에 [next]로 다음 결제가 받을 번호를 얻어 스텁에 넣는다. 기반 클래스의 `nextPgOrderId`가 이것을 부른다.
 *
 * 정해 둔 번호가 없는데 결제가 시작되면 곧바로 실패한다. 무작위 번호로 이어 가면 그 생성은 스텁과 맞지 않아 결과 미확인으로 남고,
 * 테스트는 번호를 빠뜨렸다는 사실 대신 엉뚱한 주문 상태를 보게 된다. 함께 풀린 확정들의 결제가 나눠 가져가므로 차례는 스레드 안전한 큐에 둔다.
 * 컨텍스트와 함께 살아 다음 테스트까지 남으므로, 기반 클래스가 테스트마다 [clear]로 비운다.
 */
class TestPgOrderIdGenerator : PgOrderIdGenerator {
    private val pgOrderIds = ConcurrentLinkedQueue<String>()

    /** 아직 내주지 않은 번호 뒤에 PG 주문 번호를 하나 더 정해 돌려준다. 운영의 생성기처럼 UUID 문자열이다. */
    fun next(): String {
        val pgOrderId = UUID.randomUUID().toString()
        pgOrderIds.add(pgOrderId)

        return pgOrderId
    }

    override fun generate(): String {
        return pgOrderIds.poll() ?: throw IllegalStateException("정해 둔 PG 주문 번호가 없다. 결제를 시작하기 전에 next()로 정한다.")
    }

    /** 내주지 않고 남은 번호를 버린다. */
    fun clear() {
        pgOrderIds.clear()
    }
}

/**
 * [TestPgOrderIdGenerator]를 운영의 생성기 대신 넣는다. `@EnableTestPg`가 가져온다. 평범한 `@Configuration`이면 컴포넌트 스캔이 찾아
 * 테스트 PG를 쓰지 않는 컨텍스트에도 끼어든다. `@Primary`라서 운영의 `UuidPgOrderIdGenerator` 대신 주입된다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestPgOrderIdGeneratorConfig {
    @Bean
    @Primary
    fun testPgOrderIdGenerator(): TestPgOrderIdGenerator {
        return TestPgOrderIdGenerator()
    }
}
