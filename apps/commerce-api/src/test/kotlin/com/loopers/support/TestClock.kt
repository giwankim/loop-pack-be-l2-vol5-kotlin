package com.loopers.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 테스트가 옮기는 시계. 처음에는 시스템 시계와 같고, [advance]로 옮긴 만큼 앞선다. [reset]은 옮긴 것을 지운다.
 *
 * PG 조회의 서킷 브레이커가 OPEN의 기다림을 이 시계로 잰다([TestPgLookupBreakerConfig]). 그래서 테스트가 잠 없이 기다림을 넘는다(ADR 0030 규칙 8).
 * 다른 스레드의 호출도 같은 시계를 읽으므로 옮긴 양은 `@Volatile`이다.
 */
class TestClock : Clock() {
    private val base: Clock = systemUTC()

    @Volatile
    private var offset: Duration = Duration.ZERO

    /** 시계를 [duration]만큼 앞으로 옮긴다. */
    fun advance(duration: Duration) {
        offset = offset.plus(duration)
    }

    /** 옮긴 것을 지워 시스템 시계로 되돌린다. */
    fun reset() {
        offset = Duration.ZERO
    }

    override fun instant(): Instant {
        return base.instant().plus(offset)
    }

    override fun getZone(): ZoneId {
        return base.zone
    }

    /** 브레이커는 순간만 읽는다. 시간대를 바꾼 시계는 옮긴 양을 나눠 가질 수 없으므로 만들지 않는다. */
    override fun withZone(zone: ZoneId): Clock {
        throw UnsupportedOperationException("테스트 시계는 시간대를 바꾸지 않는다.")
    }
}
