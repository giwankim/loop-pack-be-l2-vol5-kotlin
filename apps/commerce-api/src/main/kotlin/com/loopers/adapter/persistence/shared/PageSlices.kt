package com.loopers.adapter.persistence.shared

import com.loopers.domain.shared.PageSlice
import com.querydsl.jpa.impl.JPAQuery

/**
 * QueryDSL 조회의 한 조각. 차례와 조건은 부르는 쪽이 정하고, 이것은 자르는 일만 한다.
 *
 * 총 개수를 세지 않는다(카탈로그 설계 5.5). `size + 1`개를 읽어 넘치는 하나로 다음 조각의 존재를 정하고
 * 그 하나는 버린다. [PageSlice]의 KDoc이 약속한 규칙이며, 그 규칙이 적히는 자리는 여기 하나다.
 * 상품 목록과 주문 목록이 같은 세 줄을 각자 적고 있었다. [com.loopers.application.shared.toPageSlice]를 모은 것과 같은 까닭이다.
 */
fun <T> JPAQuery<T>.fetchSlice(page: Int, size: Int): PageSlice<T> {
    val rows = offset(page.toLong() * size).limit(size + 1L).fetch()
    return PageSlice(items = rows.take(size), page = page, size = size, hasNext = rows.size > size)
}
