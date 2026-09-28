package com.loopers.application.point

import jakarta.validation.constraints.Min

/**
 * 포인트 충전 입력. 사용자 식별자는 요청자에서 오므로 여기 없고 [PointService.charge]의 파라미터다(카탈로그 설계 5.27).
 *
 * [amount]는 1원 이상이다. 충전 후 잔액의 넘침은 domain이 거절한다(설계 5.7).
 * JSON 토큰의 종류를 가리는 일은 HTTP 입력 DTO의 몫이라 여기에는 Jackson 정책이 없다(설계 5.10).
 * 제약이 붙는 자리와 까닭은 [com.loopers.application.product.ProductAdminRegisterRequest]와 같다(카탈로그 설계 5.18).
 */
data class PointChargeRequest(
    @field:Min(1, message = "충전액은 {value}원 이상이어야 합니다.")
    val amount: Long,
)
