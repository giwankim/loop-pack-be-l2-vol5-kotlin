package com.loopers.adapter.persistence.shared

import com.querydsl.jpa.impl.JPAQuery
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl

/**
 * QueryDSL 조회의 한 조각. 차례와 조건은 부르는 쪽이 정하고, 이것은 자르는 일만 한다.
 *
 * 총 개수를 세지 않는다(카탈로그 설계 5.5). `size + 1`개를 읽어 넘치는 하나로 다음 조각의 존재를 정하고
 * 그 하나는 버린다. Spring Data가 [Slice]를 돌려주는 조회에서 하는 일과 같고, QueryDSL 쪽에서 그 규칙이 적히는 자리는
 * 여기 하나다. 상품 목록과 주문 목록이 이것을 쓴다.
 *
 * [pageable]에서는 위치와 크기만 읽는다. 거기 실린 정렬은 보지 않으므로 차례는 부르는 쪽의 `orderBy`가 정한다.
 */
fun <T : Any> JPAQuery<T>.fetchSlice(pageable: Pageable): Slice<T> {
    val rows = offset(pageable.offset).limit(pageable.pageSize + 1L).fetch()
    return SliceImpl(rows.take(pageable.pageSize), pageable, rows.size > pageable.pageSize)
}
