package com.loopers.support.error

/**
 * [errorType]의 message 앞에 `[detail]`을 붙인다. `detail`은 "id = 1"처럼 실패 원인을 짚는 조각이며 문장 전체가 아니다.
 */
class CoreException(
    val errorType: ErrorType,
    detail: String? = null,
) : RuntimeException(detail?.let { "[$it] ${errorType.message}" } ?: errorType.message)
