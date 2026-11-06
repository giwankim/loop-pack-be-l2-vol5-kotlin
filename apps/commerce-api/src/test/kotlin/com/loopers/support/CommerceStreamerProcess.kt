package com.loopers.support

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.GroupState
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.TopicPartition
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import org.awaitility.core.TerminalFailureException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The real commerce-streamer as a child process of the test JVM (order-outbox design 14.4). A scene in commerce-api starts it on the
 * context's MySQL and broker, with a topic and a group of its class's own, and reads what it did from its log: each
 * `Recorded the order confirmed event` line is one delivery. The rows it writes are read from the database by the scene. Both
 * cross-app classes of design 14.1 run it: `OutboxDeliveryE2ETest` and `OrderEventRecoveryE2ETest`.
 *
 * The process runs commerce-streamer's bootJar at HEAD. Gradle's `:apps:commerce-api:test` depends on the streamer's `bootJar` and
 * passes its path in the system property [JAR_PROPERTY]. Joining the class paths instead would collide (both apps have an
 * `application.yml` and the same support classes, and both scan `com.loopers`), and commerce-api has no dependency on the streamer,
 * not even in its tests. A missing jar fails the scene; it never skips it, so zero tests never counts as a pass.
 *
 * The streamer runs with its `test` profile and these overrides: the context's datasource and broker, `ddl-auto=create`, which creates
 * only the streamer's two tables, free ports for the server and management, and the class's topic and group. The topic belongs to
 * commerce-api, whose context declares it, and the streamer never creates one. The group outlives a start: a later scene's streamer
 * reads on from the offsets an earlier one marked read, since `auto.offset.reset` applies only to a group with none.
 *
 * Every wait has a deadline: the broker's topic, the group's assignment and each result. Running out, or the process exiting while
 * waiting, fails with the actual state and the streamer's recent log ([recentLog]). The log goes to a file under
 * `build/commerce-streamer`, so it stays after the run without filling the test's report.
 *
 * [terminate] ends the process and checks it is gone. Only this process is ended: it is the one the scene started, and the shared
 * database and broker are never touched. A JVM that exits without [terminate] still ends it through a shutdown hook. The word is
 * not the streamer's 멈추다, which is its consumer halting on an event it cannot record.
 */
