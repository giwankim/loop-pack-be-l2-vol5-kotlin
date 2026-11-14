package com.loopers.application.coupon.provided

import com.loopers.application.coupon.CouponIssueSteps
import com.loopers.application.coupon.required.CouponRepository
import com.loopers.config.redis.RedisConfig
import com.loopers.domain.coupon.Coupon
import com.loopers.domain.coupon.CouponCampaignExhaustedException
import com.loopers.domain.coupon.CouponCampaignSuspendedException
import com.loopers.domain.shared.Money
import com.loopers.support.EnableTestPg
import com.loopers.support.Pause
import com.loopers.support.TestClock
import com.loopers.support.captureSql
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.support.heldCouponIssueSlotTokens
import com.loopers.support.test.BaseCommittingApplicationServiceTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Issuing through the provided port on real MySQL. Every call commits, so results are read back in new transactions. The assignment's
 * `--tests '*CouponIssueIntegrationTest'` finds this class.
 *
 * The contention scenes release their calls together through a start gate and count the outcomes as successes, business rejections and
 * technical errors (spec 219 stories 4, 10, 33 and 34). They call the port, and step 2's processing slots sit inside the port, so
 * every scene passes through the slots at the default capacity of 10, so all 8 threads acquire a slot. The rollback scene is a
 * controlled injection: a spy makes the coupon's `save` throw after a flush. The lock scene pauses one issue inside its transaction
 * through a spy on the issuing step. Both check that the failed issue gave its slot back (spec 226 story 14): the rollback scene
 * finds the Set empty after the failure, and the lock scene finds only the paused holder's token after the waiter's lock failure.
 *
 * `@EnableTestPg` is here only because it is what puts the pinnable [TestClock] in the context. Nothing in this class calls the PG.
 */
