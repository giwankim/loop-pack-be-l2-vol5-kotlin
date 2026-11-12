package com.loopers.adapter.webapi.v1.coupon

import com.loopers.adapter.webapi.UserIdHeader
import com.loopers.application.coupon.required.CouponIssueSlots
import com.loopers.config.redis.RedisConfig
import com.loopers.support.DatabaseCleanUp
import com.loopers.support.countCoupons
import com.loopers.support.couponCampaignIssuedCount
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.isEqualToLong
import com.loopers.support.test.BaseWebApiAdapterTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The HTTP contract of issuing and of the own-coupon list. Issuing refuses a surrounding transaction and each request reads what the
 * last one committed, so this class leaves the test transaction and empties the tables itself. The rules behind each issue answer are
 * `CouponIssueIntegrationTest`'s, and the list's query is `CouponFinderTest`'s.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CouponApiTest(
    private val databaseCleanUp: DatabaseCleanUp,
    private val jdbc: JdbcTemplate,
    private val couponIssueSlots: CouponIssueSlots,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
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
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).isEmpty()
    }

    @Test
    fun `with every slot taken an issue answers 429 COUPON_ISSUE_CAPACITY_EXCEEDED and issues nothing`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        takeEverySlot()

        val body = assertThat(requestIssue(campaign.id, customer.id)).hasStatus(HttpStatus.TOO_MANY_REQUESTS).bodyJson()

        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_ISSUE_CAPACITY_EXCEEDED")
        assertThat(entityManager.countCoupons()).isZero()
        assertThat(entityManager.couponCampaignIssuedCount(campaign.id)).isZero()
    }

    @Test
    fun `with every slot taken a holder's repeat request still answers 200 with the same body`() {
        val holder = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = requestIssue(campaign.id, holder.id)
        takeEverySlot()

        val repeat = requestIssue(campaign.id, holder.id)

        assertThat(repeat).hasStatusOk()
        assertThat(repeat.response.contentAsString).isEqualTo(first.response.contentAsString)
    }

    @Test
    fun `with every slot taken the coupon list still answers`() {
        val holder = prepareUser()
        val issued = prepareCoupon(user = holder).id
        takeEverySlot()

        val body = assertThat(requestList(holder.id)).hasStatusOk().bodyJson()

        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].couponId").isEqualToLong(issued)
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
    fun `a suspended campaign answers 409 COUPON_CAMPAIGN_SUSPENDED and its holder still gets the coupon with 200`() {
        val holder = prepareUser()
        val latecomer = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = requestIssue(campaign.id, holder.id)
        suspendCouponCampaign(campaign)

        val body = assertThat(requestIssue(campaign.id, latecomer.id)).hasStatus(HttpStatus.CONFLICT).bodyJson()
        body.extractingPath("$.meta.result").isEqualTo("FAIL")
        body.extractingPath("$.meta.errorCode").isEqualTo("COUPON_CAMPAIGN_SUSPENDED")

        val repeat = requestIssue(campaign.id, holder.id)
        assertThat(repeat).hasStatusOk()
        assertThat(repeat.response.contentAsString).isEqualTo(first.response.contentAsString)
        assertThat(entityManager.countCoupons()).isOne()
        assertThat(entityManager.couponCampaignIssuedCount(campaign.id)).isOne()
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

    /**
     * Coupons from several campaigns come newest first, and two with the same issue time put the larger ID first. A coupon sets its
     * own issue time, so SQL lays the times on top of each other.
     */
    @Test
    fun `the coupon list is newest first and equal issue times break by descending id`() {
        val customer = prepareUser()
        val tied = prepareCoupon(user = customer).id
        val tiedLater = prepareCoupon(user = customer).id
        // Issued last but dated earliest, so an order by ID alone would put it first.
        val oldestCampaign = prepareCouponCampaign(name = "가장 오래된 쿠폰", discountAmount = 1_000)
        val oldest = prepareCoupon(oldestCampaign, customer).id
        jdbc.update("update coupon set issued_at = '2026-11-10 10:00:00.000000' where id = ?", oldest)
        jdbc.update("update coupon set issued_at = '2026-11-11 10:00:00.000000' where id in (?, ?)", tied, tiedLater)

        val body = assertThat(requestList(customer.id)).hasStatusOk().bodyJson()

        body.extractingPath("$.meta.result").isEqualTo("SUCCESS")
        body.extractingPath("$.data.page").isEqualTo(0)
        body.extractingPath("$.data.size").isEqualTo(20)
        body.extractingPath("$.data.hasNext").isEqualTo(false)
        body.extractingPath("$.data.items.length()").isEqualTo(3)
        body.extractingPath("$.data.items[0].couponId").isEqualToLong(tiedLater)
        body.extractingPath("$.data.items[1].couponId").isEqualToLong(tied)
        body.extractingPath("$.data.items[2].couponId").isEqualToLong(oldest)
        body.extractingPath("$.data.items[2].campaignId").isEqualToLong(oldestCampaign.id)
        body.extractingPath("$.data.items[2].campaignName").isEqualTo("가장 오래된 쿠폰")
        body.extractingPath("$.data.items[2].discountAmount").isEqualTo(1_000)
        body.extractingPath("$.data.items[2].issuedAt").isEqualTo("2026-11-10T10:00:00Z")
    }

    /** Coupons are issued one after another, so the newest is the last issued. Each page's IDs are joined to see that none repeats or is missing. */
    @Test
    fun `paging through the coupon list returns every coupon once and hasNext is false on the last page`() {
        val customer = prepareUser()
        val issued = List(3) { prepareCoupon(user = customer).id }

        val listed = listOf(true, false, false).flatMapIndexed { page, hasNext ->
            val body = assertThat(requestList(customer.id, "page" to "$page", "size" to "2")).hasStatusOk().bodyJson()
            body.extractingPath("$.data.page").isEqualTo(page)
            body.extractingPath("$.data.size").isEqualTo(2)
            body.extractingPath("$.data.hasNext").isEqualTo(hasNext)
            body.extractingPath("$.data.items[*].couponId").asArray().actual().map { (it as Number).toLong() }
        }

        assertThat(listed).containsExactlyElementsOf(issued.reversed())
    }

    /** A `userId` in the query is ignored: the requester header alone names whose coupons are listed. */
    @Test
    fun `the coupon list shows only the requester's coupons`() {
        val customer = prepareUser()
        val other = prepareUser()
        val own = prepareCoupon(user = customer).id
        val foreign = prepareCoupon(couponCampaign, other).id

        val body = assertThat(requestList(customer.id, "userId" to "${other.id}")).hasStatusOk().bodyJson()
        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].couponId").isEqualToLong(own)

        val list = assertThat(requestList(other.id)).hasStatusOk().bodyJson()
        list.extractingPath("$.data.items.length()").isEqualTo(1)
        list.extractingPath("$.data.items[0].couponId").isEqualToLong(foreign)
    }

    @Test
    fun `a user without coupons gets an empty coupon list`() {
        prepareCoupon()

        val body = assertThat(requestList(prepareUser().id)).hasStatusOk().bodyJson()

        body.extractingPath("$.data.items").asArray().isEmpty()
        body.extractingPath("$.data.hasNext").isEqualTo(false)
    }

    @Test
    fun `listing coupons without a requester answers 401`() {
        assertThat(requestList(null)).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the coupon list still shows a coupon after its campaign is exhausted`() {
        val holder = prepareUser()
        val campaign = prepareCouponCampaign(totalQuota = 1)
        val issued = prepareCoupon(campaign, holder).id
        assertThat(requestIssue(campaign.id, prepareUser().id)).hasStatus(HttpStatus.CONFLICT)

        val body = assertThat(requestList(holder.id)).hasStatusOk().bodyJson()

        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].couponId").isEqualToLong(issued)
    }

    @Test
    fun `the coupon list still shows a coupon after its campaign is suspended`() {
        val holder = prepareUser()
        val issued = prepareCoupon(user = holder).id
        suspendCouponCampaign()

        val body = assertThat(requestList(holder.id)).hasStatusOk().bodyJson()

        body.extractingPath("$.data.items.length()").isEqualTo(1)
        body.extractingPath("$.data.items[0].couponId").isEqualToLong(issued)
    }

    @Test
    fun `listing coupons outside the page and size bounds returns 400`() {
        val customer = prepareUser()

        listOf(
            ("page" to "-1") to "page는 0 이상이어야 합니다",
            ("size" to "0") to "size는 1 이상이어야 합니다",
            ("size" to "101") to "size는 100 이하여야 합니다",
        ).forEach { (query, message) ->
            val body = assertThat(requestList(customer.id, query)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
            body.extractingPath("$.meta.errorCode").isEqualTo("Bad Request")
            body.extractingPath("$.meta.message").asString().contains(message)
        }
    }

    /** Acquires through the port until it answers `null`, whatever the configured capacity is. */
    private fun takeEverySlot() {
        val tokens = generateSequence { couponIssueSlots.acquire() }.toList()
        assertThat(tokens).isNotEmpty()
    }

    private fun requestList(userId: Long?, vararg query: Pair<String, String>): MvcTestResult {
        return mvc.get().uri("/api/v1/coupons")
            .apply { userId?.let { header(UserIdHeader.NAME, it.toString()) } }
            .apply { query.forEach { (name, value) -> param(name, value) } }
            .exchange()
    }

    private fun requestIssue(campaignId: Long, userId: Long): MvcTestResult {
        return mvc.post().uri("$CAMPAIGNS/$campaignId/issues")
            .header(UserIdHeader.NAME, userId.toString())
            .exchange()
    }
}
