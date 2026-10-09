package com.loopers.support

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 잠금을 쥔 쪽을 멈춰 두는 latch(ADR 0018). 잠금을 쥔 쪽이 [hold]를 부르고, 테스트는 [awaitHeld]로 멈춘 것을 확인한 뒤
 * 다른 쪽을 부르고 [release]로 푼다. 테스트가 풀기 전에 실패해도 [hold]는 [HOLD_LIMIT] 뒤에 이어 가므로 표를 비우는 정리가 끝난다.
 * 푼 뒤에 [hold]에 닿는 호출은 멈추지 않는다. 풀기 전에 닿는 호출은 모두 멈추므로, 같은 단계를 지나는 호출 가운데 하나만 멈추려면
 * stub이 고른다.
 */
class Pause {
    companion object {
        /** 테스트가 풀지 못하고 실패해도 멈춘 쪽이 이어 가는 한도. */
        private val HOLD_LIMIT: Duration = Duration.ofSeconds(10)
    }

    private val held = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun hold() {
        held.countDown()
        released.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun awaitHeld() {
        assertThat(held.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).describedAs("멈출 단계에 닿았다").isTrue()
    }

    fun release() {
        released.countDown()
    }
}
