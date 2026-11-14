package com.loopers.domain.coupon

import com.loopers.domain.shared.Money
import org.instancio.kotlin.KInstancio.gen
import java.time.Instant

/**
 * An unsaved coupon of [campaignId] held by [userId]. Both are foreign keys, so a saved coupon needs saved rows behind them.
 * It goes through the real constructor because the constructor computes a field: it truncates the issue time (ADR 0010).
 */
fun createCoupon(
    campaignId: Long,
    userId: Long,
): Coupon {
    return Coupon(
        campaignId = campaignId,
        userId = userId,
        campaignName = gen().string().minLength(2).maxLength(100).get(),
        discountAmount = Money(gen().longs().range(1, 1_000_000).get()),
        issuedAt = Instant.now(),
    )
}
