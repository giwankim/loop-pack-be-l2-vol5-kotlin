package com.loopers.application.shared

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType

/**
 * 목록 유스케이스가 공통으로 받는 페이지 입력. 어느 개념의 목록이든 같은 범위를 쓰므로 `application/shared`에 둔다.
 *
 * 범위를 벗어나면 만들 때 [ErrorType.INVALID_PAGE]로 거절한다. 목록 조회는 여기를 지난 입력만 본다.
 * 도메인 규칙이 아니라 API가 정한 입력 한계라 domain이 아니라 application에 있다(설계 5.21).
 *
 * 페이지 범위와 기본값의 출처는 여기 상수들이다. `ErrorType.INVALID_PAGE`의 메시지와 API 문서의 `@Schema`가
 * 같은 숫자를 사람이 읽을 말로 옮겨 적지만 그 둘은 사본이다. `support`는 `application`을 볼 수 없어
 * 상수를 참조하지 못하므로, 범위를 바꿀 때는 세 곳을 함께 고친다.
 *
 * 기본값을 채우는 길은 [of] 하나다. 생성자가 두 값을 모두 받게 하여 기본값이 두 곳에 적히지 않게 한다.
 *
 * @property page 0부터 세는 조각 번호
 * @property size 한 조각이 담는 최대 개수
 */
data class PageQuery(
    val page: Int,
    val size: Int,
) {
    init {
        if (page < MIN_PAGE || size !in MIN_SIZE..MAX_SIZE) {
            throw CoreException(ErrorType.INVALID_PAGE)
        }
    }

    companion object {
        const val DEFAULT_PAGE = 0
        const val DEFAULT_SIZE = 20
        const val MIN_PAGE = 0
        const val MIN_SIZE = 1
        const val MAX_SIZE = 100

        /** 쿼리 파라미터를 받지 못한 자리는 기본값으로 채운다. */
        fun of(page: Int?, size: Int?): PageQuery =
            PageQuery(page = page ?: DEFAULT_PAGE, size = size ?: DEFAULT_SIZE)
    }
}
