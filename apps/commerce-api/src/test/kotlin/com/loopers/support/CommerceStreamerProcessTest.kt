package com.loopers.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A scene with no streamer jar fails rather than skips, so zero tests never counts as a pass (order-outbox design 14.4). JUnit
 * reports an `AssertionError` as a failure; a skip would be a `TestAbortedException`, which isn't one. Both checks
 * come before anything reaches a broker, so no container is needed: the address names nothing.
 */
class CommerceStreamerProcessTest {
    companion object {
        private val SETTINGS = CommerceStreamerProcess.Settings(
            jdbcUrl = "jdbc:mysql://unused:3306/loopers",
            username = "unused",
            password = "unused",
            bootstrapServers = listOf("unused:9092"),
            topic = "order-events-unused",
            group = "commerce-streamer-unused",
            partitions = 3,
        )
    }

    @Test
    fun `a start without the jar property fails naming the property`() {
        val exception = assertThrows<AssertionError> { CommerceStreamerProcess.start(SETTINGS, jarPath = null) }

        assertThat(exception.message).contains("commerce-streamer.jar")
    }

    @Test
    fun `a start whose jar property names no file fails naming the property and the path`() {
        val exception = assertThrows<AssertionError> {
            CommerceStreamerProcess.start(SETTINGS, jarPath = "build/libs/no-such-commerce-streamer.jar")
        }

        assertThat(exception.message).contains("commerce-streamer.jar").contains("build/libs/no-such-commerce-streamer.jar")
    }
}
