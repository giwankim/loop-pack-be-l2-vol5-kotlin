package com.loopers.application.brand.provided

import com.loopers.domain.brand.Brand
import jakarta.validation.Valid

/** 관리자의 브랜드 쓰기. 등록·수정·삭제를 맡는다. */
interface BrandRegister {
    /** 삭제되지 않은 다른 브랜드가 그 이름을 쓰고 있으면 `BRAND_NAME_DUPLICATED`를 던진다. */
    fun register(@Valid request: BrandAdminRegisterRequest): Brand

    /** 없는 브랜드면 `BRAND_NOT_FOUND`, 다른 브랜드가 그 이름을 쓰고 있으면 `BRAND_NAME_DUPLICATED`를 던진다. */
    fun update(id: Long, @Valid request: BrandAdminUpdateRequest): Brand

    /** 없는 브랜드면 `BRAND_NOT_FOUND`, 삭제되지 않은 상품이 남아 있으면 `BRAND_HAS_PRODUCTS`를 던진다. */
    fun delete(id: Long)
}
