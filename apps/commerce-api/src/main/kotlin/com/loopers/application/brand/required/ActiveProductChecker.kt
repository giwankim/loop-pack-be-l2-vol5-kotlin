package com.loopers.application.brand.required

/**
 * 브랜드 삭제 조건이 상품 조각에 묻는 것. 브랜드는 자기 상품을 모르고, 답은 상품 쪽에만 있다(설계 5.1, 5.8).
 *
 * 묻는 쪽인 브랜드가 모양을 선언하고 상품 조각의 Finder가 이것을 상속해 구현한다. 그래서 브랜드는 상품을 참조하지 않고,
 * 의존은 도메인에서처럼 상품에서 브랜드로만 간다.
 */
interface ActiveProductChecker {
    /** [brandId] 브랜드에 삭제되지 않은 상품이 하나라도 남아 있는지. 재고가 0인 상품도 남은 상품이다(CONTEXT.md 삭제되지 않은). */
    fun hasActiveProducts(brandId: Long): Boolean
}
