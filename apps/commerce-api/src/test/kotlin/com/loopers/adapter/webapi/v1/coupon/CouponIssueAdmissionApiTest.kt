package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.application.coupon.provided.CouponIssueAdmissionOpener
import com.loopers.config.redis.RedisConfig
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.captureSql
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.isEqualToLong
import com.loopers.support.loseTheSlotTokenOf
import com.loopers.support.test.BaseWebApiAdapterTest
import com.ninjasquad.springmockk.MockkSpyBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The HTTP answers of the coupon issue admission, closed from the start and closed again on a running app (spec 231). This context
 * starts with `admission-open-on-startup=false`, as an instance whose startup did not open admission does. Nothing closes an open
 * admission on demand, and an opened one stays open, so the whole sequence is this class's one scene, and this context, with its own
 * spy and property, is used by no other class. Admission is closed again the way `CouponIssueAdmissionClosingTest` closes it: a held
 * token removed from the Set during an issue.
 *
 * Issuing refuses a surrounding transaction and each request reads what the last one committed, so the class leaves the test
 * transaction and empties the tables itself, as `CouponApiTest` does.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = ["coupon-issue.admission-open-on-startup=false"])
class CouponIssueAdmissionApiTest(
    private val databaseCleanUp: DatabaseCleanUp,
    private val couponIssueAdmissionOpener: CouponIssueAdmissionOpener,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseWebApiAdapterTest() {
    /** Where the scene removes an issue's token. Unstubbed, it is the real issuing transaction. */
    @MockkSpyBean
    private lateinit var couponIssueSteps: CouponIssueSteps

    @AfterEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @Test
    fun `closed from the start answers 503 until the opening, and closes again when a held token vanishes`() {
        val campaign = prepareCouponCampaign()
        val customer = prepareUser()

        val captured = captureSql {
            val refused = assertThat(requestIssue(campaign.id, customer.id)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
            refused.headers().doesNotContainHeader("Retry-After")
            val body = refused.bodyJson()
            body.extractingPath("$.meta.result").isEqualTo("FAIL")
            body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_ISSUE_ADMISSION_CLOSED")
        }
        assertThat(captured.statements.filter { it.contains("for update", ignoreCase = true) }).isEmpty()
        assertThat(entityManager.countCoupons()).isZero()
        assertThat(entityManager.couponCampaignIssuedCount(campaign.id)).isZero()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).isEmpty()

        couponIssueAdmissionOpener.open()
        assertThat(requestIssue(campaign.id, customer.id)).hasStatus(HttpStatus.CREATED)

        val held = prepareCoupon(campaign)
        val losing = prepareUser()
        couponIssueSteps.loseTheSlotTokenOf(losing.id, masterRedisTemplate)
        val issueBody = assertThat(requestIssue(campaign.id, losing.id)).hasStatus(HttpStatus.CREATED).bodyJson()
        issueBody.extractingPath("$.data.campaignId").isEqualToLong(campaign.id)

        val closedBody = assertThat(requestIssue(campaign.id, prepareUser().id))
            .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
            .bodyJson()
        closedBody.extractingPath("$.meta.errorCode").isEqualTo("COUPON_ISSUE_ADMISSION_CLOSED")
        assertThat(entityManager.countCoupons()).isEqualTo(3L)
        assertThat(entityManager.couponCampaignIssuedCount(campaign.id)).isEqualTo(3)

        val repeatBody = assertThat(requestIssue(campaign.id, held.userId)).hasStatusOk().bodyJson()
        repeatBody.extractingPath("$.data.couponId").isEqualToLong(held.id)
        val listBody = assertThat(requestList(held.userId)).hasStatusOk().bodyJson()
        listBody.extractingPath("$.data.items[0].couponId").isEqualToLong(held.id)
    }

    private fun requestIssue(campaignId: Long, userId: Long): MvcTestResult {
        return mvc.post().uri("/api/v1/coupon-campaigns/$campaignId/issues")
            .header(UserIdHeader.NAME, userId.toString())
            .exchange()
    }

    private fun requestList(userId: Long): MvcTestResult {
        return mvc.get().uri("/api/v1/coupons")
            .header(UserIdHeader.NAME, userId.toString())
            .exchange()
    }
}
