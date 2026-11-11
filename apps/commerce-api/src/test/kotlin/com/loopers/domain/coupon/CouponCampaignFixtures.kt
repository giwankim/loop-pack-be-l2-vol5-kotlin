package com.loopers.domain.coupon

import com.loopers.application.coupon.provided.CouponCampaignAdminRegisterRequest
import com.loopers.domain.shared.Money
import com.loopers.domain.unsaved
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KInstancio.gen
import org.instancio.kotlin.KSelect.field

/** An unsaved campaign that has issued nothing and is issuable. The quota stays away from 1, where a single issue would exhaust it. */
fun createCouponCampaign(
    name: String? = null,
    discountAmount: Money? = null,
    totalQuota: Int? = null,
): CouponCampaign {
    return KInstancio.of<CouponCampaign>()
        .unsaved()
        .set(field(CouponCampaign::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .set(field(CouponCampaign::discountAmount), discountAmount ?: Money(gen().longs().range(1, 1_000_000).get()))
        .set(field(CouponCampaign::totalQuota), totalQuota ?: gen().ints().range(100, 1_000).get())
        .set(field(CouponCampaign::issuedCount), 0)
        .set(field(CouponCampaign::issuable), true)
        .create()
}

/** A registration request that passes the rules. The quota stays away from 1, where a single issue would exhaust the campaign. */
fun createCouponCampaignAdminRegisterRequest(
    name: String? = null,
    discountAmount: Long? = null,
    totalQuota: Int? = null,
): CouponCampaignAdminRegisterRequest {
    return KInstancio.of<CouponCampaignAdminRegisterRequest>()
        .set(field(CouponCampaignAdminRegisterRequest::name), name ?: gen().string().minLength(2).maxLength(100).get())
        .set(field(CouponCampaignAdminRegisterRequest::discountAmount), discountAmount ?: gen().longs().range(1, 1_000_000).get())
        .set(field(CouponCampaignAdminRegisterRequest::totalQuota), totalQuota ?: gen().ints().range(100, 1_000).get())
        .create()
}
