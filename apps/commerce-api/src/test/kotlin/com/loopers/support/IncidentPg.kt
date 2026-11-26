package com.loopers.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.loopers.domain.payment.CreditCard
import com.loopers.domain.payment.PgStatus
import com.loopers.domain.payment.PgTransaction
import com.loopers.domain.shared.Money

/**
 * The incident fixture (spec 274, "사고 fixture"): the test PG as one incident sees it. [TestPg] stays stateless. This class holds
 * the PG transactions it registered, one per order, under a transaction key it mints, and the response-loss switch.
 *
 * Every registered order's PG transaction is `SUCCESS`: that is what a lookup by its PG order ID finds. Orders differ in how the
 * create is answered:
 * - a normal order's create is accepted as `PENDING`, and the order finishes when the test plays the PG's 알림;
 * - a lost order's create is accepted as `SUCCESS` while response loss is off. While it is on, the PG makes the transaction and
 *   drops the connection without an answer.
 *
 * The loss is a stub over the lost order's accepting stub. WireMock matches the most recently added of two stubs with the same
 * priority, so the loss stub answers while it exists, and removing it lets the accepting stub answer again. Normal orders are
 * never lost, even while the loss is on.
 */
class IncidentPg(server: WireMockServer) {
    private val testPg = TestPg(server)

    private val transactions = mutableListOf<PgTransaction>()

    /** The create of each lost order, by PG order ID, so the loss stubs can be added again when the loss is started. */
    private val lostCreates = mutableMapOf<String, LostCreate>()

    /** The loss stubs in place. Empty while response loss is off. */
    private val lossStubs = mutableListOf<StubMapping>()

    private var responseLoss = false

    /**
     * A normal order: the create is accepted as `PENDING`, and the lookup finds the transaction `SUCCESS`. The PG sends no 알림
     * itself, so the order finishes when the test sends one. Returns the transaction the PG holds.
     */
    fun registerNormal(pgOrderId: String, amount: Long, card: CreditCard): PgTransaction {
        val transaction = hold(pgOrderId, amount)
        testPg.acceptsCreate(pgOrderId, amount, card, transaction.transactionKey, pgStatus = PgStatus.PENDING.name)

        return transaction
    }

    /**
     * A lost order: the create makes the `SUCCESS` transaction, and its response is lost while response loss is on. The lookup
     * finds the transaction. Returns the transaction the PG holds.
     */
    fun registerLost(pgOrderId: String, amount: Long, card: CreditCard): PgTransaction {
        val transaction = hold(pgOrderId, amount)
        testPg.acceptsCreate(pgOrderId, amount, card, transaction.transactionKey, pgStatus = PgStatus.SUCCESS.name)
        lostCreates[pgOrderId] = LostCreate(amount, card)
        if (responseLoss) {
            lossStubs += testPg.losesCreateResponse(pgOrderId, amount, card)
        }

        return transaction
    }

    /** Starts losing the create responses of the lost orders, those registered so far and those registered while it is on. */
    fun startResponseLoss() {
        check(!responseLoss) { "Response loss is already on." }
        responseLoss = true
        lostCreates.forEach { (pgOrderId, create) ->
            lossStubs += testPg.losesCreateResponse(pgOrderId, create.amount, create.card)
        }
    }

    /** Stops losing responses. Safe to call when the loss is off, so a `finally` can always release it. */
    fun stopResponseLoss() {
        lossStubs.forEach { testPg.remove(it) }
        lossStubs.clear()
        responseLoss = false
    }

    fun isResponseLossOn(): Boolean {
        return responseLoss
    }

    /** The PG transactions registered so far, in the order they were registered. */
    fun transactions(): List<PgTransaction> {
        return transactions.toList()
    }

    /** The PG order IDs that received at least one create, from the PG's request log, registered or not. */
    fun createdPgOrderIds(): Set<String> {
        return testPg.createdPgOrderIds().toSet()
    }

    /** Every create the PG received, answered, lost or unmatched. */
    fun createCount(): Int {
        return testPg.createCount()
    }

    /** The creates the PG received for [pgOrderId], answered or not. */
    fun createCount(pgOrderId: String): Int {
        return testPg.createCount(pgOrderId)
    }

    /**
     * Every PG order ID that received more than one create, from the PG's request log, registered or not. A second create could
     * make a second transaction, a second charge, so the incident test checks this is empty at every phase.
     */
    fun pgOrderIdsCreatedMoreThanOnce(): Set<String> {
        return testPg.createdPgOrderIds().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }

    /** The list lookups the PG received for [pgOrderId]. */
    fun listLookupCount(pgOrderId: String): Int {
        return testPg.listLookupCount(pgOrderId)
    }

    /** Every request the PG received, whatever its method and path, matched or not. */
    fun requestCount(): Int {
        return testPg.requestCount()
    }

    /** The requests that matched no stub, as [TestPg.unmatchedRequests]. */
    fun unmatchedRequests(): List<LoggedRequest> {
        return testPg.unmatchedRequests()
    }

    /** Holds a `SUCCESS` transaction for [pgOrderId] under a new key, and lets the list lookup find it. */
    private fun hold(pgOrderId: String, amount: Long): PgTransaction {
        check(transactions.none { it.pgOrderId == pgOrderId }) { "PG order ID $pgOrderId is already registered." }
        val transaction = PgTransaction(
            transactionKey = "20261126:TR:incident-${transactions.size + 1}",
            pgOrderId = pgOrderId,
            amount = Money(amount),
            status = PgStatus.SUCCESS,
        )
        transactions += transaction
        testPg.hasTransactions(pgOrderId, transaction)

        return transaction
    }
}

/** What a lost order's create carries besides its PG order ID, so its loss stub can be laid again. */
private data class LostCreate(
    val amount: Long,
    val card: CreditCard,
)
