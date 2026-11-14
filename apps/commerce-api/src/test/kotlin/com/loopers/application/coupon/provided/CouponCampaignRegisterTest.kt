package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.createCouponCampaignAdminRegisterRequest
import com.loopers.domain.shared.Money
import com.loopers.support.captureSql
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import jakarta.validation.ConstraintViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Registration commits, so the row is read back in a new transaction and only what was committed can answer. */
class CouponCampaignRegisterTest(
    private val couponCampaignRegister: CouponCampaignRegister,
) : BaseCommittingApplicationServiceTest() {
    @Test
    fun `a registered campaign is committed with nothing issued and issuing not suspended`() {
        val request = createCouponCampaignAdminRegisterRequest()

        val registered = couponCampaignRegister.register(request)

        val found = inNewTransaction { entityManager.find(CouponCampaign::class.java, registered.id) }
        assertThat(found.name).isEqualTo(request.name)
        assertThat(found.discountAmount).isEqualTo(Money(request.discountAmount))
        assertThat(found.totalQuota).isEqualTo(request.totalQuota)
        assertThat(found.issuedCount).isZero()
        assertThat(found.suspended).isFalse()
    }

    @Test
    fun `a blank name is rejected by request validation before the domain and saves nothing`() {
        val exception = assertThrows<ConstraintViolationException> {
            couponCampaignRegister.register(createCouponCampaignAdminRegisterRequest(name = "   "))
        }

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("쿠폰 캠페인 이름은 공백일 수 없습니다.")
        assertThat(countCampaigns()).isZero()
    }

    @Test
    fun `suspending commits the campaign as suspended and answers with it`() {
        val campaign = prepareCouponCampaign()

        val suspended = couponCampaignRegister.suspend(campaign.id)

        val found = inNewTransaction { entityManager.find(CouponCampaign::class.java, campaign.id) }
        assertThat(suspended.id).isEqualTo(campaign.id)
        assertThat(suspended.suspended).isTrue()
        assertThat(found.suspended).isTrue()
    }

    @Test
    fun `suspending a suspended campaign changes nothing and still answers with it`() {
        val campaign = prepareCouponCampaign()
        couponCampaignRegister.suspend(campaign.id)

        val again = couponCampaignRegister.suspend(campaign.id)

        val found = inNewTransaction { entityManager.find(CouponCampaign::class.java, campaign.id) }
        assertThat(again.suspended).isTrue()
        assertThat(found.suspended).isTrue()
        assertThat(found.issuedCount).isZero()
    }

    @Test
    fun `suspending an unknown campaign is refused with COUPON_CAMPAIGN_NOT_FOUND`() {
        val exception = assertThrows<CoreException> { couponCampaignRegister.suspend(999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
    }

    @Test
    fun `suspending reads the campaign row FOR UPDATE`() {
        val campaign = prepareCouponCampaign()

        val captured = captureSql { couponCampaignRegister.suspend(campaign.id) }

        assertThat(captured.statements.filter { it.contains("for update", ignoreCase = true) })
            .hasSize(1)
            .allSatisfy { assertThat(it).contains("coupon_campaign") }
    }

    private fun countCampaigns(): Long {
        return inNewTransaction {
            entityManager.createQuery("select count(c) from CouponCampaign c", Long::class.java).singleResult
        }
    }
}
