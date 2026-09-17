package com.loopers.utils

import jakarta.persistence.EntityManager

/** 영속성 컨텍스트를 비워 다음 조회가 DB에서 다시 읽게 한다. */
fun EntityManager.flushAndClear() {
    flush()
    clear()
}
