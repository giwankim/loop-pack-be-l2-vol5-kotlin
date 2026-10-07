package com.loopers.adapter.webapi

import org.springframework.data.domain.Slice

/**
 * 목록 응답의 공통 봉투. 총 개수는 주지 않고 다음 조각이 있는지만 준다(설계 5.5).
 * [ApiResponse]의 `data`에 담겨 `{items, page, size, hasNext}`로 내려간다.
 *
 * Spring Data의 [Slice]를 그대로 직렬화하지 않는다. 그 모양은 Spring Data가 정하고, 이 봉투의 모양은 API가 정한다.
 */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    companion object {
        /** 조각의 위치·크기·다음 조각의 존재를 그대로 옮기고 항목만 [transform]으로 응답 DTO로 바꾼다. */
        fun <T : Any, R> from(slice: Slice<T>, transform: (T) -> R): PageResponse<R> =
            PageResponse(
                items = slice.content.map(transform),
                page = slice.number,
                size = slice.size,
                hasNext = slice.hasNext(),
            )
    }
}
