package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.Coupon
import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.CouponCampaignExhaustedException
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.TestClock
import com.loopers.support.captureSql
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.IllegalTransactionStateException
import java.time.Instant

/**
 * Issuing through the provided port on real MySQL. Every call commits, so results are read back in new transactions.
 *
 * `@EnableTestPg` is here only because it is what puts the pinnable [TestClock] in the context. Nothing in this class calls the PG.
 */
@EnableTestPg
class CouponIssueIntegrationTest(
    private val couponIssuer: CouponIssuer,
    private val clock: TestClock,
) : BaseCommittingApplicationServiceTest() {
    @Test
    fun `the first issue commits one coupon with the campaign's name and discount amount and the issue time from the clock`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign(name = "신규 가입 쿠폰", discountAmount = 3_000)
        clock.pin(Instant.parse("2026-11-11T01:02:03.123456789Z"))

        val result = couponIssuer.issue(customer.id, campaign.id)

        val found = inNewTransaction { entityManager.find(Coupon::class.java, result.coupon.id) }
        assertThat(result.newlyIssued).isTrue()
        assertThat(found.campaignId).isEqualTo(campaign.id)
        assertThat(found.userId).isEqualTo(customer.id)
        assertThat(found.campaignName).isEqualTo("신규 가입 쿠폰")
        assertThat(found.discountAmount).isEqualTo(Money(3_000))
        assertThat(found.issuedAt).isEqualTo(Instant.parse("2026-11-11T01:02:03.123456Z"))
        assertThat(countCoupons()).isOne()
        assertThat(issuedCount(campaign)).isOne()
    }

    @Test
    fun `a repeat request answers with the same coupon and changes nothing`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = couponIssuer.issue(customer.id, campaign.id)

        val repeat = couponIssuer.issue(customer.id, campaign.id)

        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(first.coupon.id)
        assertThat(countCoupons()).isOne()
        assertThat(issuedCount(campaign)).isOne()
    }

    @Test
    fun `an exhausted campaign refuses a second user but still answers its holder`() {
        val holder = prepareUser()
        val latecomer = prepareUser()
        val campaign = prepareCouponCampaign(totalQuota = 1)
        val first = couponIssuer.issue(holder.id, campaign.id)

        assertThrows<CouponCampaignExhaustedException> { couponIssuer.issue(latecomer.id, campaign.id) }

        val repeat = couponIssuer.issue(holder.id, campaign.id)
        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(first.coupon.id)
        assertThat(countCoupons()).isOne()
        assertThat(issuedCount(campaign)).isOne()
    }

    @Test
    fun `an unknown campaign is refused with COUPON_CAMPAIGN_NOT_FOUND`() {
        val customer = prepareUser()

        val exception = assertThrows<CoreException> { couponIssuer.issue(customer.id, 999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
        assertThat(countCoupons()).isZero()
    }

    /** The first issue is the control: it shows that the filter would see a lock if a repeat sent one. */
    @Test
    fun `the first issue locks the campaign row and a holder's repeat request sends no FOR UPDATE`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()

        val first = captureSql { couponIssuer.issue(customer.id, campaign.id) }
        val repeat = captureSql { couponIssuer.issue(customer.id, campaign.id) }

        assertThat(lockingReads(first.statements)).hasSize(1).allSatisfy { assertThat(it).contains("coupon_campaign") }
        assertThat(lockingReads(repeat.statements)).isEmpty()
    }

    @Test
    fun `issuing inside a caller's transaction is refused and issues nothing`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()

        inNewTransaction {
            assertThrows<IllegalTransactionStateException> { couponIssuer.issue(customer.id, campaign.id) }
        }

        assertThat(countCoupons()).isZero()
        assertThat(issuedCount(campaign)).isZero()
    }

    private fun lockingReads(statements: List<String>): List<String> {
        return statements.filter { it.contains("for update", ignoreCase = true) }
    }

    private fun countCoupons(): Long {
        return inNewTransaction { entityManager.countCoupons() }
    }

    private fun issuedCount(campaign: CouponCampaign): Int {
        return inNewTransaction { entityManager.couponCampaignIssuedCount(campaign.id) }
    }
}
