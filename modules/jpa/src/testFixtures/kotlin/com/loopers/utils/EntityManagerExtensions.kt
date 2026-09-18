package com.loopers.utils

import jakarta.persistence.EntityManager

/**
 * 쌓인 변경을 DB로 내보내고 영속성 컨텍스트를 비워 다음 조회가 DB에서 다시 읽게 한다.
 *
 * 한 트랜잭션 안에서 쓰고 곧 읽는 테스트는 이것 없이는 1차 캐시가 답한다. 캐시는 조금 전 쓴 객체를
 * 그대로 돌려주므로 `@SQLRestriction`의 삭제 필터가 붙을 자리가 없고, 쓰기가 실제로 DB에 닿았는지도
 * 확인되지 않는다. 운영에서는 요청마다 컨텍스트가 새로 열려 저절로 되는 일이다.
 */
fun EntityManager.flushAndClear() {
    flush()
    clear()
}
