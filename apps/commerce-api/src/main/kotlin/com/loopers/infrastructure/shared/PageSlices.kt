package com.loopers.infrastructure.shared

import com.loopers.domain.shared.PageSlice
import org.springframework.data.domain.Slice

/**
 * Spring Data의 조각을 domain의 [PageSlice]로 옮긴다. 위치와 크기는 조회에 실어 보낸 [org.springframework.data.domain.Pageable]이
 * 정한 값 그대로이므로 Spring의 조각에서 읽는다.
 *
 * 저장소마다 같은 두 줄을 적고 있었다. 옮기는 규칙이 하나이므로 자리도 하나여야 한다.
 * domain이 아니라 infrastructure에 두는 까닭은 [Slice]가 Spring Data의 타입이라서다. domain은 그것을 모른다.
 */
fun <T> Slice<T>.toPageSlice(): PageSlice<T> =
    PageSlice(items = content, page = number, size = size, hasNext = hasNext())
