package com.loopers.application.brand.provided

import com.loopers.domain.brand.Brand
import jakarta.validation.Valid
import org.springframework.data.domain.Slice

/**
 * 브랜드 조각이 내주는 읽기. 고객과 관리자의 브랜드 조회가 부른다.
 * 삭제된 브랜드는 없는 브랜드이므로 어느 읽기에도 나오지 않는다.
 */
interface BrandFinder {
    /** [id]가 가리키는 브랜드가 없거나 삭제됐으면 `BRAND_NOT_FOUND`를 던진다. */
    fun find(id: Long): Brand

    /**
     * [id] 브랜드를 잠가 읽는다. 없거나 삭제됐으면 `BRAND_NOT_FOUND`를 던진다. 브랜드의 삭제가 부른다(ADR 0018).
     *
     * 호출자의 쓰기 트랜잭션 안에서만 부른다. 잠금은 그 트랜잭션이 끝날 때 풀린다.
     */
    fun findForUpdate(id: Long): Brand

    /** 관리자 목록. 최신 등록순으로 읽고 총 개수는 세지 않는다(설계 5.5). */
    fun findAll(@Valid request: BrandAdminListRequest): Slice<Brand>
}
