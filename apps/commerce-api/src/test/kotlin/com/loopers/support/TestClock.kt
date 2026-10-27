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
 *
 * It is also the context's primary `Clock`, so an order is confirmed at its instant (ADR 0032 rule 8). It runs with the
 * system clock unless a test [pin]s it: two confirmations in one scene still get different instants, so a scene that must
 * not confirm twice can tell. A scene that asserts a confirmation time pins the clock and asserts the instant it chose.
 */
class TestClock : Clock() {
    private val base: Clock = systemUTC()

    @Volatile
    private var pinned: Instant? = null

    @Volatile
    private var offset: Duration = Duration.ZERO

    /** 시계를 [duration]만큼 앞으로 옮긴다. */
    fun advance(duration: Duration) {
        offset = offset.plus(duration)
    }

    /** Stops the clock at [instant]. [advance] still moves it from there. */
    fun pin(instant: Instant) {
        offset = Duration.ZERO
        pinned = instant
    }

    /** Unpins the clock and drops what [advance] added, so it reads the system clock again. */
    fun reset() {
        pinned = null
        offset = Duration.ZERO
    }

    override fun instant(): Instant {
        return (pinned ?: base.instant()).plus(offset)
    }

    override fun getZone(): ZoneId {
        return base.zone
    }

    /** The breaker and the order confirmation read only the instant. A zoned copy could not share the offset, so none is made. */
    override fun withZone(zone: ZoneId): Clock {
        throw UnsupportedOperationException("테스트 시계는 시간대를 바꾸지 않는다.")
    }
}
