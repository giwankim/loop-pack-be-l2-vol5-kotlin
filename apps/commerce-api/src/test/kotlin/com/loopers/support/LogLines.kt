package com.loopers.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.awaitility.Awaitility.await
import java.time.Duration

/**
 * The lines at [level] the appender holds so far. A line logged on another thread is added under the appender's own lock, so
 * the list is read under that lock.
 */
fun ListAppender<ILoggingEvent>.linesAt(level: Level): List<ILoggingEvent> {
    return synchronized(this) { list.filter { it.level == level } }
}

/** The lines at [level] once there is one, for a line logged on another thread after the call returned. Waits up to [wait]. */
fun ListAppender<ILoggingEvent>.awaitLinesAt(level: Level, wait: Duration): List<ILoggingEvent> {
    await().atMost(wait).until { linesAt(level).isNotEmpty() }

    return linesAt(level)
}
