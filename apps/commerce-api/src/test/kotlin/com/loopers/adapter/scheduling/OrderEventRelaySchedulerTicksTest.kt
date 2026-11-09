package com.loopers.adapter.scheduling

import com.loopers.application.order.provided.OrderEventRelayer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * How ticks follow each other, on Boot's real scheduler with a relayer that only takes time. It needs no database or broker, so it starts
 * only the trigger and the auto-configuration that gives it its thread. Real time passes here because the schedule runs on real time.
 */
class OrderEventRelaySchedulerTicksTest {
    companion object {
        /** Longer than the 1 s delay, so a schedule counted from a round's start would overlap the rounds or run them back to back. */
        private val ROUND_LENGTH: Duration = Duration.ofMillis(1_500)
        private val MINIMUM_DELAY_BETWEEN_ROUNDS: Duration = Duration.ofSeconds(1)
        private val TICKS_WAIT: Duration = Duration.ofSeconds(15)
    }

    private val rounds = CopyOnWriteArrayList<Round>()
    private val runningRounds = AtomicInteger()
    private val mostRunningRounds = AtomicInteger()

    private fun contextRunner(relayer: OrderEventRelayer): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withBean(OrderEventRelayer::class.java, { relayer })
            .withUserConfiguration(OrderEventRelayScheduler::class.java)
            .withPropertyValues("order-events.relay.enabled=true")
    }

    @Test
    fun `ticks don't overlap, run on one thread and wait a second from the end of one round to the next`() {
        val slowRelayer = relayerRunning {
            val startedAtNanos = System.nanoTime()
            mostRunningRounds.accumulateAndGet(runningRounds.incrementAndGet(), ::maxOf)
            Thread.sleep(ROUND_LENGTH)
            runningRounds.decrementAndGet()
            rounds += Round(startedAtNanos, System.nanoTime(), Thread.currentThread().name)
        }

        contextRunner(slowRelayer).run {
            await().atMost(TICKS_WAIT).until { rounds.size >= 2 }
        }

        val (firstRound, secondRound) = rounds
        assertThat(mostRunningRounds).hasValue(1)
        assertThat(secondRound.threadName).isEqualTo(firstRound.threadName)
        val delayBetweenRounds = Duration.ofNanos(secondRound.startedAtNanos - firstRound.endedAtNanos)
        assertThat(delayBetweenRounds).isGreaterThanOrEqualTo(MINIMUM_DELAY_BETWEEN_ROUNDS)
    }

    /** The relay lets a database's exception leave the round (ADR 0035), so the schedule has to survive it for the relay to carry on. */
    @Test
    fun `a round that throws doesn't stop the next tick`() {
        val roundsStarted = AtomicInteger()
        val failingRelayer = relayerRunning {
            if (roundsStarted.incrementAndGet() == 1) {
                throw IllegalStateException("The database is gone.")
            }
        }

        contextRunner(failingRelayer).run {
            await().atMost(TICKS_WAIT).until { roundsStarted.get() >= 2 }
        }
    }

    private fun relayerRunning(round: () -> Unit): OrderEventRelayer {
        return object : OrderEventRelayer {
            override fun publishDueEvents() {
                round()
            }
        }
    }

    private data class Round(
        val startedAtNanos: Long,
        val endedAtNanos: Long,
        val threadName: String,
    )
}
