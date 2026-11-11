package com.loopers.domain.coupon

import com.loopers.domain.shared.RuleViolationException

class CouponCampaignExhaustedException : RuleViolationException("쿠폰 캠페인의 발급 한도가 소진되었습니다.")
