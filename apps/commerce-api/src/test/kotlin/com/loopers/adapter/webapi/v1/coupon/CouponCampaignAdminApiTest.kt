package com.loopers.adapter.webapi.v1.coupon

import com.loopers.domain.coupon.CouponCampaign
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor

class CouponCampaignAdminApiTest : BaseWebApiAdapterTest() {
    companion object {
        private const val ENDPOINT = "/api-admin/v1/coupon-campaigns"
        private val ADMIN = user("admin").roles("ADMIN")
        private val USER = user("user").roles("USER")
    }

    @Test
    fun `admin registers a campaign and gets it back with its ID, nothing issued and issuing not suspended`() {
        val result = requestPostCampaign(name = "신규 가입 쿠폰", discountAmount = 3_000, totalQuota = 5)

        val body = assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.campaignId").asNumber()
        body.extractingPath("$.data.name").isEqualTo("신규 가입 쿠폰")
        body.extractingPath("$.data.discountAmount").isEqualTo(3_000)
        body.extractingPath("$.data.totalQuota").isEqualTo(5)
        body.extractingPath("$.data.issuedCount").isEqualTo(0)
        body.extractingPath("$.data.suspended").isEqualTo(false)
        assertThat(countCampaigns()).isOne()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `registering a blank name returns 400 and saves nothing`(name: String) {
        val body = assertThat(requestPostCampaign(name = name)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
        body.extractingPath("$.meta.message").asString().contains("쿠폰 캠페인 이름은 공백일 수 없습니다")

        assertThat(countCampaigns()).isZero()
    }

    @Test
    fun `registering a name of 101 chars returns 400 and saves nothing`() {
        assertThat(requestPostCampaign(name = "가".repeat(101))).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(countCampaigns()).isZero()
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `registering a discount amount below 1 returns 400 and saves nothing`(discountAmount: Long) {
        val body = assertThat(requestPostCampaign(discountAmount = discountAmount)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.message").asString().contains("할인액은 1원 이상이어야 합니다")

        assertThat(countCampaigns()).isZero()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `registering a total quota below 1 returns 400 and saves nothing`(totalQuota: Int) {
        val body = assertThat(requestPostCampaign(totalQuota = totalQuota)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
        body.extractingPath("$.meta.message").asString().contains("발급 한도는 1 이상이어야 합니다")

        assertThat(countCampaigns()).isZero()
    }

    @Test
    fun `registering as a user returns 403 and saves nothing`() {
        assertThat(requestPostCampaign(principal = USER)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(countCampaigns()).isZero()
    }

    @Test
    fun `registering anonymously returns 403 and saves nothing`() {
        assertThat(requestPostCampaign(principal = null)).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(countCampaigns()).isZero()
    }

    @Test
    fun `admin suspends a campaign and gets it back marked suspended`() {
        val campaign = prepareCouponCampaign(name = "신규 가입 쿠폰", discountAmount = 3_000, totalQuota = 5)

        val body = assertThat(requestSuspend(campaign.id)).hasStatusOk().bodyJson()

        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.campaignId").isEqualToLong(campaign.id)
        body.extractingPath("$.data.name").isEqualTo("신규 가입 쿠폰")
        body.extractingPath("$.data.discountAmount").isEqualTo(3_000)
        body.extractingPath("$.data.totalQuota").isEqualTo(5)
        body.extractingPath("$.data.issuedCount").isEqualTo(0)
        body.extractingPath("$.data.suspended").isEqualTo(true)
    }

    @Test
    fun `suspending a suspended campaign answers 200 with the same body`() {
        val campaign = prepareCouponCampaign()
        val first = requestSuspend(campaign.id)

        val again = requestSuspend(campaign.id)

        assertThat(again).hasStatusOk()
        assertThat(again.response.contentAsString).isEqualTo(first.response.contentAsString)
    }

    @Test
    fun `suspending an unknown campaign returns 404 COUPON_CAMPAIGN_NOT_FOUND`() {
        val body = assertThat(requestSuspend(999L)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()

        body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_CAMPAIGN_NOT_FOUND")
    }

    @Test
    fun `suspending as a user returns 403 and leaves the campaign not suspended`() {
        val campaign = prepareCouponCampaign()

        assertThat(requestSuspend(campaign.id, principal = USER)).hasStatus(HttpStatus.FORBIDDEN)

        entityManager.clear()
        assertThat(entityManager.find(CouponCampaign::class.java, campaign.id).suspended).isFalse()
    }

    private fun requestSuspend(campaignId: Long, principal: RequestPostProcessor? = ADMIN): MvcTestResult {
        return mvc.post().uri("$ENDPOINT/$campaignId/suspend")
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .exchange()
    }

    private fun requestPostCampaign(
        name: String = "신규 가입 쿠폰",
        discountAmount: Long = 1_000,
        totalQuota: Int = 100,
        principal: RequestPostProcessor? = ADMIN,
    ): MvcTestResult {
        val json = """{"name": "$name", "discountAmount": $discountAmount, "totalQuota": $totalQuota}"""

        return mvc.post().uri(ENDPOINT)
            .apply { principal?.let { with(it) } }
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()
    }

    private fun countCampaigns(): Long {
        return entityManager
            .createQuery("select count(c) from CouponCampaign c", Long::class.java)
            .singleResult
    }
}
