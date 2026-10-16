package com.loopers.application.product.provided

/**
 * "고객 상품 목록이 바뀌었다"는 표시. 목록을 바꾸는 쓰기가 그것을 안 자리에서 직접, 동기로, 자기 트랜잭션 안에서 부른다.
 * 이벤트가 아니다. 표시한 트랜잭션은 커밋 바로 앞에 상품 목록 revision을 한 번 올린다(ADR 0020).
 */
interface ProductListChangeMarker {
    /**
     * 지금 트랜잭션이 커밋할 때 revision을 올리게 한다. 한 트랜잭션에서 여러 번 불러도 한 번 올린다.
     * 트랜잭션 밖에서 부르면 `IllegalStateException`을 던진다. 올릴 커밋이 없어 표시가 조용히 사라지기 때문이다.
     *
     * 올리기가 실패하면 트랜잭션이 되돌아가고 예외가 호출자에게 간다. 쓰기도 하나도 반영되지 않는다.
     */
    fun markChanged()
}
