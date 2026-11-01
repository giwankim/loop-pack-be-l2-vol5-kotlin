package com.loopers.support

import com.loopers.application.order.OrderPaymentSagaSteps
import com.loopers.application.payment.PaymentModifyService
import com.loopers.application.payment.PgTransactionService
import io.mockk.every
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.util.AopTestUtils

/**
 * Runs [action] right after confirm's create is answered, before the answer goes to TX B: the PG holds a transaction and TX B
 * hasn't started. The PG call is outside any transaction, so whatever port [action] calls runs in its own, and a `NEVER` port
 * is allowed. A stub on the TX B bean would run inside the transaction its proxy opened. The receiver is a `@MockkSpyBean`
 * field, a CGLIB proxy, so the stub goes on the spy it wraps.
 */
fun PgTransactionService.afterTheCreateIsAnswered(action: () -> Unit) {
    val target = AopTestUtils.getUltimateTargetObject<PgTransactionService>(this)
    every { target.create(any(), any()) } answers {
        val transaction = callOriginal()
        action()
        transaction
    }
}

/**
 * Stops the first TX B that applies a PG transaction to the payment on [pause], just before it does, while it holds the order.
 * Later calls are not stopped, so another TX B applying the same answer waits on the order lock. The receiver is a
 * `@MockkSpyBean` field, a CGLIB proxy, so the stub goes on the spy it wraps.
 */
fun PaymentModifyService.pauseTheFirstTxBHoldingTheOrder(pause: Pause) {
    val target = AopTestUtils.getUltimateTargetObject<PaymentModifyService>(this)
    every { target.reconcile(any(), any()) } answers {
        pause.hold()
        callOriginal()
    } andThenAnswer { callOriginal() }
}

/**
 * The TX B entry for a PG transaction changes the payment and the order, flushes them through [entityManager] and then throws
 * once, as if the database failed before the commit, so the whole TX B rolls back. Later calls are the real ones. TX B's
 * transaction is opened by the proxy the receiver is, so the stub goes on the spy it wraps.
 */
fun OrderPaymentSagaSteps.failTxBOnceAfterItsWrites(entityManager: EntityManager) {
    val target = AopTestUtils.getUltimateTargetObject<OrderPaymentSagaSteps>(this)
    every { target.applyTransaction(any(), any(), any()) } answers {
        callOriginal()
        entityManager.flush()
        throw DataAccessResourceFailureException("The database failed before TX B committed.")
    } andThenAnswer { callOriginal() }
}
