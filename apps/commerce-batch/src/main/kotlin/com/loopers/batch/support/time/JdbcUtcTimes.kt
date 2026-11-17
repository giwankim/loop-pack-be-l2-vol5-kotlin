package com.loopers.batch.support.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The one place an instant becomes a JDBC value and back. Every time crosses JDBC as a UTC `LocalDateTime`, which Connector/J
 * stores and reads as it is. An `Instant`, `Timestamp` or `OffsetDateTime` would be stored in the JVM's zone, Asia/Seoul,
 * nine hours off the rows Hibernate writes in UTC (spec 237, 확인한 사실 2).
 */
object JdbcUtcTimes {
    fun toJdbc(instant: Instant): LocalDateTime {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
    }

    fun fromJdbc(utcDateTime: LocalDateTime): Instant {
        return utcDateTime.toInstant(ZoneOffset.UTC)
    }
}