class CommerceStreamerProcess private constructor(
    private val process: Process,
    private val logFile: Path,
    private val settings: Settings,
) {
    companion object {
        /** The system property Gradle sets to the streamer's bootJar. */
        const val JAR_PROPERTY = "commerce-streamer.jar"

        /** How long the class's topic may take to be described by the broker. The context created it before the scene started. */
        private val BROKER_WAIT: Duration = Duration.ofSeconds(30)

        /** A start took 7.4 s once on a laptop (design 14.4), and the group then settled in 0.6 s. The rest is a margin for load. */
        private val ASSIGNMENT_WAIT: Duration = Duration.ofSeconds(90)

        /** How long a published record may take to be recorded once the group holds every partition. */
        private val RESULT_WAIT: Duration = Duration.ofSeconds(30)

        /** Longer than Boot's 30 s shutdown phase, so a graceful end comes on its own before it is forced. */
        private val TERMINATION_WAIT: Duration = Duration.ofSeconds(40)

        private val POLL_INTERVAL: Duration = Duration.ofMillis(200)

        private val ADMIN_CALL_WAIT: Duration = Duration.ofSeconds(5)

        private const val RECENT_LOG_LINES = 40

        private const val RECORDED = "Recorded the order confirmed event."

        private val LOG_DIRECTORY: Path = Path.of("build", "commerce-streamer")

        /**
         * Starts the streamer from the jar [jarPath] names and returns once each of its consumers holds a partition of [Settings.topic]
         * and together they hold all of them. A scene's wait then measures the listener, not the group joining. A start that fails is
         * terminated before the failure leaves.
         */
        fun start(settings: Settings, jarPath: String? = System.getProperty(JAR_PROPERTY)): CommerceStreamerProcess {
            val jar = streamerJar(jarPath)
            awaitTopic(settings)

            Files.createDirectories(LOG_DIRECTORY)
            val logFile = LOG_DIRECTORY.resolve("${settings.topic}-${Instant.now().toEpochMilli()}.log")
            val process = ProcessBuilder(listOf(javaCommand(), "-jar", jar.toString()) + arguments(settings))
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start()
            val streamer = CommerceStreamerProcess(process, logFile, settings)

            try {
                streamer.awaitAssignment()
            } catch (exception: Throwable) {
                streamer.terminate()
                throw exception
            }
            return streamer
        }

        private fun streamerJar(jarPath: String?): Path {
            if (jarPath.isNullOrBlank()) {
                throw AssertionError(
                    "The system property $JAR_PROPERTY names no commerce-streamer bootJar. " +
                        "Gradle's :apps:commerce-api:test sets it; run the test through Gradle, " +
                        "or pass -D$JAR_PROPERTY=<path to the bootJar>.",
                )
            }

            val jar = Path.of(jarPath)
            if (!Files.isRegularFile(jar)) {
                throw AssertionError("The system property $JAR_PROPERTY names $jarPath, which is not a file.")
            }
            return jar
        }

        private fun javaCommand(): String {
            return Path.of(System.getProperty("java.home"), "bin", "java").toString()
        }

        private fun arguments(settings: Settings): List<String> {
            return listOf(
                "--spring.profiles.active=test",
                "--datasource.mysql-jpa.main.jdbc-url=${settings.jdbcUrl}",
                "--datasource.mysql-jpa.main.username=${settings.username}",
                "--datasource.mysql-jpa.main.password=${settings.password}",
                "--spring.kafka.bootstrap-servers=${settings.brokers}",
                "--spring.jpa.hibernate.ddl-auto=create",
                "--server.port=0",
                "--management.server.port=0",
                "--order-events.topic=${settings.topic}",
                "--spring.kafka.consumer.group-id=${settings.group}",
            )
        }

        /** Waits until the broker describes the class's topic with all its partitions. */
        private fun awaitTopic(settings: Settings) {
            admin(settings).use { admin ->
                var state = "not described yet"
                awaitUntil("the broker to describe the topic ${settings.topic}", BROKER_WAIT, state = { state }) {
                    val described = runCatching {
                        val topics = admin.describeTopics(listOf(settings.topic)).allTopicNames().within()

                        topics.getValue(settings.topic).partitions().size
                    }
                    state = described.map { "$it partitions" }.getOrElse { "not described: $it" }

                    described.getOrNull() == settings.partitions
                }
            }
        }

        private fun admin(settings: Settings): Admin {
            return Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to settings.brokers))
        }

        private fun <T> KafkaFuture<T>.within(): T {
            return get(ADMIN_CALL_WAIT.toMillis(), TimeUnit.MILLISECONDS)
        }

        /**
         * Waits until [condition] holds, failing after [wait] with [state] and, when given, the streamer's [recentLog]. A wait whose
         * process has exited ([exitCode] is not null) fails at once, since nothing it waits for can happen then.
         */
        private fun awaitUntil(
            what: String,
            wait: Duration,
            state: () -> String,
            recentLog: (() -> String)? = null,
            exitCode: () -> Int? = { null },
            condition: () -> Boolean,
        ) {
            try {
                await(what)
                    .atMost(wait)
                    .pollInterval(POLL_INTERVAL)
                    .failFast("the streamer exited", Callable { exitCode() != null })
                    .until { condition() }
            } catch (_: TerminalFailureException) {
                val headline = "The streamer exited with ${exitCode()} while waiting for $what."
                throw AssertionError(failure(headline, state(), recentLog))
            } catch (_: ConditionTimeoutException) {
                throw AssertionError(failure("Waited ${wait.toSeconds()} s for $what.", state(), recentLog))
            }
        }

        private fun failure(headline: String, state: String, recentLog: (() -> String)?): String {
            if (recentLog == null) {
                return "$headline\nState: $state"
            }

            return "$headline\nState: $state\nThe streamer's last $RECENT_LOG_LINES log lines:\n${recentLog()}"
        }
    }

    /** Ends the process if it is alive when the test JVM exits, for a run that ended before [terminate] was reached. */
    private val shutdownHook = Thread { process.destroyForcibly() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /** Each `Recorded the order confirmed event` line the streamer has logged so far, from the message on. */
    fun recordedLines(): List<String> {
        return logLines()
            .filter { RECORDED in it }
            .map { it.substring(it.indexOf(RECORDED)) }
    }

    /**
     * The `Recorded the order confirmed event` lines once there are [expected], waiting up to [RESULT_WAIT]. The streamer logs a line
     * after its transaction committed, so the rows it wrote are there once its line is. Running out fails with the lines so far, the
     * group's offsets against the topic's records, the scene's [state] (its rows) and the recent log.
     */
    fun awaitRecordedLines(expected: Int, state: () -> String): List<String> {
        awaitUntil(
            what = "$expected recorded order confirmed events",
            wait = RESULT_WAIT,
            state = { "${recordedLines().size} recorded; ${offsets()}; ${state()}" },
            recentLog = ::recentLog,
            exitCode = ::exitCode,
        ) { recordedLines().size >= expected }

        return recordedLines()
    }

    /** The streamer's last log lines, for a scene's own failure message, such as a wait on the broker that ran out. */
    fun recentLog(): String {
        return logLines().takeLast(RECENT_LOG_LINES).joinToString("\n")
    }

    /**
     * Ends the process and checks it is gone: SIGTERM first, which lets its consumers leave the group, and a forced end if it outlasts
     * [TERMINATION_WAIT]. Calling it again does nothing.
     */
    fun terminate() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
            }
        }
        check(!process.isAlive) { "The streamer ${process.pid()} is still running after it was terminated. Its log is $logFile." }

        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    /** Waits until each consumer of the group holds a partition of the topic and together they hold all of them. */
    private fun awaitAssignment() {
        admin(settings).use { admin ->
            var state = "not described yet"
            awaitUntil(
                what = "the group ${settings.group} to hold every partition of ${settings.topic}",
                wait = ASSIGNMENT_WAIT,
                state = { state },
                recentLog = ::recentLog,
                exitCode = ::exitCode,
            ) {
                val described = runCatching { describeGroup(admin) }
                val group = described.getOrNull()
                state = group?.toString() ?: "not described: ${described.exceptionOrNull()}"

                group?.holdsEveryPartition(settings.partitions) == true
            }
        }
    }

    private fun describeGroup(admin: Admin): GroupSnapshot {
        val description = admin.describeConsumerGroups(listOf(settings.group)).describedGroups().getValue(settings.group).within()
        val memberPartitions = description.members().map { member ->
            member.assignment().topicPartitions().filter { it.topic() == settings.topic }.map { it.partition() }.sorted()
        }

        return GroupSnapshot(description.groupState(), memberPartitions)
    }

    /** The offset the group marked read against the topic's end, per partition: how far the streamer has read what was published. */
    private fun offsets(): String {
        return runCatching {
            admin(settings).use { admin ->
                val partitions = (0 until settings.partitions).map { TopicPartition(settings.topic, it) }
                val markedRead = admin.listConsumerGroupOffsets(settings.group).partitionsToOffsetAndMetadata().within()
                val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().within()

                partitions.joinToString(", ", "marked read/records ") {
                    "${it.partition()}: ${markedRead[it]?.offset() ?: "none"}/${ends.getValue(it).offset()}"
                }
            }
        }.getOrElse { "offsets not read: $it" }
    }

    private fun exitCode(): Int? {
        return if (process.isAlive) null else process.exitValue()
    }

    /**
     * The log's complete lines. The process is still writing it, so the last line may be partly written: only lines a newline ended
     * are kept, and the bytes are decoded leniently, since a read can end inside a multibyte character.
     */
    private fun logLines(): List<String> {
        val text = String(Files.readAllBytes(logFile), Charsets.UTF_8)

        return text.substring(0, text.lastIndexOf('\n') + 1).lines().dropLast(1)
    }

    /** A consumer group as the broker described it: its state and the topic's partitions each member holds. */
    private data class GroupSnapshot(
        val state: GroupState,
        val memberPartitions: List<List<Int>>,
    ) {
        /**
         * Stable, with every member holding a partition and all [partitions] held. A group's first generation can form with one
         * member holding them all before the other consumers join (the streamer's `BaseConsumerTest`), so a full count isn't enough.
         */
        fun holdsEveryPartition(partitions: Int): Boolean {
            return state == GroupState.STABLE &&
                memberPartitions.isNotEmpty() &&
                memberPartitions.all { it.isNotEmpty() } &&
                memberPartitions.flatten().sorted() == (0 until partitions).toList()
        }

        override fun toString(): String {
            return "$state, member partitions $memberPartitions"
        }
    }

    /** Where the streamer runs: the context's MySQL and broker, and the class's topic and group. */
    data class Settings(
        val jdbcUrl: String,
        val username: String,
        val password: String,
        val bootstrapServers: List<String>,
        val topic: String,
        val group: String,
        val partitions: Int,
    ) {
        /** [bootstrapServers] as one setting's value. */
        val brokers: String
            get() = bootstrapServers.joinToString(",")
    }
}
