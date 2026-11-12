package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.application.coupon.provided.CouponIssueAdmissionOpener
import com.loopers.config.redis.RedisConfig
import com.loopers.support.CONFIGURED_COUPON_ISSUE_INSTANCE_ID
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.awaitCommandSucceeds
import com.loopers.support.captureSql
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.isEqualToLong
import com.loopers.support.stopRedisDuringTheIssueOf
import com.loopers.support.test.BaseWebApiAdapterTest
import com.loopers.testcontainers.StoppableRedis
import com.loopers.testcontainers.StoppableRedisTestContainersConfig
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.time.measureTimedValue

private val log = KotlinLogging.logger {}

/**
 * A real Redis outage closes the coupon issue admission, and admission stays closed until it is opened (spec 231, ADR 0037). Every
 * scene goes through HTTP on real MySQL, and the outage is the Redis the app uses, stopped or paused, never an exception a mock throws.
 * Admission's state is never read: each scene sees it in the next request's answer.
 *
 * The class imports [StoppableRedisTestContainersConfig] for a Redis of its own, so stopping it reaches no other class. Issuing
 * refuses a surrounding transaction and each request reads what the last one committed, so the class leaves the test transaction and
 * empties the tables itself, as `CouponIssueAdmissionApiTest` does. The fault inside an issue is injected with a spy on
 * [CouponIssueSteps.issueUnderLock], as `CouponIssueSlotsIntegrationTest` holds its issues.
 *
 * Each scene logs what it observed in one INFO line after its assertions, read back with `scripts/scene-lines`.
 */
@Import(StoppableRedisTestContainersConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CouponIssueRedisFailureApiTest(
    private val databaseCleanUp: DatabaseCleanUp,
    private val couponIssueAdmissionOpener: CouponIssueAdmissionOpener,
    private val stoppableRedis: StoppableRedis,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseWebApiAdapterTest() {
    companion object {
        /**
         * The bound on a refusal in front of a paused Redis. The acquire waits out the command timeout (100 ms) and the early lookup
         * reads the database, so this is generous; the scene does not assert the timeout itself.
         */
        private val REFUSAL_BOUND: Duration = Duration.ofSeconds(2)
    }

    /** Where a scene stops Redis inside an issue. Unstubbed, it is the real issuing transaction. */
    @MockkSpyBean
    private lateinit var couponIssueSteps: CouponIssueSteps

    /**
     * The first handshake of a new context is bound by the command timeout too, so the startup's opening may have failed and left
     * admission closed, as it would on an instance that booted with Redis down. Each scene starts from a connection that works and
     * an open admission. Opening an open admission does nothing.
     */
    @BeforeEach
    fun openAdmission() {
        masterRedisTemplate.awaitCommandSucceeds()
        couponIssueAdmissionOpener.open()
    }

    /**
     * Empties the tables first, so a failed opening cannot leave rows to the next scene, then brings Redis back and opens admission
     * again, so a scene that closed it does not reach the next one. Runs before the base's Redis flush, which needs Redis back.
     */
    @AfterEach
    fun restoreRedisAndOpenAdmission() {
        databaseCleanUp.truncateAllTables()
        stoppableRedis.restore()
        masterRedisTemplate.awaitCommandSucceeds()
        couponIssueAdmissionOpener.open()
    }

    @Test
    fun `with Redis stopped a new user gets 503 without any SQL lock, while a holder gets the same coupon and the list`() {
        val campaign = prepareCouponCampaign()
        val held = prepareCoupon(campaign)
        val newcomer = prepareUser()
        stoppableRedis.stop()

        val captured = captureSql { assertAdmissionClosed(requestIssue(campaign.id, newcomer.id)) }
        val body = assertThat(requestIssue(campaign.id, held.userId)).hasStatusOk().bodyJson()
        val list = assertThat(requestList(held.userId)).hasStatusOk().bodyJson()
        val newcomerList = assertThat(requestList(newcomer.id)).hasStatusOk().bodyJson()

        val locks = captured.statements.filter { it.contains("for update", ignoreCase = true) }
        val couponRows = entityManager.countCoupons()
        val issuedCount = entityManager.couponCampaignIssuedCount(campaign.id)
        assertThat(locks).isEmpty()
        assertThat(couponRows).isOne()
        assertThat(issuedCount).isOne()
        body.extractingPath("$.data.couponId").isEqualToLong(held.id)
        list.extractingPath("$.data.items[0].couponId").isEqualToLong(held.id)
        newcomerList.extractingPath("$.data.items").asArray().isEmpty()
        log.info {
            "Stopped Redis: new user 503 COUPON_ISSUE_ADMISSION_CLOSED with ${locks.size} FOR UPDATE; holder 200 with the same " +
                "coupon, list 200; coupon rows $couponRows (the holder's), issued count $issuedCount"
        }
    }

    /**
     * A `PONG` is not a Set to trust: Redis answering again opens nothing. Making a failed acquire leave admission open turns this
     * scene and the paused one red, since the first request after the start or the unpause is then issued (spec 231's first red
     * check, run as a local edit and never committed).
     */
    @Test
    fun `after Redis starts again a new user still gets 503, and is issued only after the opening`() {
        val campaign = prepareCouponCampaign()
        val first = prepareUser()
        val second = prepareUser()
        stoppableRedis.stop()
        assertAdmissionClosed(requestIssue(campaign.id, first.id))

        stoppableRedis.start()
        masterRedisTemplate.awaitCommandSucceeds()

        assertAdmissionClosed(requestIssue(campaign.id, second.id))
        assertThat(entityManager.countCoupons()).isZero()
        couponIssueAdmissionOpener.open()
        assertThat(requestIssue(campaign.id, second.id)).hasStatus(HttpStatus.CREATED)
        val couponRows = entityManager.countCoupons()
        val issuedCount = entityManager.couponCampaignIssuedCount(campaign.id)
        assertThat(couponRows).isOne()
        assertThat(issuedCount).isOne()
        log.info {
            "Restarted Redis: 503 during the outage, still 503 after the start with Redis answering, 201 after the opening; " +
                "coupon rows $couponRows, issued count $issuedCount"
        }
    }

    @Test
    fun `with Redis paused a new user gets 503 within the bound, and admission is still closed after unpausing`() {
        val campaign = prepareCouponCampaign()
        val first = prepareUser()
        val second = prepareUser()
        stoppableRedis.pause()

        val (refused, elapsed) = measureTimedValue { requestIssue(campaign.id, first.id) }
        stoppableRedis.unpause()
        masterRedisTemplate.awaitCommandSucceeds()
        val afterUnpause = requestIssue(campaign.id, second.id)

        assertAdmissionClosed(refused)
        assertThat(elapsed.inWholeMilliseconds).isLessThan(REFUSAL_BOUND.toMillis())
        assertAdmissionClosed(afterUnpause)
        val couponRows = entityManager.countCoupons()
        val issuedCount = entityManager.couponCampaignIssuedCount(campaign.id)
        assertThat(couponRows).isZero()
        assertThat(issuedCount).isZero()
        log.info {
            "Paused Redis: new user 503 in ${elapsed.inWholeMilliseconds} ms (bound ${REFUSAL_BOUND.toMillis()} ms), " +
                "still 503 after unpausing; coupon rows $couponRows, issued count $issuedCount"
        }
    }

    /**
     * The assignment's "반환 실패·복구": the coupon commits, its release fails, and the token stays. Redis keeps the Set across the stop,
     * so the token is still there after the start, and only the opening removes it. Taking the cleanup out of the opening turns this
     * scene red at the empty Set (spec 231's second red check).
     */
    @Test
    fun `with Redis stopped during the issue the coupon is issued, and its leftover token is removed only by the opening`() {
        val campaign = prepareCouponCampaign()
        val stopping = prepareUser()
        val afterStart = prepareUser()
        couponIssueSteps.stopRedisDuringTheIssueOf(stopping.id, stoppableRedis)

        val body = assertThat(requestIssue(campaign.id, stopping.id)).hasStatus(HttpStatus.CREATED).bodyJson()
        val couponRows = entityManager.countCoupons()
        val issuedCount = entityManager.couponCampaignIssuedCount(campaign.id)
        stoppableRedis.start()
        masterRedisTemplate.awaitCommandSucceeds()
        val leftover = masterRedisTemplate.heldCouponIssueSlotTokens()
        val refused = requestIssue(campaign.id, afterStart.id)
        couponIssueAdmissionOpener.open()
        val afterOpening = masterRedisTemplate.heldCouponIssueSlotTokens()
        val issued = requestIssue(campaign.id, afterStart.id)

        body.extractingPath("$.data.campaignId").isEqualToLong(campaign.id)
        assertThat(couponRows).isOne()
        assertThat(issuedCount).isOne()
        assertThat(leftover).singleElement().satisfies({ assertThat(it).startsWith("$CONFIGURED_COUPON_ISSUE_INSTANCE_ID:") })
        assertAdmissionClosed(refused)
        assertThat(afterOpening).isEmpty()
        assertThat(issued).hasStatus(HttpStatus.CREATED)
        log.info {
            "Release failed: 201 with coupon rows $couponRows, issued count $issuedCount; after the start ${leftover.size} " +
                "token left in the Set and the next user 503; after the opening ${afterOpening.size} tokens and the next user 201"
        }
    }

    /**
     * A business rejection is not replaced by a server error: the issuing transaction refuses as exhausted, the release after it
     * fails on a stopped Redis, and the request still answers the 409 (spec 231's story 6). The next user's 503 shows that the
     * release did fail and closed admission.
     */
    @Test
    fun `with Redis stopped during the issue an exhausted campaign still answers 409, and admission is closed`() {
        val campaign = prepareCouponCampaign(totalQuota = 1)
        prepareCoupon(campaign)
        val latecomer = prepareUser()
        val afterStart = prepareUser()
        couponIssueSteps.stopRedisDuringTheIssueOf(latecomer.id, stoppableRedis)

        val error = assertThat(requestIssue(campaign.id, latecomer.id)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        stoppableRedis.start()
        masterRedisTemplate.awaitCommandSucceeds()
        val refused = requestIssue(campaign.id, afterStart.id)

        error.extractingPath("$.meta.errorCode").isEqualTo("COUPON_CAMPAIGN_EXHAUSTED")
        assertAdmissionClosed(refused)
        val couponRows = entityManager.countCoupons()
        val issuedCount = entityManager.couponCampaignIssuedCount(campaign.id)
        assertThat(couponRows).isOne()
        assertThat(issuedCount).isOne()
        log.info {
            "Release failed after a rejection: 409 COUPON_CAMPAIGN_EXHAUSTED, and the next user 503 after the start; " +
                "coupon rows $couponRows (the holder's), issued count $issuedCount"
        }
    }

    /** A 503 that names the closed admission as its cause, not any other server error. */
    private fun assertAdmissionClosed(result: MvcTestResult) {
        val body = assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).bodyJson()
        body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_ISSUE_ADMISSION_CLOSED")
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
