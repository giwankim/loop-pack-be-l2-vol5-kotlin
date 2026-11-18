package com.loopers.support

import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** What the weekly ranking's job tests share: the job's three identifying parameters, and purchase activities by SQL. */
object WeeklyRankingJobs {
    const val INSERT_ACTIVITY_SQL =
        "INSERT INTO purchase_activity (order_id, product_id, quantity, confirmed_at) VALUES (?, ?, ?, ?)"

    fun jobParameters(
        periodStart: LocalDate = LocalDate.parse("2026-08-17"),
        snapshotId: String,
        revision: Long = 1,
    ): JobParameters {
        return JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addString("snapshotId", snapshotId)
            .addLong("revision", revision)
            .toJobParameters()
    }

    /** Inserts a purchase activity confirmed at [confirmedAt], a UTC wall time, into the test-only `purchase_activity`. */
    fun JdbcClient.insertActivity(orderId: Long, productId: Long, quantity: Int, confirmedAt: LocalDateTime) {
        sql(INSERT_ACTIVITY_SQL)
            .params(orderId, productId, quantity, confirmedAt)
            .update()
    }

    /** The assignment's section 6 orders, inserted in reverse key order so a reader's own order shows. */
    fun JdbcClient.insertAssignmentActivities() {
        WeeklyRankingInputs.ASSIGNMENT_INPUTS.reversed().forEach { input ->
            insertActivity(
                orderId = input.orderId,
                productId = input.productId,
                quantity = input.quantity,
                confirmedAt = LocalDateTime.ofInstant(input.confirmedAt, ZoneOffset.UTC),
            )
        }
    }
}
