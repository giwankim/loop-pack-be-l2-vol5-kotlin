package com.loopers.domain.shared

/**
 * 목록 한 조각. 총 개수는 세지 않고 다음 조각이 있는지만 안다(설계 5.5).
 *
 * 저장소가 `size + 1`개를 조회해 [hasNext]를 정하고 넘치는 하나는 [items]에서 버린다.
 * 여러 개념의 목록이 같은 모양을 쓰므로 `domain/shared`에 둔다.
 *
 * 이름에 `Page`를 붙인 까닭은 Spring Data의 `Slice`, 그리고 아키텍처 테스트가 말하는 패키지 조각과
 * 한 트리 안에서 이름이 겹치지 않게 하려는 것이다(설계 5.21).
 *
 * @property items 이 조각의 항목. 많아야 [size]개다
 * @property page 0부터 세는 조각 번호
 * @property size 한 조각이 담는 최대 개수
 * @property hasNext 다음 조각이 있는지
 */
data class PageSlice<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    /**
     * 항목만 다른 타입으로 옮긴 같은 조각. 조각의 위치와 다음 조각의 존재는 그대로다.
     *
     * 항목이 지연 로딩되는 연관을 읽어야 하면 옮기는 일이 트랜잭션 안에서 끝나야 하므로,
     * 응답 봉투가 아니라 조각 자신이 이 일을 할 수 있어야 한다([com.loopers.application.product.ProductService.findAll]).
     */
    fun <R> map(transform: (T) -> R): PageSlice<R> =
        PageSlice(items = items.map(transform), page = page, size = size, hasNext = hasNext)
}
