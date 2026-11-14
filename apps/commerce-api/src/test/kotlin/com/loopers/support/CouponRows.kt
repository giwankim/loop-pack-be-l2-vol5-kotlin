package com.loopers.support

import jakarta.persistence.EntityManager

/** Counts the `coupon` table in SQL, since "one coupon row" is a fact of the table and not something the repository should vouch for itself. */
fun EntityManager.countCoupons(): Long {
    return (createNativeQuery("select count(*) from coupon").singleResult as Number).toLong()
}

/** The issued count stored for [campaignId], read in SQL so that no persistence context can answer. */
fun EntityManager.couponCampaignIssuedCount(campaignId: Long): Int {
    val query = createNativeQuery("select issued_count from coupon_campaign where id = :id").setParameter("id", campaignId)

    return (query.singleResult as Number).toInt()
}
