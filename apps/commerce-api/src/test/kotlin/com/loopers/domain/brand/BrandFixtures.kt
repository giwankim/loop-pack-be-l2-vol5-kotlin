package com.loopers.domain.brand

import com.loopers.application.brand.BrandAdminRegisterRequest
import com.loopers.application.brand.BrandAdminUpdateRequest
import org.instancio.Instancio

/** 브랜드 이름. 1..[Brand.NAME_MAX_LENGTH]자의 대문자 영문이다. */
fun brandName(): String {
    return Instancio.gen().string().length(1, Brand.NAME_MAX_LENGTH).upperCase().get()
}

/** 저장하지 않은 브랜드. 진짜 생성자를 거친다. */
fun createBrand(name: String = brandName()): Brand {
    return Brand(name)
}

/** 브랜드 등록 요청. 등록 규칙을 지나는 이름을 가진다. */
fun createBrandAdminRegisterRequest(name: String = brandName()): BrandAdminRegisterRequest {
    return BrandAdminRegisterRequest(name)
}

/** 브랜드 수정 요청. 수정 규칙을 지나는 이름을 가진다. */
fun createBrandAdminUpdateRequest(name: String = brandName()): BrandAdminUpdateRequest {
    return BrandAdminUpdateRequest(name)
}
