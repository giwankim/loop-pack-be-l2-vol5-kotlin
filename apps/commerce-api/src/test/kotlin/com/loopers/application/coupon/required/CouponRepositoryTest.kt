package com.loopers.application.coupon.required

import com.loopers.domain.coupon.createCoupon
import com.loopers.support.flushAndClear
import com.loopers.support.test.BaseRepositoryTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

/** What the database itself guards for a coupon: one per campaign and user, real rows behind both IDs, and a deleted row counting as missing (ADR 0015, 0016). */
class CouponRepositoryTest(
    private val couponRepository: CouponRepository,
) : BaseRepositoryTest() {
    @Test
    fun `saving a second coupon for the same campaign and user violates the unique constraint`() {
        val coupon = prepareCoupon()

        assertThrows<DataIntegrityViolationException> {
            couponRepository.save(createCoupon(campaignId = coupon.campaignId, userId = coupon.userId))
            entityManager.flushAndClear()
        }
    }

    @Test
    fun `a user may hold coupons of different campaigns and a campaign may issue to different users`() {
        val user = prepareUser()
        val campaign = prepareCouponCampaign()
        prepareCoupon(campaign = campaign, user = user)

        prepareCoupon(campaign = prepareCouponCampaign(), user = user)
        prepareCoupon(campaign = campaign, user = prepareUser())
        entityManager.flushAndClear()

        assertThat(couponRepository.findByCampaignIdAndUserId(campaign.id, user.id)).isNotNull()
    }

    @Test
    fun `saving a coupon for a user that does not exist violates the foreign key`() {
        val campaign = prepareCouponCampaign()

        assertThrows<DataIntegrityViolationException> {
            couponRepository.save(createCoupon(campaignId = campaign.id, userId = 999L))
            entityManager.flushAndClear()
        }
    }

    @Test
    fun `saving a coupon for a campaign that does not exist violates the foreign key`() {
        val user = prepareUser()

        assertThrows<DataIntegrityViolationException> {
            couponRepository.save(createCoupon(campaignId = 999L, userId = user.id))
            entityManager.flushAndClear()
        }
    }

    @Test
    fun `findByCampaignIdAndUserId is null for another user's pair and for a deleted coupon`() {
        val coupon = prepareCoupon()
        val other = prepareUser()
        entityManager.flushAndClear()

        assertThat(couponRepository.findByCampaignIdAndUserId(coupon.campaignId, coupon.userId)?.id).isEqualTo(coupon.id)
        assertThat(couponRepository.findByCampaignIdAndUserId(coupon.campaignId, other.id)).isNull()

        deleteCoupon(coupon)
        entityManager.flushAndClear()

        assertThat(couponRepository.findByCampaignIdAndUserId(coupon.campaignId, coupon.userId)).isNull()
    }
}
