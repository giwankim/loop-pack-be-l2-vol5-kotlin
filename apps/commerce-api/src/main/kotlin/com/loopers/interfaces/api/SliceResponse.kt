package com.loopers.interfaces.api

import com.loopers.domain.shared.Slice

/**
 * 목록 응답의 겉모양. 총 개수 대신 다음 조각의 존재만 싣는다(설계 5.5).
 * [ApiResponse]와 같이 개념을 가리지 않는 봉투이므로 개념별 응답 DTO 옆이 아니라 이 자리에 둔다.
 */
data class SliceResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) {
    companion object {
        fun <T, R> from(slice: Slice<T>, transform: (T) -> R): SliceResponse<R> =
            SliceResponse(
                items = slice.items.map(transform),
                page = slice.page,
                size = slice.size,
                hasNext = slice.hasNext,
            )
    }
}
