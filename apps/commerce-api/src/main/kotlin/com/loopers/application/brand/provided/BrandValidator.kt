package com.loopers.application.brand.provided

import com.loopers.domain.brand.Brand
import jakarta.validation.Valid

/**
 * 브랜드를 바꾸기 전에 보는 사전 조건. 저장소나 상품 조각에 물어야 답할 수 있는 것만 여기 있고,
 * 이름 규칙은 [Brand]가 지킨다(ADR 0014). 첫 거절에서 던지고 멈춘다.
 *
 * Request를 받으므로 다른 provided 포트처럼 그 제약도 검증한다. [BrandRegister]를 거쳐 오면 이미 검증된 값이지만,
 * 이 포트를 따로 부르는 쪽도 같은 검사를 받는다(카탈로그 설계 5.25).
 */
interface BrandValidator {
    /** 삭제되지 않은 브랜드가 그 이름을 쓰고 있으면 `BRAND_NAME_DUPLICATED`를 던진다. */
    fun validateForRegister(@Valid request: BrandAdminRegisterRequest)

    /** [brand]가 아닌 삭제되지 않은 브랜드가 그 이름을 쓰고 있으면 `BRAND_NAME_DUPLICATED`를 던진다. */
    fun validateForUpdate(brand: Brand, @Valid request: BrandAdminUpdateRequest)

    /** 삭제되지 않은 상품이 하나라도 남아 있으면 `BRAND_HAS_PRODUCTS`를 던진다. 재고가 0인 상품도 남은 상품이다. */
    fun validateForDelete(brand: Brand)
}
