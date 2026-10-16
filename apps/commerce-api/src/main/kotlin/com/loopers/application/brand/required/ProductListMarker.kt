package com.loopers.application.brand.required

/**
 * 브랜드 이름 변경이 상품 조각에 시키는 것. 고객 상품 목록은 항목마다 브랜드 이름을 실으므로, 이름이 바뀌면 목록도 바뀐다(ADR 0020).
 *
 * [ProductDeleter]처럼 브랜드가 모양을 선언하고 상품 조각이 직접 구현한다. 상품 조각이 이미 브랜드에 기대므로
 * 브랜드가 상품 조각의 표시 포트를 부르면 조각 사이에 순환이 생긴다(ADR 0017). 브랜드 삭제는 [ProductDeleter.deleteAllOfBrand]가
 * 표시하므로 이 포트는 이름 변경만 쓴다.
 */
interface ProductListMarker {
    /**
     * 지금 트랜잭션이 커밋할 때 상품 목록 revision을 올리게 한다. 한 트랜잭션에서 여러 번 불러도 한 번 올린다.
     * 트랜잭션 밖에서 부르면 `IllegalStateException`을 던진다. 올리기가 실패하면 트랜잭션이 되돌아가고 예외가 호출자에게 간다.
     */
    fun markChanged()
}
