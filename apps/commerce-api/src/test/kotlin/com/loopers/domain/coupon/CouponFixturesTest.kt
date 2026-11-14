package com.loopers.domain.coupon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Draws many defaults so a default outside its range shows here every time, rather than failing some other test now and then (ADR 0010). */
class CouponFixturesTest {
    companion object {
        private const val SAMPLES = 1_000
    }

    @Test
    fun `createCoupon builds unsaved coupons of the given campaign and user with values the columns hold`() {
        val coupons = List(SAMPLES) { createCoupon(campaignId = 1L, userId = 2L) }

        assertThat(coupons).allSatisfy { coupon ->
            assertThat(coupon.id).isZero()
            assertThat(coupon.deletedAt).isNull()
            assertThat(coupon.campaignId).isEqualTo(1L)
            assertThat(coupon.userId).isEqualTo(2L)
            assertThat(coupon.campaignName.length).isBetween(2, CouponCampaign.NAME_MAX_LENGTH)
            assertThat(coupon.discountAmount.amount.toLong()).isBetween(1L, 1_000_000L)
        }
    }
}
