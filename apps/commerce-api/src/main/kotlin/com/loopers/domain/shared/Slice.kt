package com.loopers.domain.shared

/**
 * 목록 조회의 한 조각. 총 개수 대신 다음 조각의 존재만 알려준다(설계 5.5).
 * 저장 약속이 Spring Data를 모르도록 domain이 가진 타입이며, 다음 조각의 존재를 어떻게 알아내는지는 구현이 정한다.
 */
data class Slice<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    /** 항목만 다른 타입으로 옮긴 같은 조각. 조각의 위치와 다음 조각의 존재는 그대로다. */
    fun <R> map(transform: (T) -> R): Slice<R> =
        Slice(items = items.map(transform), page = page, size = size, hasNext = hasNext)
}
