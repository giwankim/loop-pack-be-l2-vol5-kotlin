package com.loopers.application.coupon.required

import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CouponCampaignRepositoryTest(
    private val couponCampaignRepository: CouponCampaignRepository,
) : BaseRepositoryTest() {
    @Test
    fun `findForUpdateById reads a campaign, and is null for an unknown and for a deleted one`() {
        val campaign = prepareCouponCampaign()
        entityManager.flushAndClear()

        assertThat(couponCampaignRepository.findForUpdateById(campaign.id)?.id).isEqualTo(campaign.id)
        assertThat(couponCampaignRepository.findForUpdateById(999L)).isNull()

        deleteCouponCampaign(campaign)
        entityManager.flushAndClear()

        assertThat(couponCampaignRepository.findForUpdateById(campaign.id)).isNull()
    }
}
