package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The HTTP contract of issuing. Issuing refuses a surrounding transaction and each request reads what the last one committed, so this
 * class leaves the test transaction and empties the tables itself. The rules behind each answer are `CouponIssueIntegrationTest`'s.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CouponApiTest(
    private val databaseCleanUp: DatabaseCleanUp,
) : BaseWebApiAdapterTest() {
    companion object {
        private const val CAMPAIGNS = "/api/v1/coupon-campaigns"
    }

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `the first issue answers 201 with the coupon`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign(name = "신규 가입 쿠폰", discountAmount = 3_000)

        val body = assertThat(requestIssue(campaign.id, customer.id)).hasStatus(HttpStatus.CREATED).bodyJson()

        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.couponId").asNumber()
        body.extractingPath("$.data.campaignId").isEqualToLong(campaign.id)
        body.extractingPath("$.data.campaignName").isEqualTo("신규 가입 쿠폰")
        body.extractingPath("$.data.discountAmount").isEqualTo(3_000)
        body.extractingPath("$.data.issuedAt").isNotNull()
        assertThat(entityManager.countCoupons()).isOne()
        assertThat(entityManager.couponCampaignIssuedCount(campaign.id)).isOne()
    }

    @Test
    fun `a repeat request answers 200 with the same body and changes nothing`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = requestIssue(campaign.id, customer.id)

        val repeat = requestIssue(campaign.id, customer.id)

        assertThat(first).hasStatus(HttpStatus.CREATED)
        assertThat(repeat).hasStatusOk()
        assertThat(repeat.response.contentAsString).isEqualTo(first.response.contentAsString)
        assertThat(entityManager.countCoupons()).isOne()
        assertThat(entityManager.couponCampaignIssuedCount(campaign.id)).isOne()
    }

    @Test
    fun `an exhausted campaign answers 409 COUPON_CAMPAIGN_EXHAUSTED and its holder still gets the coupon with 200`() {
        val holder = prepareUser()
        val latecomer = prepareUser()
        val campaign = prepareCouponCampaign(totalQuota = 1)
        val first = requestIssue(campaign.id, holder.id)

        val body = assertThat(requestIssue(campaign.id, latecomer.id)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_CAMPAIGN_EXHAUSTED")

        val repeat = requestIssue(campaign.id, holder.id)
        assertThat(repeat).hasStatusOk()
        assertThat(repeat.response.contentAsString).isEqualTo(first.response.contentAsString)
        assertThat(entityManager.countCoupons()).isOne()
    }

    @Test
    fun `an unknown campaign answers 404 COUPON_CAMPAIGN_NOT_FOUND`() {
        val customer = prepareUser()

        val body = assertThat(requestIssue(999L, customer.id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()

        body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_CAMPAIGN_NOT_FOUND")
    }

    @Test
    fun `a request without a requester answers 401 and issues nothing`() {
        val campaign = prepareCouponCampaign()

        assertThat(mvc.post().uri("$CAMPAIGNS/${campaign.id}/issues")).hasStatus(HttpStatus.UNAUTHORIZED)

        assertThat(entityManager.countCoupons()).isZero()
    }

    /** The other user's own first request answering 201 shows that neither field gave them a coupon. */
    @Test
    fun `a user ID in the query or the body cannot name another user`() {
        val requester = prepareUser()
        val other = prepareUser()
        val campaign = prepareCouponCampaign()

        assertThat(
            mvc.post().uri("$CAMPAIGNS/${campaign.id}/issues?userId=${other.id}")
                .header(UserIdHeader.NAME, requester.id.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId": ${other.id}}"""),
        ).hasStatus(HttpStatus.CREATED)

        assertThat(requestIssue(campaign.id, other.id)).hasStatus(HttpStatus.CREATED)
        assertThat(requestIssue(campaign.id, requester.id)).hasStatusOk()
    }

    private fun requestIssue(campaignId: Long, userId: Long): MvcTestResult {
        return mvc.post().uri("$CAMPAIGNS/$campaignId/issues")
            .header(UserIdHeader.NAME, userId.toString())
            .exchange()
    }
}
