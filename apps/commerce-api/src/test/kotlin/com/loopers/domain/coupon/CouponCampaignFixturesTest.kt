package com.loopers.domain.coupon

import com.loopers.domain.shared.Money
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/** Draws many defaults so a default outside its range shows here every time, rather than failing some other test now and then (ADR 0010). */
class CouponCampaignFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `createCouponCampaign builds unsaved campaigns that have issued nothing and are not suspended`() {
        val campaigns = List(SAMPLES) { createCouponCampaign() }

        assertThat(campaigns).allSatisfy { campaign ->
            assertThat(campaign.id).isZero()
            assertThat(campaign.deletedAt).isNull()
            assertThat(campaign.issuedCount).isZero()
            assertThat(campaign.suspended).isFalse()
            assertThat(campaign.totalQuota).isBetween(100, 1_000)
        }
    }

    @Test
    fun `createCouponCampaign builds campaigns the real constructor accepts`() {
        val campaigns = List(SAMPLES) { createCouponCampaign() }

        assertThat(campaigns).allSatisfy { campaign ->
            assertDoesNotThrow { CouponCampaign(campaign.name, campaign.discountAmount, campaign.totalQuota) }
        }
    }

    @Test
    fun `createCouponCampaign uses the values it is given`() {
        val campaign = createCouponCampaign(name = "신규 가입 쿠폰", discountAmount = Money(5_000), totalQuota = 7)

        assertThat(campaign.name).isEqualTo("신규 가입 쿠폰")
        assertThat(campaign.discountAmount).isEqualTo(Money(5_000))
        assertThat(campaign.totalQuota).isEqualTo(7)
    }

    @Test
    fun `createCouponCampaignAdminRegisterRequest satisfies the request's constraints and avoids a quota of 1`() {
        val requests = List(SAMPLES) { createCouponCampaignAdminRegisterRequest() }

        assertThat(requests.flatMap { validator.validate(it) }).isEmpty()
        assertThat(requests).allSatisfy { request ->
            assertThat(request.name).hasSizeBetween(2, 100)
            assertThat(request.discountAmount).isBetween(1, 1_000_000)
            assertThat(request.totalQuota).isBetween(100, 1_000)
        }
    }

    @Test
    fun `createCouponCampaignAdminRegisterRequest uses the name it is given`() {
        val request = createCouponCampaignAdminRegisterRequest(name = "신규 가입 쿠폰")

        assertThat(request.name).isEqualTo("신규 가입 쿠폰")
    }

    @Test
    fun `createCouponCampaignAdminRegisterRequest uses the discount amount it is given`() {
        val request = createCouponCampaignAdminRegisterRequest(discountAmount = 5_000)

        assertThat(request.discountAmount).isEqualTo(5_000)
    }

    @Test
    fun `createCouponCampaignAdminRegisterRequest uses the total quota it is given`() {
        val request = createCouponCampaignAdminRegisterRequest(totalQuota = 7)

        assertThat(request.totalQuota).isEqualTo(7)
    }
}
