package com.loopers.application.payment.provided

import com.github.tomakehurst.wiremock.WireMockServer
import com.loopers.domain.payment.PaymentStatus
import com.loopers.support.EnableTestPg
import com.loopers.support.TestPg
import com.loopers.support.captureSql
import com.loopers.support.explainTree
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.wiremock.spring.InjectWireMock

/**
 * The count of 끝나지 않은 결제 through [PaymentFinder] on real MySQL: what it answers, which SQL it sends and how MySQL plans it.
 *
 * Every payment is made by a real confirmation against the test PG, one per payment status: a create accepted as a successful
 * transaction (성공), one accepted as a pending transaction (처리 중), a rejection before the create (실패), and a lost create
 * response (결과 미확인). Confirming runs outside any transaction (ADR 0028 rule 2), so this class commits.
 */
@EnableTestPg
class PaymentFinderTest(
    private val paymentFinder: PaymentFinder,
    private val jdbc: JdbcTemplate,
) : BaseCommittingApplicationServiceTest() {
    /** The one exception to constructor injection, for the reason on the same field of `OrderPaymentIntegrationTest`. */
    @InjectWireMock(TestPg.NAME)
    private lateinit var server: WireMockServer

    private val testPg by lazy { TestPg(server) }

    @Test
    fun `the count answers only the unknown and pending payments, one payment of each status stored`() {
        prepareOnePaymentOfEachStatus()

        val count = paymentFinder.countUnresolved()

        assertThat(count).isEqualTo(UnresolvedPaymentCount(unknown = 1, pending = 1))
    }

    @Test
    fun `a status with no payment counts zero`() {
        confirmOrder(testPg, prepareOrder(), pgStatus = "PENDING")
        confirmOrder(testPg, prepareOrder(), pgStatus = "PENDING")

        val count = paymentFinder.countUnresolved()

        assertThat(count).isEqualTo(UnresolvedPaymentCount(unknown = 0, pending = 2))
    }

    /**
     * The count is one statement on the payment table, planned as the `EXPLAIN FORMAT=TREE` of the statement the port actually sent,
     * bound to the same values. `IDX_PAYMENT_ON_STATUS` holds every column the statement reads, so MySQL answers from the index alone.
     *
     * The index's name is not enough. With `deleted_at IS NULL` added, MySQL still picks `IDX_PAYMENT_ON_STATUS`, but as an
     * "Index range scan" that reads each payment's row to check `deleted_at`. Only "Covering" tells the two apart (issue 268's red
     * check). The scan's kind, range or full, is the optimizer's choice on a table this small, so the scene doesn't pin it.
     */
    @Test
    fun `the count is one statement on the payment table whose plan reads only the status index`() {
        prepareOnePaymentOfEachStatus()

        val captured = captureSql { paymentFinder.countUnresolved() }

        val countQuery = captured.statements.single()
        assertThat(countQuery).contains("from payment ").doesNotContainIgnoringCase("join")
        val plan = jdbc.explainTree(countQuery, listOf(PaymentStatus.UNKNOWN.name, PaymentStatus.PENDING.name))
        assertThat(plan).describedAs("EXPLAIN FORMAT=TREE of %s", countQuery)
            .contains("Covering index")
            .contains("using IDX_PAYMENT_ON_STATUS")
    }

    /** Checks each confirmation left the status it is there for, so a count scene never stands on fewer statuses than it names. */
    private fun prepareOnePaymentOfEachStatus() {
        val statuses = listOf(
            confirmOrder(testPg, prepareOrder(), pgStatus = "SUCCESS"),
            confirmOrder(testPg, prepareOrder(), pgStatus = "PENDING"),
            confirmOrderRejectedBeforeCreate(testPg, prepareOrder()),
            confirmOrderLosingCreateResponse(testPg, prepareOrder()),
        ).map { it.paymentStatus }

        check(statuses.toSet() == PaymentStatus.entries.toSet()) { "one payment of each status, got $statuses" }
    }
}
