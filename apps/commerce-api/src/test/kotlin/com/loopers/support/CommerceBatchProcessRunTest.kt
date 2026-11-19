package com.loopers.support

import com.loopers.adapter.integration.kafka.OrderEventsProperties
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate
import javax.sql.DataSource

/**
 * The real commerce-batch run to its exit from commerce-api's tests (spec 259, Maintainer story 12). Each scene starts from the
 * batch's own ranking tables and no Batch metadata ([WeeklyRankingBatchTables]).
 *
 * The freeze reads commerce-streamer's `purchase_activity`, so the empty-period scene first starts the real streamer once, whose
 * `ddl-auto=create` makes that table empty, and ends it before the batch runs. No copy of the table is written here. The class
 * names its own topic and group, so the streamer reads no other class's records.
 */
@TestPropertySource(properties = ["order-events.topic=${CommerceBatchProcessRunTest.TOPIC}"])
class CommerceBatchProcessRunTest(
    private val dataSource: DataSource,
    private val environment: Environment,
    private val kafkaProperties: KafkaProperties,
    private val orderEventsProperties: OrderEventsProperties,
) : BaseCommittingApplicationServiceTest() {
    companion object {
        const val TOPIC = "order-events-commerce-batch-process"

        private const val GROUP = "commerce-streamer-commerce-batch-process"

        private val PERIOD_START: LocalDate = LocalDate.parse("2026-08-17")
    }

    @BeforeEach
    fun startFromTheBatchTables() {
        WeeklyRankingBatchTables.reset(dataSource)
    }

    @Test
    fun `a run on a period with no purchase activity exits with 0 and leaves the period's snapshot and its publication`() {
        createTheStreamerTables()

        // run returns only after an exit with 0; any other code fails it with the log's tail.
        CommerceBatchProcess.run(
            CommerceBatchProcess.Settings.of(environment),
            CommerceBatchProcess.WeeklyRankingParameters(PERIOD_START, snapshotId = "2026-08-17-r1", revision = 1),
        )

        assertThat(
            nativeStrings(
                "select concat(snapshot_id, ' ', period_start, ' revision ', revision, ' ', state, ' inputs ', input_count) " +
                    "from weekly_ranking_snapshot",
            ),
        ).containsExactly("2026-08-17-r1 2026-08-17 revision 1 FROZEN inputs 0")
        assertThat(
            nativeStrings(
                "select concat(period_start, ' ', snapshot_id, ' revision ', revision) from weekly_ranking_publication",
            ),
        ).containsExactly("2026-08-17 2026-08-17-r1 revision 1")
    }

    @Test
    fun `a run whose periodStart is not a Monday fails with the exit code and the tail of the batch's log`() {
        val tuesday = CommerceBatchProcess.WeeklyRankingParameters(
            periodStart = LocalDate.parse("2026-08-18"),
            snapshotId = "2026-08-18-r1",
            revision = 1,
        )

        val exception = assertThrows<AssertionError> {
            CommerceBatchProcess.run(CommerceBatchProcess.Settings.of(environment), tuesday)
        }

        assertThat(exception.message)
            .containsPattern("The batch exited with [1-9][0-9]*\\.")
            .contains("periodStart must be a Monday, but 2026-08-18 is a TUESDAY")
        assertThat(nativeStrings("select snapshot_id from weekly_ranking_snapshot")).isEmpty()
    }

    /** Starts the real streamer, whose start recreates its tables empty, and ends it once its consumers hold the topic. */
    private fun createTheStreamerTables() {
        val settings = CommerceStreamerProcess.Settings.of(environment, kafkaProperties, orderEventsProperties, GROUP)

        CommerceStreamerProcess.start(settings).terminate()
    }
}
