package com.loopers.support

import org.springframework.core.env.Environment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The real commerce-batch as a child process of the test JVM (spec 259). Unlike [CommerceStreamerProcess], it runs to its exit:
 * [run] starts the batch's `weeklyRanking` job on the context's MySQL, waits for the process to end and returns the finished run.
 * A scene reads what the job did from the database.
 *
 * The process runs commerce-batch's bootJar at HEAD. Gradle's `:apps:commerce-api:test` depends on the batch's `bootJar` and passes
 * its path in the system property [JAR_PROPERTY], the way it passes the streamer's. commerce-api has no dependency on the batch,
 * not even in its tests. A missing jar fails the scene; it never skips it, so zero tests never counts as a pass.
 *
 * The batch runs with its `test` profile and the context's datasource. The profile turns on Batch's metadata schema and the weekly
 * ranking's schema file, both created only if missing. No Redis address is passed: the batch's main code never uses Redis, and
 * the connection is made on first use, so its start doesn't need one. The job's three identifying parameters are given in their
 * command-line spelling ([WeeklyRankingParameters.arguments]), the one an operator types (weekly-ranking design 4).
 *
 * The run has a deadline, [RUN_WAIT]. Running out ends the process and fails, and so does a non-zero exit code:
 * `CommerceBatchApplication.main` exits with the job's exit code, and a refused start exits non-zero too. Both failures carry the
 * batch's recent log. The log goes to a file under `build/commerce-batch`, so it stays after the run without filling the test's
 * report.
 */
object CommerceBatchProcess {
    /** The system property Gradle sets to the batch's bootJar. */
    const val JAR_PROPERTY = "commerce-batch.jar"

    /** The system property Gradle sets to the batch's `weekly-ranking-schema.sql`, the source of the ranking's tables. */
    const val SCHEMA_PROPERTY = "commerce-batch.weekly-ranking-schema"

    /**
     * A start took 3.2 to 4.0 s on a laptop and the job's run on a few rows 0.15 to 0.26 s (weekly-ranking design 14.2). The rest
     * is a margin for load.
     */
    private val RUN_WAIT: Duration = Duration.ofSeconds(120)

    /** How long a process past its deadline may take to end once it is forced. */
    private val TERMINATION_WAIT: Duration = Duration.ofSeconds(10)

    private const val RECENT_LOG_LINES = 40

    private const val JOB_NAME = "weeklyRanking"

    private val LOG_DIRECTORY: Path = Path.of("build", "commerce-batch")

    /**
     * Runs the batch's `weeklyRanking` job with [parameters] from the jar [jarPath] names, and returns once the process exited
     * with 0. A missing jar, a run past [RUN_WAIT] and a non-zero exit code fail with an [AssertionError].
     */
    fun run(
        settings: Settings,
        parameters: WeeklyRankingParameters,
        jarPath: String? = System.getProperty(JAR_PROPERTY),
    ) {
        val jar = requiredFile(JAR_PROPERTY, jarPath, "commerce-batch bootJar")

        Files.createDirectories(LOG_DIRECTORY)
        val logFile = LOG_DIRECTORY.resolve("${parameters.snapshotId}-${Instant.now().toEpochMilli()}.log")
        val process = ProcessBuilder(listOf(javaCommand(), "-jar", jar.toString()) + arguments(settings, parameters))
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        val shutdownHook = Thread { process.destroyForcibly() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            if (!process.waitFor(RUN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                throw AssertionError(failure("The batch did not exit within ${RUN_WAIT.toSeconds()} s.", logFile))
            }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw AssertionError(failure("The batch exited with $exitCode.", logFile))
        }
    }

    /** The batch's `weekly-ranking-schema.sql` from the path Gradle passes in [SCHEMA_PROPERTY], for a scene to run itself. */
    fun weeklyRankingSchema(schemaPath: String? = System.getProperty(SCHEMA_PROPERTY)): Path {
        return requiredFile(SCHEMA_PROPERTY, schemaPath, "commerce-batch weekly-ranking-schema.sql")
    }

    private fun requiredFile(property: String, path: String?, what: String): Path {
        if (path.isNullOrBlank()) {
            throw AssertionError(
                "The system property $property names no $what. " +
                    "Gradle's :apps:commerce-api:test sets it; run the test through Gradle, " +
                    "or pass -D$property=<path to the file>.",
            )
        }

        val file = Path.of(path)
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw AssertionError("The system property $property names $path, which is not a readable file.")
        }
        return file
    }

    private fun javaCommand(): String {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString()
    }

    private fun arguments(settings: Settings, parameters: WeeklyRankingParameters): List<String> {
        return listOf(
            "--spring.profiles.active=test",
            "--datasource.mysql-jpa.main.jdbc-url=${settings.jdbcUrl}",
            "--datasource.mysql-jpa.main.username=${settings.username}",
            "--datasource.mysql-jpa.main.password=${settings.password}",
            "--job.name=$JOB_NAME",
        ) + parameters.arguments()
    }

    private fun failure(headline: String, logFile: Path): String {
        return "$headline Its log is $logFile.\nThe batch's last $RECENT_LOG_LINES log lines:\n${recentLog(logFile)}"
    }

    private fun recentLog(logFile: Path): String {
        return readLogLines(logFile).takeLast(RECENT_LOG_LINES).joinToString("\n")
    }

    /** The log's lines, decoded leniently, since a forced end can leave a multibyte character cut. */
    private fun readLogLines(logFile: Path): List<String> {
        return String(Files.readAllBytes(logFile), Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }
    }

    /** `weeklyRanking`'s three identifying parameters. */
    data class WeeklyRankingParameters(
        val periodStart: LocalDate,
        val snapshotId: String,
        val revision: Long,
    ) {
        /** The command-line spelling, with the types the job's validator requires (weekly-ranking design 4). */
        fun arguments(): List<String> {
            return listOf(
                "periodStart=$periodStart,java.time.LocalDate",
                "snapshotId=$snapshotId",
                "revision=$revision,java.lang.Long",
            )
        }
    }

    /** Where the batch runs: the context's MySQL. */
    data class Settings(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    ) {
        companion object {
            /** The context's MySQL read from its [environment]. */
            fun of(environment: Environment): Settings {
                return Settings(
                    jdbcUrl = environment.getRequiredProperty("datasource.mysql-jpa.main.jdbc-url"),
                    username = environment.getRequiredProperty("datasource.mysql-jpa.main.username"),
                    password = environment.getRequiredProperty("datasource.mysql-jpa.main.password"),
                )
            }
        }
    }
}