@EnableTestPg
class CouponIssueIntegrationTest(
    private val couponIssuer: CouponIssuer,
    private val clock: TestClock,
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val masterRedisTemplate: RedisTemplate<String, String>,
) : BaseCommittingApplicationServiceTest() {
    /** Where the rollback scene throws. Unstubbed, it is the real repository. */
    @MockkSpyBean
    private lateinit var couponRepository: CouponRepository

    /** Where the lock scene's holder pauses. Unstubbed, it is the real issuing transaction. */
    @MockkSpyBean
    private lateinit var couponIssueSteps: CouponIssueSteps

    private val pause = Pause()

    @Test
    fun `the first issue commits one coupon with the campaign's name and discount amount and the issue time from the clock`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign(name = "신규 가입 쿠폰", discountAmount = 3_000)
        clock.pin(Instant.parse("2026-11-11T01:02:03.123456789Z"))

        val result = couponIssuer.issue(customer.id, campaign.id)

        val found = inNewTransaction { entityManager.find(Coupon::class.java, result.coupon.id) }
        assertThat(result.newlyIssued).isTrue()
        assertThat(found.campaignId).isEqualTo(campaign.id)
        assertThat(found.userId).isEqualTo(customer.id)
        assertThat(found.campaignName).isEqualTo("신규 가입 쿠폰")
        assertThat(found.discountAmount).isEqualTo(Money(3_000))
        assertThat(found.issuedAt).isEqualTo(Instant.parse("2026-11-11T01:02:03.123456Z"))
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    @Test
    fun `a repeat request answers with the same coupon and changes nothing`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = couponIssuer.issue(customer.id, campaign.id)

        val repeat = couponIssuer.issue(customer.id, campaign.id)

        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(first.coupon.id)
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    @Test
    fun `an exhausted campaign refuses a second user but still answers its holder`() {
        val holder = prepareUser()
        val latecomer = prepareUser()
        val campaign = prepareCouponCampaign(totalQuota = 1)
        val first = couponIssuer.issue(holder.id, campaign.id)

        assertThrows<CouponCampaignExhaustedException> { couponIssuer.issue(latecomer.id, campaign.id) }

        val repeat = couponIssuer.issue(holder.id, campaign.id)
        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(first.coupon.id)
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    @Test
    fun `a suspended campaign refuses a new user and keeps the coupons issued before the suspension`() {
        val holder = prepareUser()
        val latecomer = prepareUser()
        val campaign = prepareCouponCampaign(totalQuota = 5)
        val first = couponIssuer.issue(holder.id, campaign.id)
        suspendCouponCampaign(campaign)

        assertThrows<CouponCampaignSuspendedException> { couponIssuer.issue(latecomer.id, campaign.id) }

        val kept = inNewTransaction { entityManager.find(Coupon::class.java, first.coupon.id) }
        assertThat(kept.userId).isEqualTo(holder.id)
        assertThat(kept.issuedAt).isEqualTo(first.coupon.issuedAt)
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    @Test
    fun `a suspended campaign still answers its holder with the same coupon`() {
        val holder = prepareUser()
        val campaign = prepareCouponCampaign()
        val first = couponIssuer.issue(holder.id, campaign.id)
        suspendCouponCampaign(campaign)

        val repeat = couponIssuer.issue(holder.id, campaign.id)

        assertThat(repeat.newlyIssued).isFalse()
        assertThat(repeat.coupon.id).isEqualTo(first.coupon.id)
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    @Test
    fun `an unknown campaign is refused with COUPON_CAMPAIGN_NOT_FOUND`() {
        val customer = prepareUser()

        val exception = assertThrows<CoreException> { couponIssuer.issue(customer.id, 999L) }

        assertThat(exception.errorType).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND)
        assertThat(committedCouponCount()).isZero()
    }

    /** The first issue is the control: it shows that the filter would see a lock if a repeat sent one. */
    @Test
    fun `the first issue locks the campaign row and a holder's repeat request sends no FOR UPDATE`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()

        val first = captureSql { couponIssuer.issue(customer.id, campaign.id) }
        val repeat = captureSql { couponIssuer.issue(customer.id, campaign.id) }

        assertThat(lockingReads(first.statements)).hasSize(1).allSatisfy { assertThat(it).contains("coupon_campaign") }
        assertThat(lockingReads(repeat.statements)).isEmpty()
    }

    @Test
    fun `issuing inside a caller's transaction is refused and issues nothing`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()

        inNewTransaction {
            assertThrows<IllegalTransactionStateException> { couponIssuer.issue(customer.id, campaign.id) }
        }

        assertThat(committedCouponCount()).isZero()
        assertThat(committedIssuedCount(campaign)).isZero()
    }

    /**
     * The campaign row's lock lets the issues pass one at a time, and each reads the issued count the one before it committed. Without
     * the lock, issues read the same count and write it plus one. Two local runs without `FOR UPDATE` ended in 3 and 4 deadlocks rather
     * than an overrun, because each coupon's insert share-locks the campaign row through its foreign key before the count's update at commit.
     * That is an accident of the foreign key, not a guard: an issue that commits before another inserts still leaves a lost update.
     */
    @Test
    fun `eight users issuing from a campaign of five at once get five coupons and three exhausted refusals`() {
        val totalQuota = 5
        val campaign = prepareCouponCampaign(totalQuota = totalQuota)
        val customers = List(8) { prepareUser() }

        val results = runConcurrently(customers.map { customer -> { couponIssuer.issue(customer.id, campaign.id) } })

        val outcomes = tally(results, CouponCampaignExhaustedException::class)
        val expected = Outcomes(successes = totalQuota, rejections = customers.size - totalQuota, technicalErrors = 0)
        assertThat(outcomes).isEqualTo(expected)
        assertThat(outcomes.total).isEqualTo(customers.size)
        assertThat(results.mapNotNull { it.getOrNull() }).allSatisfy { assertThat(it.newlyIssued).isTrue() }
        val couponRows = committedCouponCount()
        val committedCount = committedIssuedCount(campaign)
        assertThat(couponRows).isEqualTo(outcomes.successes.toLong())
        assertThat(committedCount).isEqualTo(outcomes.successes)
        assertThat(committedCount).isEqualTo(totalQuota)
        log.info {
            "Quota scene: ${customers.size} users, total quota $totalQuota: issued ${outcomes.successes}, " +
                "exhausted ${outcomes.rejections}, technical errors ${outcomes.technicalErrors}; " +
                "coupon rows $couponRows, issued count $committedCount"
        }
    }

    /**
     * Both calls miss in the early read, so both reach the issuing transaction. The one that waited on the lock re-checks after the
     * first committed and answers with that coupon. Without the re-check, it would issue a second coupon and fail on the unique key.
     */
    @Test
    fun `one user issuing on two threads at once gets one coupon twice and nobody else sees it`() {
        val customer = prepareUser()
        val stranger = prepareUser()
        val campaign = prepareCouponCampaign()

        val results = runConcurrently(List(2) { { couponIssuer.issue(customer.id, campaign.id) } })

        val outcomes = tally(results)
        assertThat(outcomes).isEqualTo(Outcomes(successes = 2, rejections = 0, technicalErrors = 0))
        val answers = results.map { it.getOrThrow() }
        val couponIds = answers.map { it.coupon.id }
        val newlyIssued = answers.map { it.newlyIssued }
        assertThat(couponIds.distinct()).hasSize(1)
        assertThat(newlyIssued.count { it }).isOne()
        val couponRows = committedCouponCount()
        val committedCount = committedIssuedCount(campaign)
        assertThat(couponRows).isOne()
        assertThat(committedCount).isOne()
        assertThat(committedCouponsOf(customer.id).content.map { it.id }).containsExactly(couponIds.first())
        assertThat(committedCouponsOf(stranger.id).content).isEmpty()
        log.info {
            "Same-user scene: 2 threads: coupon IDs $couponIds, newly issued $newlyIssued; " +
                "coupon rows $couponRows, issued count $committedCount"
        }
    }

    /**
     * The stub flushes before it throws, as a mid-failure test does (`CODING_STANDARDS.md`), so the raised count's UPDATE has reached
     * MySQL when the coupon's insert fails. Only the rollback takes it back. The captured statements show the UPDATE went out and no
     * coupon insert did.
     */
    @Test
    fun `a coupon save that throws after the count's update rolls the count back, and the next issue gives one coupon`() {
        val customer = prepareUser()
        val campaign = prepareCouponCampaign()
        val failure = DataAccessResourceFailureException("The database failed at the coupon's insert.")
        every { couponRepository.save(any()) } answers {
            entityManager.flush()
            throw failure
        } andThenAnswer { callOriginal() }

        val captured = captureSql {
            assertThrows<DataAccessResourceFailureException> { couponIssuer.issue(customer.id, campaign.id) }
        }

        assertThat(captured.statements.filter { it.startsWith("update coupon_campaign") }).hasSize(1)
        assertThat(captured.statements.filter { it.startsWith("insert into coupon ") }).isEmpty()
        assertThat(committedCouponCount()).isZero()
        assertThat(committedIssuedCount(campaign)).isZero()
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).isEmpty()

        val retried = couponIssuer.issue(customer.id, campaign.id)

        assertThat(retried.newlyIssued).isTrue()
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    /**
     * The holder is another issue, paused inside its issuing transaction after its own coupon's insert, so it holds the campaign's
     * lock. The lock wait is 3 seconds (ADR 0018 rule 7), and the holder pauses for longer. The waiting issue's port throws a
     * [PessimisticLockingFailureException], which `ApiControllerAdvice` answers with 409 `CONCURRENT_REQUEST` (rule 8,
     * `LockFailureApiTest`). What commits afterwards is the holder's coupon alone.
     */
    @Test
    fun `an issue that waits on a held campaign lock past the timeout fails as a lock failure and issues nothing`() {
        val holder = prepareUser()
        val waiter = prepareUser()
        val campaign = prepareCouponCampaign()
        pauseIssuingUnderLock(holder.id)

        val holding = inAnotherThread { couponIssuer.issue(holder.id, campaign.id) }
        pause.awaitHeld()

        assertThrows<PessimisticLockingFailureException> { couponIssuer.issue(waiter.id, campaign.id) }
        assertThat(masterRedisTemplate.heldCouponIssueSlotTokens()).hasSize(1)

        pause.release()
        assertThat(holding.await().newlyIssued).isTrue()
        assertThat(committedCouponsOf(waiter.id).content).isEmpty()
        assertThat(committedCouponCount()).isOne()
        assertThat(committedIssuedCount(campaign)).isOne()
    }

    /** [userId]'s issuing transaction pauses after its writes, with the campaign still locked. Other users' issues are not stubbed. */
    private fun pauseIssuingUnderLock(userId: Long) {
        val target = AopTestUtils.getUltimateTargetObject<CouponIssueSteps>(couponIssueSteps)
        every { target.issueUnderLock(userId, any()) } answers {
            callOriginal().also { pause.hold() }
        }
    }

    private fun lockingReads(statements: List<String>): List<String> {
        return statements.filter { it.contains("for update", ignoreCase = true) }
    }
}
