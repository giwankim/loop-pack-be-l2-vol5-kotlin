package com.loopers.application.product.provided

/**
 * 주문 확정의 TX A가 결제 중인 주문의 재고를 예약하는 차감. 예약은 재고에서 미리 빼 두는 것이다(ADR 0026). 상품의 재고는 상품 슬라이스만 바꾼다.
 * 부르는 쪽의 트랜잭션에 참여하므로 예약은 결제의 시작과 함께 커밋되고 함께 되돌아간다(ADR 0024 규칙 1).
 */
interface StockDeductor {
    /**
     * [quantities]의 상품마다 그 수량만큼 재고를 차감한다. 키는 상품 ID이고 값은 줄일 수량이다.
     * 상품을 모두 읽은 뒤에야 차감하므로, 하나라도 없거나 삭제됐으면 어느 재고도 줄이지 않고 `PRODUCT_NOT_FOUND`를 던진다.
     * 그래서 없는 상품이 재고 부족보다 앞서고, 그 차례는 키의 차례와 무관하다(포인트·주문 설계 15).
     * 재고가 모자란 상품이 있으면 [com.loopers.domain.product.InsufficientStockException]을 던지고, 앞서 줄인 재고는 부르는 쪽의 트랜잭션이 되돌린다.
     *
     * 상품 행은 이 차감이 처음 읽으면서 id 오름차순으로 잠근다(ADR 0018). 같은 트랜잭션에서 잠그지 않고 먼저 읽어 둔 상품이면
     * Hibernate가 처음 읽은 필드 값을 그대로 두어 낡은 재고에서 차감하므로, 부르는 쪽은 그 상품들을 미리 읽지 않는다.
     */
    fun deduct(quantities: Map<Long, Int>)
}
