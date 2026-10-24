package com.loopers.domain.payment

/**
 * Mints the PG order ID of a payment that is starting (`CONTEXT.md` pgOrderId, ADR 0029). It takes no order ID: an ID derived
 * from the order is unique only within one life of one database, so after a reset or restore an old PG transaction could
 * confirm a new, unpaid order.
 *
 * The production implementation lives in the PG adapter, which knows the PG's format rules. Tests pass a generator that
 * returns a fixed value, because a test PG stub must know the PG order ID before the payment calls the PG.
 */
fun interface PgOrderIdGenerator {
    /** A PG order ID no other payment has used. */
    fun generate(): String
}
