package com.loopers.application.point

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern

/**
 * 포인트 충전 입력. 사용자 식별자는 요청자에서 오므로 여기 없고 [PointService.charge]의 파라미터다(카탈로그 설계 5.27).
 *
 * [chargeKey]는 HTTP의 `Idempotency-Key` 헤더에서 온 충전 키다. 헤더의 존재와 형식은 interfaces가 먼저 거르지만,
 * Controller를 거치지 않는 호출도 같은 규칙을 받도록 제약을 여기에도 둔다(카탈로그 설계 5.25). 형식은 [CHARGE_KEY_PATTERN] 하나이며
 * 대소문자를 구분하고 공백을 떼지 않는다(설계 5.8).
 *
 * [amount]는 1원 이상이다. 충전 후 잔액의 넘침은 domain이 거절한다(설계 5.7).
 * JSON 토큰의 종류를 가리는 일은 HTTP 입력 DTO의 몫이라 여기에는 Jackson 정책이 없다(설계 5.10).
 * 제약이 붙는 자리와 까닭은 [com.loopers.application.product.ProductAdminRegisterRequest]와 같다(카탈로그 설계 5.18).
 */
data class PointChargeRequest(
    @field:Pattern(regexp = CHARGE_KEY_PATTERN, message = "충전 키는 1자 이상 128자 이하의 영문·숫자·하이픈·밑줄이어야 합니다.")
    val chargeKey: String,
    @field:Min(1, message = "충전액은 {value}원 이상이어야 합니다.")
    val amount: Long,
) {
    companion object {
        /** 허용하는 충전 키. ASCII 영문·숫자·하이픈·밑줄 1–128자(설계 5.8). */
        const val CHARGE_KEY_PATTERN = "[A-Za-z0-9_-]{1,128}"
    }
}
