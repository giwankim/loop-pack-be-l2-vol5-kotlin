package com.loopers.domain.point

import com.loopers.application.point.provided.PointChargeRequest
import org.instancio.kotlin.KInstancio
import org.instancio.kotlin.KInstancio.gen
import org.instancio.kotlin.KSelect.field

/**
 * 포인트 충전 요청. `@Min(1)`에는 상한이 없어 충전액을 1..1,000,000원으로 정한다.
 * 기본 충전을 여러 번 더해도 잔액이 넘치지 않는다. 잔액이 주문 합계를 덮는지는 fixture가 보장하지 않는다.
 */
fun createPointChargeRequest(amount: Long? = null): PointChargeRequest {
    return KInstancio.of<PointChargeRequest>()
        .set(field(PointChargeRequest::amount), amount ?: gen().longs().range(1, 1_000_000).get())
        .create()
}
