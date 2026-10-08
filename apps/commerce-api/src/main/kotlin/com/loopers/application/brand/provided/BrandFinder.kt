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

    /** 관리자 목록. 최신 등록순으로 읽고 총 개수는 세지 않는다(설계 5.5). */
    fun findAll(@Valid request: BrandAdminListRequest): Slice<Brand>
}
