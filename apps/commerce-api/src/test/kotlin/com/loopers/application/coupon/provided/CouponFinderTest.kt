package com.loopers.application.coupon.provided

import com.loopers.domain.shared.Money
import com.loopers.support.captureSql
import com.loopers.support.explainTree
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The own-coupon list through [CouponFinder] on real MySQL: which SQL it sends and how MySQL plans it. The order, the paging and whose
 * coupons appear are the HTTP contract, and `CouponApiTest` holds them.
 *
 * `prepareCoupon` issues through [CouponIssuer], which refuses a surrounding transaction, so this class commits and its base empties the
 * tables after each test.
 */
class CouponFinderTest(
    private val couponFinder: CouponFinder,
    private val jdbc: JdbcTemplate,
) : BaseCommittingApplicationServiceTest() {
    @Test
    fun `the default request reads the first page of 20 with the campaign's name and discount amount copied at issue`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign(name = "신규 가입 쿠폰", discountAmount = 3_000)
        val issued = prepareCoupon(campaign, customer)

        val slice = couponFinder.findAll(customer.id, CouponListRequest())

        assertThat(slice.number).isEqualTo(CouponListRequest.DEFAULT_PAGE)
        assertThat(slice.size).isEqualTo(CouponListRequest.DEFAULT_SIZE)
        assertThat(slice.hasNext()).isFalse()
        val listed = slice.content.single()
        assertThat(listed.id).isEqualTo(issued.id)
        assertThat(listed.campaignId).isEqualTo(campaign.id)
        assertThat(listed.campaignName).isEqualTo("신규 가입 쿠폰")
        assertThat(listed.discountAmount).isEqualTo(Money(3_000))
    }

    @Test
    fun `the port refuses a page or size out of bounds`() {
        val customer = prepareUser()

        listOf(
            CouponListRequest(page = -1),
            CouponListRequest(size = 0),
            CouponListRequest(size = CouponListRequest.MAX_SIZE + 1),
        ).forEach { request ->
            assertThrows<ConstraintViolationException> { couponFinder.findAll(customer.id, request) }
        }
    }

    /**
     * The list is one statement on the coupon table. It does not join the campaign: the name and discount amount are the coupon's own
     * copies. It counts nothing either, as a `Page` would.
     *
     * The plan is the `EXPLAIN FORMAT=TREE` of the statement the port actually sent, bound to the same values (user, offset, `size + 1`).
     * With the user fixed, `IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID` holds the rows in the list's order, so the plan has no sort step.
     * The index name is asserted too, since it is the only index that answers the order and this ticket declares it as the list's.
     */
    @Test
    fun `the list is one statement on the coupon table alone whose plan reads the user's index without a sort step`() {
        val customer = prepareUser()
        val shared = prepareCouponCampaign()
        prepareCoupon(shared, customer)
        repeat(2) { prepareCoupon(campaign = prepareCouponCampaign(), user = customer) }
        repeat(2) { prepareCoupon(campaign = shared) }

        val captured = captureSql { couponFinder.findAll(customer.id, CouponListRequest(page = 1, size = 2)) }

        assertThat(captured.result.content).hasSize(1)
        val listQuery = captured.statements.single()
        assertThat(listQuery).contains("from coupon ").doesNotContain("coupon_campaign").doesNotContainIgnoringCase("join")
        val plan = jdbc.explainTree(listQuery, listOf(customer.id, 2, 3))
        assertThat(plan).describedAs("EXPLAIN FORMAT=TREE of %s", listQuery)
            .contains("IDX_COUPON_ON_USER_ID_AND_ISSUED_AT_AND_ID")
            .doesNotContain("Sort")
    }
}
