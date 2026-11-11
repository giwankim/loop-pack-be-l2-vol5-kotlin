package com.loopers.domain.coupon

import com.loopers.domain.shared.InvalidNameException
import com.loopers.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Constructor arguments a test does not look at come from `valid`, a fixture campaign whose values pass the rules. */
class CouponCampaignTest {
    private val valid = createCouponCampaign()

    @Test
    fun `a new campaign keeps what it was given, has issued nothing and is issuable`() {
        val campaign = CouponCampaign(name = valid.name, discountAmount = valid.discountAmount, totalQuota = valid.totalQuota)

        assertThat(campaign.name).isEqualTo(valid.name)
        assertThat(campaign.discountAmount).isEqualTo(valid.discountAmount)
        assertThat(campaign.totalQuota).isEqualTo(valid.totalQuota)
        assertThat(campaign.issuedCount).isZero()
        assertThat(campaign.issuable).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t\n"])
    fun `a blank name throws InvalidNameException`(name: String) {
        assertThrows<InvalidNameException> {
            CouponCampaign(name = name, discountAmount = valid.discountAmount, totalQuota = valid.totalQuota)
        }
    }

    @Test
    fun `a name of 101 chars throws InvalidNameException`() {
        assertThrows<InvalidNameException> {
            CouponCampaign(name = "가".repeat(101), discountAmount = valid.discountAmount, totalQuota = valid.totalQuota)
        }
    }

    @Test
    fun `a name of 100 chars is accepted`() {
        val name = "가".repeat(100)

        val campaign = CouponCampaign(name = name, discountAmount = valid.discountAmount, totalQuota = valid.totalQuota)

        assertThat(campaign.name).isEqualTo(name)
    }

    @Test
    fun `a discount amount of 0 throws InvalidDiscountAmountException`() {
        assertThrows<InvalidDiscountAmountException> {
            CouponCampaign(name = valid.name, discountAmount = Money.ZERO, totalQuota = valid.totalQuota)
        }
    }

    @Test
    fun `a discount amount of 1 is accepted`() {
        val campaign = CouponCampaign(name = valid.name, discountAmount = Money(1), totalQuota = valid.totalQuota)

        assertThat(campaign.discountAmount).isEqualTo(Money(1))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `a total quota below 1 throws InvalidTotalQuotaException`(totalQuota: Int) {
        assertThrows<InvalidTotalQuotaException> {
            CouponCampaign(name = valid.name, discountAmount = valid.discountAmount, totalQuota = totalQuota)
        }
    }

    @Test
    fun `a total quota of 1 is accepted`() {
        val campaign = CouponCampaign(name = valid.name, discountAmount = valid.discountAmount, totalQuota = 1)

        assertThat(campaign.totalQuota).isEqualTo(1)
    }
}
