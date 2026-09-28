package com.loopers.domain.point

/** 포인트 이력 저장 약속. 이력은 남긴 뒤 바꾸지 않으므로 저장뿐이다. */
interface PointHistoryRepository {
    fun save(history: PointHistory): PointHistory
}
