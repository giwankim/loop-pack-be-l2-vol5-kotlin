package com.loopers.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A clock a scene sets and moves. Only the instant is read: the job reads `clock.instant()`. A test class puts it in its
 * context as the primary `Clock` with a nested `@TestConfiguration`.
 */
class MovableClock(
    @Volatile var instant: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    override fun instant(): Instant {
        return instant
    }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }

    override fun withZone(zone: ZoneId): Clock {
        throw UnsupportedOperationException("The movable clock does not change zones")
    }
}
