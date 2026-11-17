package com.loopers.support.test

import com.loopers.testcontainers.MySqlTestContainersConfig
import com.loopers.testcontainers.RedisTestContainersConfig
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.test.JobOperatorTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The base of commerce-batch's job tests. It starts the full context on a real MySQL and Redis, and the tests launch jobs
 * through [jobOperatorTestUtils]. Nothing wraps a test in a transaction: a step commits its own chunks and its metadata, and
 * a test reads what they left. The base carries every common test annotation (ADR 0012).
 *
 * `spring.batch.job.enabled=false` keeps Boot's runner from launching a job when the context starts. A test launches the one
 * it sets on [jobOperatorTestUtils].
 *
 * `modules:jpa`'s `DatabaseCleanUp` knows only entity tables, and commerce-batch has no entities, so a test names the tables
 * it empties through [emptyTables].
 */
@SpringBootTest(properties = ["spring.batch.job.enabled=false"])
@SpringBatchTest
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
abstract class BaseBatchJobTest {
    @Autowired
    protected lateinit var jobOperatorTestUtils: JobOperatorTestUtils

    @Autowired
    protected lateinit var jobRepository: JobRepository

    @Autowired
    protected lateinit var jdbcClient: JdbcClient

    /**
     * Deletes every row of [tableNames], then every job instance and execution in Batch's metadata, unless the scene asks to
     * keep them with [keepBatchMetadata]. The metadata's sequence tables keep their row, since Batch reads its next IDs there.
     */
    protected fun emptyTables(vararg tableNames: String, keepBatchMetadata: Boolean = false) {
        tableNames.forEach { tableName ->
            jdbcClient.sql("DELETE FROM `$tableName`").update()
        }
        if (!keepBatchMetadata) {
            BATCH_METADATA_TABLES.forEach { tableName ->
                jdbcClient.sql("DELETE FROM `$tableName`").update()
            }
        }
    }

    companion object {
        /** Batch 6's metadata tables, children first, so no foreign key refuses a delete. */
        private val BATCH_METADATA_TABLES = listOf(
            "BATCH_STEP_EXECUTION_CONTEXT",
            "BATCH_STEP_EXECUTION",
            "BATCH_JOB_EXECUTION_CONTEXT",
            "BATCH_JOB_EXECUTION_PARAMS",
            "BATCH_JOB_EXECUTION",
            "BATCH_JOB_INSTANCE",
        )
    }
}
