package com.loopers.application.coupon.provided

import com.loopers.domain.coupon.CouponCampaign
import com.loopers.domain.coupon.createCouponCampaignAdminRegisterRequest
import com.loopers.domain.shared.Money
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
        assertThat(found.issuable).isTrue()
    }

    @Test
    fun `a blank name is rejected by request validation before the domain and saves nothing`() {
        val exception = assertThrows<ConstraintViolationException> {
            couponCampaignRegister.register(createCouponCampaignAdminRegisterRequest(name = "   "))
        }

        assertThat(exception.constraintViolations.map { it.message }).containsExactly("쿠폰 캠페인 이름은 공백일 수 없습니다.")
        assertThat(countCampaigns()).isZero()
    }

    private fun countCampaigns(): Long {
        return inNewTransaction {
            entityManager.createQuery("select count(c) from CouponCampaign c", Long::class.java).singleResult
        }
    }
}
