package com.loopers.application.coupon.required

import com.loopers.domain.coupon.CouponCampaign
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository

/** The coupon campaign store. Spring Data builds the implementation. */
interface CouponCampaignRepository : Repository<CouponCampaign, Long> {
    fun save(couponCampaign: CouponCampaign): CouponCampaign

    /** Reads the campaign `FOR UPDATE` by primary key, so only its row is locked. Null if it is missing or deleted (ADR 0018). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(campaignId: Long): CouponCampaign?
}
