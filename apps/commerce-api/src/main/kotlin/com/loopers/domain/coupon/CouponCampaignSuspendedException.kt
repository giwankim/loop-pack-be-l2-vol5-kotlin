package com.loopers.domain.coupon

import com.loopers.domain.shared.RuleViolationException

class CouponCampaignSuspendedException : RuleViolationException("쿠폰 캠페인의 발급이 중지되었습니다.")
