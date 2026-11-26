package com.loopers.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender

/**
 * One captured log event as the deployed profiles' logstash JSON carries it: the level, the key-value pairs as top-level fields
 * (values as text), and the exception logged with it. The `test` console prints no key-value pair, so tests read these instead
 * of the formatted message (payment-incident design 2.3).
 */
data class LogEvent(
    val level: Level,
    val fields: Map<String, String>,
    val throwable: Throwable?,
) {
    /** The `event` field, the event's name (`pg.create.accepted`). */
    val name: String
        get() = fields.getValue("event")
}

/**
 * Every captured line that carries an `event` field, in the order it was logged. A line logged on another thread is added under
 * the appender's own lock, so the list is read under that lock ([linesAt]'s reason).
 */
fun ListAppender<ILoggingEvent>.logEvents(): List<LogEvent> {
    val lines = synchronized(this) { list.toList() }

    return lines.map { line ->
        LogEvent(
            level = line.level,
            fields = line.keyValuePairs.orEmpty().associate { it.key to it.value.toString() },
            throwable = (line.throwableProxy as? ThrowableProxy)?.throwable,
        )
    }.filter { "event" in it.fields }
}

/** One order's event sequence: the captured events whose `orderId` field is [orderId], in the order they were logged. */
fun ListAppender<ILoggingEvent>.logEventsOf(orderId: Long): List<LogEvent> {
    return logEvents().filter { it.fields["orderId"] == orderId.toString() }
}
