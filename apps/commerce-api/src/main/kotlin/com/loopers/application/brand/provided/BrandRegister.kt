package com.loopers.application.brand.provided

import com.loopers.domain.brand.Brand
import jakarta.validation.Valid

/** 관리자의 브랜드 쓰기. 등록·수정·삭제를 맡는다. */
interface BrandRegister {
    /** 삭제되지 않은 다른 브랜드가 그 이름을 쓰고 있으면 `BRAND_NAME_DUPLICATED`를 던진다. */
    fun register(@Valid request: BrandAdminRegisterRequest): Brand

    /** 없는 브랜드면 `BRAND_NOT_FOUND`, 다른 브랜드가 그 이름을 쓰고 있으면 `BRAND_NAME_DUPLICATED`를 던진다. */
    fun update(id: Long, @Valid request: BrandAdminUpdateRequest): Brand

    /**
     * 브랜드와 그 브랜드의 삭제되지 않은 상품을 함께 삭제한다. 재고가 0인 상품도 삭제하고, 이미 삭제된 상품은 그대로 둔다.
     * 없는 브랜드면 `BRAND_NOT_FOUND`를 던진다. 이미 삭제된 브랜드도 없는 브랜드다.
     */
    fun delete(id: Long)
}
