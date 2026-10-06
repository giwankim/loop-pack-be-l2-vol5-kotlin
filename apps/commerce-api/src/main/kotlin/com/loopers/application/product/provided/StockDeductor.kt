package com.loopers.application.product.provided

/**
 * 주문 확정이 부르는 재고 차감. 상품의 재고는 상품 조각만 바꾼다.
 * 부르는 쪽의 트랜잭션에 참여하므로 차감은 확정과 함께 커밋되고 함께 되돌아간다(ADR 0003).
 */
interface StockDeductor {
    /**
     * [productId] 상품의 재고에서 [quantity]개를 차감한다. 상품이 없거나 삭제됐으면 `PRODUCT_NOT_FOUND`를 던진다.
     * 재고가 모자라면 재고를 그대로 두고 [com.loopers.domain.product.InsufficientStockException]을 던진다.
     * 주문할 수 있는 상품인지는 부르는 쪽이 이미 확인했다.
     */
    fun deduct(productId: Long, quantity: Int)
}
