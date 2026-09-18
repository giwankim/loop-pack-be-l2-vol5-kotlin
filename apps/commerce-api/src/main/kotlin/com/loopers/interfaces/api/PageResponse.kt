package com.loopers.interfaces.api

import com.loopers.domain.shared.PageSlice

/**
 * 목록 응답의 공통 봉투. 총 개수는 주지 않고 다음 조각이 있는지만 준다(설계 5.5).
 * [ApiResponse]의 `data`에 담겨 `{items, page, size, hasNext}`로 내려간다.
 */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    companion object {
        /** domain의 조각을 그대로 옮기고 항목만 [transform]으로 응답 DTO로 바꾼다. */
        fun <T, R> from(slice: PageSlice<T>, transform: (T) -> R): PageResponse<R> =
            PageResponse(
                items = slice.items.map(transform),
                page = slice.page,
                size = slice.size,
                hasNext = slice.hasNext,
            )
    }
}
