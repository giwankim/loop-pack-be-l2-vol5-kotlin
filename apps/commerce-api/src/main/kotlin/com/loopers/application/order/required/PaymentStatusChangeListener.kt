package com.loopers.application.order.required

import com.loopers.domain.payment.PaymentStatus

/**
 * Hears that a payment's status changed and the change committed (spec 281). It is told of status changes only: what a listener
 * makes of them, such as which statuses it counts, is the listener's to decide. The implementation is the gauge
 * `commerce.payment.unresolved` in `adapter.monitoring`.
 *
 * The order slice's saga calls it outside both transactions, right after the one that made the change returned: after TX A
 * returns a new payment, and after TX B commits a changed result. A replay, an unchanged or conflicted result and a rolled-back
 * transaction change no committed status, so they call nothing. It is the order slice's required port because that saga is its
 * only caller and the only code that knows the change committed (ADR 0013: the asking slice declares the port).
 */
interface PaymentStatusChangeListener {
    /**
     * A payment's status went from [from] to [to] and the change committed. [from] is `null` for a payment that was just created.
     * The implementation only changes numbers in memory and throws nothing, so the caller does not catch.
     */
    fun statusChanged(from: PaymentStatus?, to: PaymentStatus)
}
